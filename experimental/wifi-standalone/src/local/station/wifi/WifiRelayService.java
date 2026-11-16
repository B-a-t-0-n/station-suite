package local.station.wifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import hev.htproxy.TProxyService;
import local.station.relay.WifiLocalRelay;
import java.io.File;
import java.io.FileDescriptor;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Explicit best-effort split relay. WAN goes directly; root/system bypass is possible. */
public final class WifiRelayService extends VpnService {
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final AtomicLong dnsLocal=new AtomicLong(),dnsUpstream=new AtomicLong(),dnsErrors=new AtomicLong(),tcpOut=new AtomicLong(),tcpIn=new AtomicLong(),dropped=new AtomicLong();
    private final Object writeLock=new Object();
    private final FileDescriptor pump=new FileDescriptor(),engine=new FileDescriptor();
    private ParcelFileDescriptor tunnel,nativeFd;
    private WifiLocalRelay relay;
    private Thread worker,reverse;
    private volatile boolean nativeStarted;
    private volatile String code="starting";
    private volatile String failureStage="",failureType="";
    private volatile long deadline=Long.MAX_VALUE;
    private ThreadPoolExecutor dns;
    private File nativeConfig;
    private ConnectivityManager.NetworkCallback wifiRequest;
    private volatile boolean wifiAvailable;

    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel channel=new NotificationChannel("wifi_local","Локальные сервисы Wi-Fi",NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(1,new Notification.Builder(this,"wifi_local").setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Станция · Wi-Fi").setContentText("Эксперимент: защита от OTA не гарантируется").setOnlyAlertOnce(true).build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(worker!=null){
            if(intent!=null&&"start".equals(intent.getAction())&&intent.getLongExtra("durationMs",-1)==0)deadline=Long.MAX_VALUE;
            return deadline==Long.MAX_VALUE?START_STICKY:START_NOT_STICKY;
        }
        boolean recovering=intent==null&&getSharedPreferences("wifi_mode",0).getBoolean("enabled",false);
        if((!recovering&&(intent==null||!"start".equals(intent.getAction())))||VpnService.prepare(this)!=null){stopSelf();return START_NOT_STICKY;}
        long duration=recovering?0:intent.getLongExtra("durationMs",600000L);
        if(duration!=0&&(duration<1000||duration>3600000)){stopSelf();return START_NOT_STICKY;}
        deadline=duration==0?Long.MAX_VALUE:SystemClock.elapsedRealtime()+duration;
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        wifiRequest=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){wifiAvailable=true;}
            @Override public void onLost(Network network){wifiAvailable=false;}
        };
        cm.requestNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),wifiRequest);
        dns=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(8),new ThreadPoolExecutor.AbortPolicy());
        worker=new Thread(this::run,"station-wifi-local");worker.start();
        return duration==0?START_STICKY:START_NOT_STICKY;
    }
    private void run(){
        try{
            code="native_prepare";
            TProxyService.loadVerified(new File(getApplicationInfo().nativeLibraryDir,"libhev-socks5-tunnel.so").getAbsolutePath(),WifiBuild.NATIVE_SHA256);
            relay=new WifiLocalRelay(DnsPolicy.SERVICE_IP,0);
            Thread local=new Thread(()->{try{relay.run();}catch(Exception ignored){}},"wifi-loopback-relay");local.setDaemon(true);local.start();
            long wait=SystemClock.elapsedRealtime()+1500;
            while(!relay.isReady()&&local.isAlive()&&SystemClock.elapsedRealtime()<wait)SystemClock.sleep(10);
            if(!relay.isReady())throw new IllegalStateException("local_relay_unavailable");
            code="local_relay_ready";
            nativeConfig=new File(getFilesDir(),"wifi-native.yml");
            String yaml="tunnel:\n  name: station-wifi-local\n  mtu: 1500\n  ipv4: 10.111.77.2\n  icmp: 'off'\n  multi-queue: false\n  offload:\n    tso: false\n    uso: false\n    jumbo: false\n"
                    +"socks5:\n  address: 127.0.0.1\n  port: 17694\n  pipeline: false\n  udp: 'udp'\n"
                    +"misc:\n  max-session-count: 16\n  connect-timeout: 2500\n  tcp-read-write-timeout: 600000\n  udp-read-write-timeout: 1000\n  log-file: null\n  log-level: error\n  limit-nofile: 1024\n";
            Files.write(nativeConfig.toPath(),yaml.getBytes(StandardCharsets.US_ASCII));
            Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM|OsConstants.O_NONBLOCK|OsConstants.O_CLOEXEC,0,pump,engine);
            nativeFd=ParcelFileDescriptor.dup(engine);
            nativeStarted=TProxyService.TProxyStartService(nativeConfig.getAbsolutePath(),nativeFd.getFd());
            SystemClock.sleep(150);
            if(!nativeStarted||!TProxyService.TProxyIsRunning())throw new IllegalStateException("native_unavailable");
            code="native_running";
            Builder builder=new Builder().setSession("Station local services (experimental)")
                    .addAddress(DnsPolicy.TUN_IP,32).addRoute(DnsPolicy.SERVICE_IP,32)
                    .addRoute(DnsPolicy.DNS_IP,32).addDnsServer(DnsPolicy.DNS_IP)
                    .setMtu(1500).setBlocking(false).allowFamily(OsConstants.AF_INET6);
            for(String name:new String[]{getPackageName(),"com.android.shell","local.station.agent","local.station.voicebridge"})builder.addDisallowedApplication(name);
            if(stopped.get())return;
            code="establishing";
            tunnel=builder.establish();if(tunnel==null)throw new IllegalStateException("vpn_not_established");
            LocalPacketPolicy policy=new LocalPacketPolicy();
            reverse=new Thread(()->reverse(policy),"wifi-local-return");reverse.start();
            code="active";
            long statusAt=0;
            byte[] packet=new byte[65535];
            while(active()){
                if(!relay.isReady()||!TProxyService.TProxyIsRunning())throw new IllegalStateException("local_worker_unavailable");
                long now=SystemClock.elapsedRealtime();if(now-statusAt>=2000){writeStatus();statusAt=now;}
                int length=read(tunnel.getFileDescriptor(),packet);
                if(length==0)continue;
                DnsPolicy.Query query=DnsPolicy.parsePacket(packet,length);
                if(query!=null){
                    byte[] response=DnsPolicy.localResponse(query);
                    if(response!=null){writeTun(response,response.length);dnsLocal.incrementAndGet();}
                    else try{dns.execute(()->forwardDns(query));}
                    catch(java.util.concurrent.RejectedExecutionException busy){dnsErrors.incrementAndGet();dnsFailure(query);}
                }else if(policy.outbound(packet,length,now)){write(pump,packet,length);tcpOut.incrementAndGet();}
                else dropped.incrementAndGet();
            }
            code="deadline";
        }catch(Exception failure){failureStage=code;failureType=failure.getClass().getSimpleName();code="failed";}
        finally{writeStatus();stopSelf();}
    }
    private void reverse(LocalPacketPolicy policy){
        byte[] packet=new byte[65535];
        try{
            while(active()){
                int length=read(pump,packet);if(length==0)continue;
                if(!policy.inbound(packet,length,SystemClock.elapsedRealtime())){dropped.incrementAndGet();continue;}
                writeTun(packet,length);tcpIn.incrementAndGet();
            }
        }catch(Exception failure){if(active()){code="return_path_failed";stopSelf();}}
    }
    private void forwardDns(DnsPolicy.Query query){
        try{
            ConnectivityManager cm=getSystemService(ConnectivityManager.class);
            Network selected=null;LinkProperties selectedLink=null;
            for(Network network:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);LinkProperties link=cm.getLinkProperties(network);
                if(caps==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||link==null||link.getDnsServers().isEmpty())continue;
                if(selected==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){selected=network;selectedLink=link;}
                if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))break;
            }
            if(selected==null)throw new IllegalStateException("no_physical_dns");
            try(DatagramSocket socket=new DatagramSocket()){
                if(!protect(socket))throw new IllegalStateException("protect_failed");
                selected.bindSocket(socket);socket.setSoTimeout(1800);
                InetAddress server=selectedLink.getDnsServers().get(0);
                socket.connect(server,53);socket.send(new DatagramPacket(query.dnsBytes,query.dnsBytes.length));
                byte[] data=new byte[4096];DatagramPacket response=new DatagramPacket(data,data.length);socket.receive(response);
                byte[] actual=java.util.Arrays.copyOf(data,response.getLength());
                byte[] packet=DnsPolicy.reply(query,actual);
                if(packet==null)throw new IllegalStateException("invalid_dns_reply");
                writeTun(packet,packet.length);dnsUpstream.incrementAndGet();
            }
        }catch(Exception failure){dnsErrors.incrementAndGet();dnsFailure(query);}
    }
    private void dnsFailure(DnsPolicy.Query query){
        try{
            byte[] response=query.dnsBytes.clone();
            response[2]=(byte)0x81;response[3]=(byte)0x82;
            byte[] packet=DnsPolicy.reply(query,response);
            if(packet!=null)writeTun(packet,packet.length);
        }catch(Exception ignored){}
    }
    private boolean active(){return !stopped.get()&&SystemClock.elapsedRealtime()<deadline;}
    private int read(FileDescriptor fd,byte[] buffer)throws Exception{
        StructPollfd poll=new StructPollfd();poll.fd=fd;poll.events=(short)OsConstants.POLLIN;
        if(Os.poll(new StructPollfd[]{poll},100)==0)return 0;
        if((poll.revents&(OsConstants.POLLERR|OsConstants.POLLHUP|OsConstants.POLLNVAL))!=0)throw new IllegalStateException("closed_descriptor");
        try{return Os.read(fd,buffer,0,buffer.length);}catch(ErrnoException retry){if(retry.errno==OsConstants.EAGAIN||retry.errno==OsConstants.EINTR)return 0;throw retry;}
    }
    private void write(FileDescriptor fd,byte[] data,int length)throws Exception{
        long until=SystemClock.elapsedRealtime()+300;
        while(active()&&SystemClock.elapsedRealtime()<until){
            try{if(Os.write(fd,data,0,length)!=length)throw new IllegalStateException("partial_packet");return;}
            catch(ErrnoException retry){if(retry.errno!=OsConstants.EAGAIN&&retry.errno!=OsConstants.EINTR)throw retry;SystemClock.sleep(5);}
        }
        throw new IllegalStateException("write_deadline");
    }
    private void writeTun(byte[] data,int length)throws Exception{synchronized(writeLock){if(tunnel!=null&&active())write(tunnel.getFileDescriptor(),data,length);}}
    private void writeStatus(){
        try{
            JSONObject status=new JSONObject().put("code",code).put("failureStage",failureStage).put("failureType",failureType).put("active",tunnel!=null&&active()&&"active".equals(code))
                    .put("updatedAtMs",System.currentTimeMillis()).put("remainingMs",deadline==Long.MAX_VALUE?-1:Math.max(0,deadline-SystemClock.elapsedRealtime()))
                    .put("dnsLocal",dnsLocal.get()).put("dnsUpstream",dnsUpstream.get()).put("dnsErrors",dnsErrors.get())
                    .put("wifiAvailable",wifiAvailable)
                    .put("tcpOut",tcpOut.get()).put("tcpIn",tcpIn.get()).put("dropped",dropped.get()).put("otaProtectionGuaranteed",false);
            File temp=new File(getFilesDir(),"wifi-status.tmp");Files.write(temp.toPath(),status.toString().getBytes(StandardCharsets.UTF_8));
            temp.renameTo(new File(getFilesDir(),"wifi-status.json"));
        }catch(Exception ignored){}
    }
    @Override public void onRevoke(){getSharedPreferences("wifi_mode",0).edit().putBoolean("enabled",false).commit();stopSelf();}
    @Override public void onDestroy(){
        if(!stopped.compareAndSet(false,true))return;
        if(tunnel!=null)try{tunnel.close();}catch(Exception ignored){}
        if(dns!=null)dns.shutdownNow();if(relay!=null)relay.close();close(pump);
        if(wifiRequest!=null)try{getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiRequest);}catch(Exception ignored){}
        Thread hardStop=new Thread(()->{
            SystemClock.sleep(3000);
            android.os.Process.killProcess(android.os.Process.myPid());
        },"wifi-native-stop-deadline");hardStop.setDaemon(true);hardStop.start();
        new Thread(()->{
            boolean joined=!nativeStarted||TProxyService.TProxyStopService();
            if(joined){if(nativeFd!=null)try{nativeFd.close();}catch(Exception ignored){}close(engine);}
            if(nativeConfig!=null)nativeConfig.delete();if(!"failed".equals(code))code="stopped";writeStatus();
            // Dedicated process; native descriptors cannot survive a failed join or be reused.
            android.os.Process.killProcess(android.os.Process.myPid());
        },"wifi-native-stop").start();
        super.onDestroy();
    }
    private static void close(FileDescriptor fd){if(fd.valid())try{Os.close(fd);}catch(Exception ignored){}}
}
