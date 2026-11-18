package local.station.wifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import hev.htproxy.TProxyService;
import local.station.relay.SelectiveSocksGateway;
import local.station.relay.WifiLocalRelay;
import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Bounded selective-TV experiment, isolated from the normal relay and boot mode. */
public final class TvRelayService extends VpnService {
    private static final int GATEWAY_PORT=17696;
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final Object writeLock=new Object();
    private final FileDescriptor pump=new FileDescriptor(),engine=new FileDescriptor();
    private final AtomicLong dnsLocal=new AtomicLong(),dnsUpstream=new AtomicLong(),dnsErrors=new AtomicLong(),dropped=new AtomicLong();
    private volatile ParcelFileDescriptor tunnel;
    private ParcelFileDescriptor nativeFd;
    private volatile boolean nativeStarted,upstreamReady,localReady,gatewayReady;
    private volatile long deadline;
    private volatile String code="starting",failureStage="",failureType="";
    private TvPrivateConfig config;
    private TvUidPolicy uidPolicy;
    private TcpFlowAdmission admission;
    private LocalPacketPolicy localPolicy;
    private WifiLocalRelay localRelay;
    private SelectiveSocksGateway gateway;
    private Thread worker,reverse;
    private ThreadPoolExecutor dns;
    private File nativeConfig;

