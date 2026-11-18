package local.station.wifi;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Disposable single-/32 SYN capture. Never forwards, resolves DNS or changes Wi-Fi. */
public final class SynOwnerProbeService extends VpnService {
    private static final String ACTION="syn_owner_probe",RESULT="syn-owner-probe.json";
    private final AtomicBoolean completed=new AtomicBoolean();
    private final Object tunnelLock=new Object();
    private String nonce="";
    private volatile ParcelFileDescriptor tunnel;
    private volatile boolean tunnelEstablished,tunnelClosed=true;
    private volatile int selectedSynPackets;
    private volatile long deadline;
    private Thread worker,watchdog;

    static void request(Activity activity,String nonce){
        try{
            Files.deleteIfExists(new File(activity.getFilesDir(),RESULT).toPath());
            if(!SynOwnerProbeSpec.validNonce(nonce)){write(activity,"","invalid_nonce",false,true,0,null);return;}
            // prepare() checks existing consent; this branch never opens its returned dialog.
            if(VpnService.prepare(activity)!=null){write(activity,nonce,"consent_required",false,true,0,null);return;}
            String guard=guard(activity);
            if(guard!=null){write(activity,nonce,guard,false,true,0,null);return;}
            write(activity,nonce,"requested",false,true,0,null);
            activity.startForegroundService(new Intent(activity,SynOwnerProbeService.class).setAction(ACTION).putExtra("nonce",nonce));
        }catch(Exception failure){write(activity,SynOwnerProbeSpec.validNonce(nonce)?nonce:"","request_failed",false,true,0,null);}
        finally{activity.finish();}
    }
    private static String guard(Context context){
        WifiManager wifi=context.getApplicationContext().getSystemService(WifiManager.class);
        if(wifi==null||wifi.getWifiState()!=WifiManager.WIFI_STATE_DISABLED)return "wifi_must_be_off";
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
        if(cm==null)return "network_state_unavailable";
        Network[] networks=cm.getAllNetworks();if(networks==null)return "network_state_unavailable";
        for(Network network:networks){
            NetworkCapabilities caps=cm.getNetworkCapabilities(network);
            if(caps==null)return "network_state_unavailable";
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "vpn_must_be_off";
        }
        return null;
    }
    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel channel=new NotificationChannel("syn_owner_probe","Проверка маршрута",NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(2,new Notification.Builder(this,"syn_owner_probe").setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Станция · Проверка маршрута").setOnlyAlertOnce(true).build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(worker!=null){finish("duplicate_request",null);return START_NOT_STICKY;}
        if(intent==null||!ACTION.equals(intent.getAction())||!SynOwnerProbeSpec.validNonce(intent.getStringExtra("nonce"))){finish("invalid_request",null);return START_NOT_STICKY;}
        nonce=intent.getStringExtra("nonce");
        if(VpnService.prepare(this)!=null){finish("consent_required",null);return START_NOT_STICKY;}
        String reason=guard(this);if(reason!=null){finish(reason,null);return START_NOT_STICKY;}
        deadline=SystemClock.elapsedRealtime()+SynOwnerProbeSpec.CAPTURE_MS;
        watchdog=new Thread(()->{
            SystemClock.sleep(SynOwnerProbeSpec.HARD_MS);
            if(completed.compareAndSet(false,true)){
                // Dedicated :synprobe process only. This cannot kill Agent, Voicebridge or TV apps.
                closeTunnel();
                Thread report=new Thread(()->{publish("hard_deadline",null,true);stopSelf();},"syn-owner-deadline-result");
                report.setDaemon(true);report.start();
                SystemClock.sleep(100);
                android.os.Process.killProcess(android.os.Process.myPid());
            }else if(!tunnelClosed)android.os.Process.killProcess(android.os.Process.myPid());
        },"syn-owner-hard-close");watchdog.setDaemon(true);watchdog.start();
        worker=new Thread(this::run,"syn-owner-capture");worker.setDaemon(true);worker.start();
        return START_NOT_STICKY;
    }
    private void run(){
        try{
            if(completed.get())return;
            Builder builder=new Builder().setSession("Station bounded SYN owner probe")
                    .addAddress(SynOwnerProbeSpec.SOURCE,32).addRoute(SynOwnerProbeSpec.DESTINATION,32)
                    .setMtu(1500).setBlocking(false).allowFamily(OsConstants.AF_INET6);
            for(String name:new String[]{getPackageName(),"com.android.shell","local.station.agent","local.station.voicebridge"})builder.addDisallowedApplication(name);
            if(completed.get()||SystemClock.elapsedRealtime()>=deadline-1000){finish("startup_deadline",null);return;}
            ParcelFileDescriptor fresh=builder.establish();
            if(fresh==null){finish("vpn_not_established",null);return;}
            synchronized(tunnelLock){
                if(completed.get()){fresh.close();return;}
                tunnel=fresh;tunnelEstablished=true;tunnelClosed=false;
            }
            publish("capturing",null,false);
            byte[] packet=new byte[1500];
            while(!completed.get()&&SystemClock.elapsedRealtime()<deadline){
                ParcelFileDescriptor current=tunnel;if(current==null)break;
                StructPollfd poll=new StructPollfd();poll.fd=current.getFileDescriptor();poll.events=(short)OsConstants.POLLIN;
                if(Os.poll(new StructPollfd[]{poll},100)==0)continue;
                if((poll.revents&(OsConstants.POLLERR|OsConstants.POLLHUP|OsConstants.POLLNVAL))!=0)throw new IllegalStateException("closed_tun");
                int length;
                try{length=Os.read(current.getFileDescriptor(),packet,0,packet.length);}
                catch(ErrnoException retry){if(retry.errno==OsConstants.EAGAIN||retry.errno==OsConstants.EINTR)continue;throw retry;}
                if(!SynOwnerProbeSpec.matchesInitialSyn(packet,length))continue;
                selectedSynPackets++;
                ProcSocketOwner.Result result=ProcSocketOwner.fromProc(ProcSocketOwner.TcpState.SYN_SENT,SynOwnerProbeSpec.tuple());
                finish("syn_observed",result);return;
            }
            finish("no_selected_syn",null);
        }catch(Exception failure){finish("probe_failed_"+failure.getClass().getSimpleName(),null);}
    }
    private void finish(String status,ProcSocketOwner.Result result){
        if(!completed.compareAndSet(false,true))return;
        closeTunnel();publish(status,result,true);stopSelf();
    }
    private synchronized void publish(String status,ProcSocketOwner.Result result,boolean finalResult){
        if(!finalResult&&completed.get())return;
        write(this,nonce,status,tunnelEstablished,tunnelClosed,selectedSynPackets,result);
    }
    private void closeTunnel(){
        synchronized(tunnelLock){
            ParcelFileDescriptor current=tunnel;tunnel=null;
            if(current!=null)try{current.close();tunnelClosed=true;}catch(Exception failure){tunnelClosed=false;}
        }
    }
    private static void write(Context context,String nonce,String status,boolean established,boolean closed,int count,ProcSocketOwner.Result result){
        try{
            JSONObject value=new JSONObject().put("nonce",nonce).put("status",status).put("ownUid",android.os.Process.myUid())
                    .put("expectedClientUid",SynOwnerProbeSpec.EXPECTED_UID).put("expectedState","02")
                    .put("source",SynOwnerProbeSpec.SOURCE).put("sourcePort",SynOwnerProbeSpec.SOURCE_PORT)
                    .put("destination",SynOwnerProbeSpec.DESTINATION).put("destinationPort",SynOwnerProbeSpec.DESTINATION_PORT)
                    .put("selectedSynPackets",count).put("tunnelEstablished",established).put("tunnelClosed",closed)
                    .put("forwardedPackets",0).put("hardDeadlineMs",SynOwnerProbeSpec.HARD_MS).put("probePassed",false);
            if(result!=null){
                boolean match=result.complete()&&!result.owners[0].ambiguous()&&result.owners[0].uid()==SynOwnerProbeSpec.EXPECTED_UID;
                value.put("tcpReadable",result.tcpReadable).put("tcp6Readable",result.tcp6Readable)
                        .put("lookupComplete",result.complete()).put("clientTupleFound",result.owners[0].found())
                        .put("clientUid",result.owners[0].uid()).put("clientUidAmbiguous",result.owners[0].ambiguous())
                        .put("clientUidMatchesExpected",match).put("clientUidMatchesOwn",result.owners[0].uid()==android.os.Process.myUid())
                        .put("probePassed","syn_observed".equals(status)&&established&&closed&&count>0&&match);
            }
            File temporary=new File(context.getFilesDir(),"syn-owner-probe.tmp");
            Files.write(temporary.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(),new File(context.getFilesDir(),RESULT).toPath(),StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){}
    }
    @Override public void onRevoke(){finish("revoked",null);}
    @Override public void onDestroy(){finish("destroyed",null);closeTunnel();stopForeground(true);super.onDestroy();}
}
