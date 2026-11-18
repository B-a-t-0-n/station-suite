package local.station.wifi;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Process;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Fixed loopback/proc diagnostic in the actual APK UID. No VPN, Wi-Fi or settings mutations. */
final class SocketOwnerProbe {
    private static final int SERVER_PORT=24111,CLIENT_PORT=24110,EXPECTED_CLIENT_UID=10005;
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private final Activity activity;
    private final String nonce;
    private final boolean ownsRunning;
    private final AtomicBoolean completed=new AtomicBoolean();
    private volatile ServerSocket listener;
    private volatile Socket client;
    private SocketOwnerProbe(Activity activity,String nonce,boolean ownsRunning){this.activity=activity;this.nonce=nonce;this.ownsRunning=ownsRunning;}

    static void start(Activity activity,String nonce){
        // A caller must match the nonce in the result, including failures; no stale success is retained.
        try{Files.deleteIfExists(new File(activity.getFilesDir(),"socket-owner-probe.json").toPath());}catch(Exception ignored){}
        if(nonce==null||!nonce.matches("[a-f0-9]{32}")){
            new SocketOwnerProbe(activity,"",false).finish("invalid_nonce",null);return;
        }
        if(!RUNNING.compareAndSet(false,true)){
            new SocketOwnerProbe(activity,nonce,false).finish("probe_busy",null);return;
        }
        SocketOwnerProbe probe=new SocketOwnerProbe(activity,nonce,true);
        Thread watchdog=new Thread(()->{
            try{Thread.sleep(3800);}catch(InterruptedException stop){Thread.currentThread().interrupt();}
            probe.finish("deadline",null);
        },"socket-owner-probe-deadline");watchdog.setDaemon(true);watchdog.start();
        Thread worker=new Thread(probe::run,"socket-owner-probe");worker.setDaemon(true);worker.start();
    }
    private void run(){
        try{
            WifiManager wifi=activity.getApplicationContext().getSystemService(WifiManager.class);
            ConnectivityManager cm=activity.getSystemService(ConnectivityManager.class);
            if(wifi==null||wifi.getWifiState()!=WifiManager.WIFI_STATE_DISABLED){finish("wifi_must_be_off",null);return;}
            if(cm==null){finish("network_state_unavailable",null);return;}
            Network[] networks=cm.getAllNetworks();
            if(networks==null){finish("network_state_unavailable",null);return;}
            for(Network network:networks){
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){finish("vpn_must_be_off",null);return;}
            }
            if(completed.get())return;
            listener=new ServerSocket();listener.setReuseAddress(false);
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),SERVER_PORT),1);
            if(completed.get())return;
            writeResult("listening",null,false);
            listener.setSoTimeout(2500);client=listener.accept();
            if(completed.get())return;
            if(!"127.0.0.1".equals(client.getInetAddress().getHostAddress())||client.getPort()!=CLIENT_PORT){finish("unexpected_client_tuple",null);return;}
            byte[] loopback={127,0,0,1};
            ProcSocketOwner.Result result=ProcSocketOwner.fromProc(
                    new ProcSocketOwner.Tuple(loopback,CLIENT_PORT,loopback,SERVER_PORT),
                    new ProcSocketOwner.Tuple(loopback,SERVER_PORT,loopback,CLIENT_PORT));
            finish("observed",result);
        }catch(Exception failure){finish("probe_failed_"+failure.getClass().getSimpleName(),null);}
        finally{close();}
    }
    private void finish(String status,ProcSocketOwner.Result result){
        if(!completed.compareAndSet(false,true))return;
        close();
        writeResult(status,result,true);
        if(ownsRunning)RUNNING.set(false);
        activity.runOnUiThread(activity::finish);
    }
    private synchronized void writeResult(String status,ProcSocketOwner.Result result,boolean finalResult){
        if(!finalResult&&completed.get())return;
        try{
            JSONObject value=new JSONObject().put("nonce",nonce).put("status",status)
                    .put("ownUid",Process.myUid()).put("expectedClientUid",EXPECTED_CLIENT_UID)
                    .put("clientPort",CLIENT_PORT).put("serverPort",SERVER_PORT)
                    .put("loopbackAddress","127.0.0.1");
            if(result!=null){
                value.put("tcpReadable",result.tcpReadable).put("tcp6Readable",result.tcp6Readable)
                        .put("lookupComplete",result.complete())
                        .put("clientTupleFound",result.owners[0].found()).put("clientUidAmbiguous",result.owners[0].ambiguous())
                        .put("clientUid",result.owners[0].uid())
                        .put("clientUidMatchesExpected",result.complete()&&result.owners[0].uid()==EXPECTED_CLIENT_UID)
                        .put("serverTupleFound",result.owners[1].found()).put("serverUidAmbiguous",result.owners[1].ambiguous())
                        .put("serverUid",result.owners[1].uid())
                        .put("serverUidMatchesOwn",result.complete()&&result.owners[1].uid()==Process.myUid());
            }
            File temporary=new File(activity.getFilesDir(),"socket-owner-probe.tmp");
            Files.write(temporary.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(),new File(activity.getFilesDir(),"socket-owner-probe.json").toPath(),StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){}
    }
    private void close(){
        Socket socket=client;if(socket!=null)try{socket.close();}catch(Exception ignored){}
        ServerSocket server=listener;if(server!=null)try{server.close();}catch(Exception ignored){}
    }
}