    @Override public void onCreate(){
        super.onCreate();NotificationChannel channel=new NotificationChannel("station_tv_test","Станция · ТВ-тест",NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(3,new Notification.Builder(this,"station_tv_test").setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Станция · Проверка ТВ-сети").setOnlyAlertOnce(true).build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(worker!=null)return START_NOT_STICKY;
        if(intent==null||!"tv_start".equals(intent.getAction())){code="invalid_request";writeStatus();stopSelf();return START_NOT_STICKY;}
        try{
            config=TvPrivateConfig.take(getFilesDir());deadline=SystemClock.elapsedRealtime()+config.durationMs;
            // Independent deadline starts before any readiness/metadata work. It
            // kills only this declared :tvnetwork process, never another app.
            Thread hard=new Thread(()->{SystemClock.sleep(config.durationMs+3000);Process.killProcess(Process.myPid());},"tv-hard-deadline");
            hard.setDaemon(true);hard.start();
            if(VpnService.prepare(this)!=null)throw new IllegalStateException("consent_required");
            String guard=TvControl.guard(this,true);if(guard!=null)throw new IllegalStateException("another_vpn_or_relay");
            dns=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(8),new ThreadPoolExecutor.AbortPolicy());
            worker=new Thread(this::run,"tv-relay-test");worker.setDaemon(true);worker.start();
        }catch(Exception failure){failureStage=code;failureType=failure.getClass().getSimpleName();code="failed";writeStatus();stopSelf();}
        return START_NOT_STICKY;
    }
    private void run(){
        try{
            code="uid_policy";PackageManager pm=getPackageManager();
            Set<Integer> denied=new HashSet<Integer>(Arrays.asList(0,1000,2000,10005,Process.myUid()));
            denied.add(pm.getApplicationInfo("ru.yandex.quasar.app",0).uid);
            int happUid=Process.myUid();
            for(String name:new String[]{getPackageName(),"com.android.shell","local.station.agent","local.station.voicebridge","su.happ.proxyutility","com.psiphon3"}){
                try{int uid=pm.getApplicationInfo(name,0).uid;denied.add(uid);if("su.happ.proxyutility".equals(name))happUid=uid;}
                catch(PackageManager.NameNotFoundException absent){}
            }
            if(config.upstreamPackage!=null){int uid=pm.getApplicationInfo(config.upstreamPackage,0).uid;denied.add(uid);happUid=uid;}
            uidPolicy=new TvUidPolicy(config.tvPackages,new TvUidPolicy.Packages(){
                public int uidForPackage(String name)throws Exception{return pm.getApplicationInfo(name,0).uid;}
                public Set<String> packagesForUid(int uid){String[] names=pm.getPackagesForUid(uid);return names==null?null:new HashSet<String>(Arrays.asList(names));}
            },denied);
            admission=new TcpFlowAdmission((local,lp,remote,rp)->{
                ProcSocketOwner.Result result=ProcSocketOwner.fromProc(ProcSocketOwner.TcpState.SYN_SENT,new ProcSocketOwner.Tuple(local,lp,remote,rp));
                ProcSocketOwner.Owner owner=result.owners[0];int uid=owner.uid();
                // A changed package/shared UID mapping must not reuse startup permission.
                boolean complete=result.complete();
                if(complete&&owner.found()&&!owner.ambiguous()&&uidPolicy.allowedUids().contains(uid)&&!uidPolicy.stillAllowed(uid))
                    return new TcpFlowAdmission.OwnerObservation(true,true,false,-1);
                return new TcpFlowAdmission.OwnerObservation(complete,owner.found(),owner.ambiguous(),uid);
            },uidPolicy.allowedUids(),denied,Process.myUid(),happUid);
            localPolicy=new LocalPacketPolicy();
            code="upstream_probe";probeUpstream();upstreamReady=true;
            code="native_prepare";
            TProxyService.loadVerified(new File(getApplicationInfo().nativeLibraryDir,"libhev-socks5-tunnel.so").getAbsolutePath(),WifiBuild.NATIVE_SHA256);
            localRelay=new WifiLocalRelay(DnsPolicy.SERVICE_IP,0);
            Thread local=new Thread(()->{try{localRelay.run();}catch(Exception ignored){}},"tv-local-relay");local.setDaemon(true);local.start();
            waitReady(()->localRelay.isReady(),local,1500);localReady=true;
            SelectiveSocksGateway.Capability cap=SelectiveSocksGateway.Capability.random();
            SelectiveSocksGateway.SocksHop hop=config.username()==null?SelectiveSocksGateway.SocksHop.loopback(config.proxyPort)
                    :SelectiveSocksGateway.SocksHop.authenticatedLoopback(config.proxyPort,config.username(),config.password());
            gateway=new SelectiveSocksGateway(DnsPolicy.SERVICE_IP,GATEWAY_PORT,17694,hop,cap,true,SelectiveSocksGateway.Limits.defaults());
            Thread gw=new Thread(()->{try{gateway.run();}catch(Exception ignored){}},"tv-auth-gateway");gw.setDaemon(true);gw.start();
            waitReady(()->gateway.isReady(),gw,1500);gatewayReady=true;
            nativeConfig=new File(getFilesDir(),"tv-native-private.yml");
            String yaml="tunnel:\n  name: station-tv-test\n  mtu: 1500\n  ipv4: "+DnsPolicy.TUN_IP+"\n  icmp: 'off'\n  multi-queue: false\n  offload:\n    tso: false\n    uso: false\n    jumbo: false\n"
                    +"socks5:\n  address: 127.0.0.1\n  port: "+GATEWAY_PORT+"\n  username: '"+cap.username()+"'\n  password: '"+cap.password()+"'\n  pipeline: false\n  udp: 'udp'\n"
                    +"misc:\n  max-session-count: 64\n  connect-timeout: 2500\n  tcp-read-write-timeout: 600000\n  udp-read-write-timeout: 1000\n  log-file: null\n  log-level: error\n  limit-nofile: 1024\n";
            Files.write(nativeConfig.toPath(),yaml.getBytes(StandardCharsets.US_ASCII));
            Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM|OsConstants.O_NONBLOCK|OsConstants.O_CLOEXEC,0,pump,engine);
            nativeFd=ParcelFileDescriptor.dup(engine);nativeStarted=TProxyService.TProxyStartService(nativeConfig.getAbsolutePath(),nativeFd.getFd());
            SystemClock.sleep(150);if(!nativeStarted||!TProxyService.TProxyIsRunning())throw new IllegalStateException("native_not_running");
            code="establishing";String guard=TvControl.guard(this,true);if(guard!=null||!active())throw new IllegalStateException("startup_guard");
            Builder builder=new Builder().setSession("Station bounded selective TV")
                    .addAddress(DnsPolicy.TUN_IP,32).addRoute(DnsPolicy.SERVICE_IP,32).addRoute(DnsPolicy.DNS_IP,32)
                    .addDnsServer(DnsPolicy.DNS_IP).setMtu(1500).setBlocking(false);
            for(TvRoutePlan.Route route:TvRoutePlan.publicIpv4())builder.addRoute(route.address,route.prefix);
            for(String name:new String[]{getPackageName(),"com.android.shell","local.station.agent","local.station.voicebridge","su.happ.proxyutility","com.psiphon3"}){
                try{pm.getApplicationInfo(name,0);builder.addDisallowedApplication(name);}catch(PackageManager.NameNotFoundException absent){}
            }
            if(config.upstreamPackage!=null)builder.addDisallowedApplication(config.upstreamPackage);
            if(!active())return;
            ParcelFileDescriptor fresh=builder.establish();if(fresh==null)throw new IllegalStateException("vpn_not_established");
            synchronized(writeLock){if(!active()){fresh.close();return;}tunnel=fresh;}
            reverse=new Thread(this::reverse,"tv-tun-return");reverse.setDaemon(true);reverse.start();
            code="active";long statusAt=0;byte[] packet=new byte[65535];
            while(active()){
                if(!localRelay.isReady()||!gateway.isReady()||!TProxyService.TProxyIsRunning())throw new IllegalStateException("relay_unavailable");
                long now=SystemClock.elapsedRealtime();if(now-statusAt>=1000){writeStatus();statusAt=now;}
                int length=read(tunnel.getFileDescriptor(),packet);if(length==0)continue;
                DnsPolicy.Query query=DnsPolicy.parsePacket(packet,length);
                if(query!=null){byte[] response=DnsPolicy.localResponse(query);
                    if(response!=null){writeTun(response,response.length);dnsLocal.incrementAndGet();}
                    else try{dns.execute(()->forwardDns(query));}catch(java.util.concurrent.RejectedExecutionException busy){dnsErrors.incrementAndGet();dnsFailure(query);}
                }else if(localPolicy.outbound(packet,length,now)||admission.outbound(packet,length,now))write(pump,packet,length);
                else dropped.incrementAndGet();
            }
            code="deadline";
        }catch(Exception failure){failureStage=code;failureType=failure.getClass().getSimpleName();code="failed";}
        finally{writeStatus();stopSelf();}
    }
    private interface Ready {boolean ready();}
    private void waitReady(Ready ready,Thread thread,int milliseconds)throws Exception{
        long until=Math.min(deadline,SystemClock.elapsedRealtime()+milliseconds);
        while(!ready.ready()&&thread.isAlive()&&active()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(10);
        if(!ready.ready()||!active())throw new IllegalStateException("loopback_not_ready");
    }
    private void probeUpstream()throws Exception{
        try(Socket socket=new Socket()){
            if(!protect(socket))throw new IllegalStateException("protect_failed");
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),config.proxyPort),750);socket.setSoTimeout(750);
            InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();int method=config.username()==null?0:2;
            output.write(new byte[]{5,1,(byte)method});output.flush();if(input.read()!=5||input.read()!=method)throw new IllegalStateException("proxy_method_unavailable");
            if(method==2){byte[] user=config.username().getBytes(StandardCharsets.UTF_8),pass=config.password().getBytes(StandardCharsets.UTF_8);
                try{output.write(1);output.write(user.length);output.write(user);output.write(pass.length);output.write(pass);output.flush();
                    if(input.read()!=1||input.read()!=0)throw new IllegalStateException("proxy_auth_failed");}
                finally{Arrays.fill(user,(byte)0);Arrays.fill(pass,(byte)0);}
            }
        }
    }
    private void reverse(){
        byte[] packet=new byte[65535];
        try{while(active()){int length=read(pump,packet);if(length==0)continue;long now=SystemClock.elapsedRealtime();
            if(localPolicy.inbound(packet,length,now)||admission.inbound(packet,length,now))writeTun(packet,length);else dropped.incrementAndGet();
        }}catch(Exception failure){if(active()){code="return_path_failed";stopSelf();}}
    }
    private void forwardDns(DnsPolicy.Query query){
        try{
            ConnectivityManager cm=getSystemService(ConnectivityManager.class);Network selected=null;InetAddress resolver=null;
            for(Network network:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);LinkProperties links=cm.getLinkProperties(network);
                if(caps==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||links==null)continue;
                for(InetAddress address:links.getDnsServers())if(privateLanResolver(address)&&directlyConnected(links,address)){
                    if(selected==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){selected=network;resolver=address;}break;
                }
                if(selected!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))break;
            }
            if(selected==null||resolver==null)throw new IllegalStateException("private_lan_dns_unavailable");
            try(DatagramSocket socket=new DatagramSocket()){
                if(!protect(socket))throw new IllegalStateException("protect_failed");selected.bindSocket(socket);socket.setSoTimeout(1200);
                socket.connect(resolver,53);socket.send(new DatagramPacket(query.dnsBytes,query.dnsBytes.length));
                byte[] bytes=new byte[4096];DatagramPacket response=new DatagramPacket(bytes,bytes.length);socket.receive(response);
                byte[] reply=DnsPolicy.reply(query,Arrays.copyOf(bytes,response.getLength()));if(reply==null)throw new IllegalStateException("invalid_dns_reply");
                writeTun(reply,reply.length);dnsUpstream.incrementAndGet();
            }
        }catch(Exception failure){dnsErrors.incrementAndGet();dnsFailure(query);}
    }
    static boolean privateLanResolver(InetAddress address){
        byte[] bytes=address.getAddress();if(bytes.length!=4)return false;int a=bytes[0]&255,b=bytes[1]&255;
        return a==10||a==172&&b>=16&&b<=31||a==192&&b==168;
    }
    private static boolean directlyConnected(LinkProperties links,InetAddress address){
        for(RouteInfo route:links.getRoutes())if(route.getGateway()!=null&&route.getGateway().isAnyLocalAddress()
                &&route.getDestination().getPrefixLength()>0&&route.getDestination().contains(address))return true;
        return false;
    }
    private void dnsFailure(DnsPolicy.Query query){try{byte[] bytes=query.dnsBytes.clone();bytes[2]=(byte)0x81;bytes[3]=(byte)0x82;byte[] reply=DnsPolicy.reply(query,bytes);if(reply!=null)writeTun(reply,reply.length);}catch(Exception ignored){}}
    private boolean active(){return !stopped.get()&&deadline>0&&SystemClock.elapsedRealtime()<deadline;}
    private int read(FileDescriptor fd,byte[] buffer)throws Exception{
        StructPollfd poll=new StructPollfd();poll.fd=fd;poll.events=(short)OsConstants.POLLIN;if(Os.poll(new StructPollfd[]{poll},100)==0)return 0;
        if((poll.revents&(OsConstants.POLLERR|OsConstants.POLLHUP|OsConstants.POLLNVAL))!=0)throw new IllegalStateException("closed_descriptor");
        try{return Os.read(fd,buffer,0,buffer.length);}catch(ErrnoException retry){if(retry.errno==OsConstants.EAGAIN||retry.errno==OsConstants.EINTR)return 0;throw retry;}
    }
    private void write(FileDescriptor fd,byte[] bytes,int length)throws Exception{
        long until=SystemClock.elapsedRealtime()+300;while(active()&&SystemClock.elapsedRealtime()<until){
            try{if(Os.write(fd,bytes,0,length)!=length)throw new IllegalStateException("partial_packet");return;}
            catch(ErrnoException retry){if(retry.errno!=OsConstants.EAGAIN&&retry.errno!=OsConstants.EINTR)throw retry;SystemClock.sleep(5);}
        }throw new IllegalStateException("packet_write_deadline");
    }
    private void writeTun(byte[] bytes,int length)throws Exception{synchronized(writeLock){ParcelFileDescriptor current=tunnel;if(current!=null&&active())write(current.getFileDescriptor(),bytes,length);}}
    private void writeStatus(){
        try{
            JSONObject value=new JSONObject().put("nonce",config==null?"":config.nonce).put("code",code).put("failureStage",failureStage).put("failureType",failureType)
                    .put("updatedAtMs",System.currentTimeMillis()).put("updatedAtElapsedMs",SystemClock.elapsedRealtime()).put("pid",Process.myPid())
                    .put("active",tunnel!=null&&active()&&"active".equals(code)).put("upstreamReady",upstreamReady).put("localReady",localReady).put("gatewayReady",gatewayReady)
                    .put("remainingMs",Math.max(0,deadline-SystemClock.elapsedRealtime())).put("dnsLocal",dnsLocal.get()).put("dnsUpstream",dnsUpstream.get()).put("dnsErrors",dnsErrors.get())
                    .put("dropped",dropped.get()).put("persistent",false).put("otaProtectionGuaranteed",false);
            if(admission!=null){TcpFlowAdmission.Snapshot s=admission.snapshot();value.put("admission",new JSONObject().put("activeFlows",s.activeFlows).put("ownerLookups",s.ownerLookups)
                    .put("ownerAllowed",s.ownerAllowed).put("denied",s.denied).put("outboundPackets",s.outboundPackets).put("inboundPackets",s.inboundPackets)
                    .put("generations",s.generations).put("lastDecision",s.lastDecision.name()).put("lastOwnerDecision",s.lastOwnerDecision.name()).put("lastOwnerUid",s.lastOwnerUid));}
            if(gateway!=null)value.put("gateway",new JSONObject(gateway.summary()));
            File temporary=new File(getFilesDir(),"tv-status.tmp");Files.write(temporary.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(),new File(getFilesDir(),"tv-status.json").toPath(),StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){}
    }
    @Override public void onRevoke(){code="revoked";stopSelf();}
    @Override public void onDestroy(){
        if(!stopped.compareAndSet(false,true))return;
        synchronized(writeLock){ParcelFileDescriptor current=tunnel;tunnel=null;if(current!=null)try{current.close();}catch(Exception ignored){}}
        if(dns!=null)dns.shutdownNow();if(gateway!=null)gateway.close();if(localRelay!=null)localRelay.close();close(pump);
        Thread hard=new Thread(()->{SystemClock.sleep(3000);Process.killProcess(Process.myPid());},"tv-stop-hard-deadline");hard.setDaemon(true);hard.start();
        Thread cleanup=new Thread(()->{
            boolean joined=!nativeStarted||TProxyService.TProxyStopService();
            if(joined){if(nativeFd!=null)try{nativeFd.close();}catch(Exception ignored){}close(engine);}
            if(nativeConfig!=null)nativeConfig.delete();if(!"failed".equals(code))code="stopped";writeStatus();Process.killProcess(Process.myPid());
        },"tv-stop");cleanup.setDaemon(true);cleanup.start();super.onDestroy();
    }
    private static void close(FileDescriptor fd){if(fd.valid())try{Os.close(fd);}catch(Exception ignored){}}
}
