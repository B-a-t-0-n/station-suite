package local.station.voicebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import org.json.JSONObject;

/** Local config, display push and music remain available independently of voice/cloud health. */
public final class LocalServicesService extends Service {
    private HandlerThread thread;
    private Handler worker;
    private NativeConfigServer config;
    private LocalConfigPushServer push;
    private LocalMusicServer music;
    private volatile boolean destroyed;
    private long heartbeat,nextAttemptAt;
    private int failures;
    private String code="starting";
    private String lastFailureStage="",lastFailureType="";
    static boolean configured(Context context){
        File files=context.getFilesDir();
        return new File(files,NativeConfigStore.TEMPLATE_FILE).isFile()
                &&new File(files,"native-tls.p12").isFile()
                &&new File(files,"native-tls-password.txt").isFile()
                &&new File(files,"music-config.json").isFile();
    }
    private final Runnable tick=new Runnable(){
        @Override public void run(){
            if(destroyed)return;
            try{
                if(healthy()){code="ready";failures=0;}
                else if(SystemClock.elapsedRealtime()>=nextAttemptAt)startServices();
                writeStatus();
            }finally{if(!destroyed)worker.postDelayed(this,2000);}
        }
    };
    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel channel=new NotificationChannel("station_services","Сервисы Станции",NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(3,new Notification.Builder(this,"station_services")
                .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Сервисы Станции")
                .setContentText("Локальные настройки, управление и музыка").setOnlyAlertOnce(true).build());
        thread=new HandlerThread("StationLocalServices");thread.start();worker=new Handler(thread.getLooper());worker.post(tick);
    }
    private void startServices(){
        closeServices();char[] password=null;
        long retryDelay=failures++==0?5000:30000;
        try{
            code="validating_config";
            if(!configured(this))throw new IllegalStateException("not_provisioned");
            password=readPassword(new File(getFilesDir(),"native-tls-password.txt"));
            code="loading_local_tls";
            javax.net.ssl.SSLContext tls=NativeConfigServer.tlsFromPrivateFile(new File(getFilesDir(),"native-tls.p12").getCanonicalFile(),password);
            push=new LocalConfigPushServer(tls);final LocalConfigPushServer ownedPush=push;
            code="validating_config";
            NativeConfigStore store=new NativeConfigStore(getFilesDir(),System::currentTimeMillis,null,ownedPush::signalRefresh);
            store.sync();
            config=new NativeConfigServer(store,tls);
            music=new LocalMusicServer(getFilesDir());code="starting_listeners";
            if(destroyed)throw new IllegalStateException("stopped");
            push.start();config.start();music.start();
            // Push binds asynchronously; health is based on actual listener states.
            if(healthy()){code="ready";failures=0;}
        }catch(Exception unavailable){lastFailureStage=code;lastFailureType=unavailable.getClass().getSimpleName();closeServices();code=destroyed?"stopped":"local_services_start_failed";}
        finally{nextAttemptAt=SystemClock.elapsedRealtime()+retryDelay;if(password!=null)Arrays.fill(password,'\0');}
    }
    static char[] readPassword(File file)throws Exception{
        if(!file.isFile()||file.length()<16||file.length()>128)throw new IllegalStateException("invalid_private_material");
        byte[] bytes=new byte[129];int count=0;char[] result=null;
        try(FileInputStream input=new FileInputStream(file)){
            while(count<bytes.length){int read=input.read(bytes,count,bytes.length-count);if(read<0)break;count+=read;}
            if(count<16||count>128)throw new IllegalStateException("invalid_private_material");
            result=new char[count];
            for(int i=0;i<count;i++){int value=bytes[i]&255;if(value<33||value>126)throw new IllegalStateException("invalid_private_material");result[i]=(char)value;}
            return result;
        }catch(Exception invalid){if(result!=null)Arrays.fill(result,'\0');throw invalid;}
        finally{Arrays.fill(bytes,(byte)0);}
    }
    private boolean healthy(){
        try{return !destroyed&&config!=null&&push!=null&&music!=null&&config.status().optBoolean("active",false)
                &&push.status().optBoolean("listening",false)&&new JSONObject(music.statusJson()).optBoolean("running",false);}
        catch(Exception unavailable){return false;}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    private void writeStatus(){
        try{
            boolean ready=healthy();if(ready)code="ready";else if("ready".equals(code))code="listener_unavailable";
            JSONObject state=new JSONObject().put("healthy",ready).put("code",code).put("heartbeatSeq",++heartbeat)
                    .put("updatedAtMs",System.currentTimeMillis()).put("servicePid",android.os.Process.myPid())
                    .put("retryInMs",ready?0:Math.max(0,nextAttemptAt-SystemClock.elapsedRealtime()));
            if(!lastFailureStage.isEmpty())state.put("lastFailureStage",lastFailureStage).put("lastFailureType",lastFailureType);
            if(config!=null)state.put("config",config.status());
            if(push!=null)state.put("push",push.status());
            if(music!=null)state.put("music",new JSONObject(music.statusJson()));
            PrivateConfig.write(this,"local-services-status.json",state);
        }catch(Exception ignored){}
    }
    private void closeServices(){
        if(music!=null)try{music.close();}catch(Exception ignored){}
        if(config!=null)try{config.stop();}catch(Exception ignored){}
        if(push!=null)try{push.stop();}catch(Exception ignored){}
        music=null;config=null;push=null;
    }
    @Override public void onDestroy(){
        destroyed=true;
        if(worker!=null){worker.removeCallbacksAndMessages(null);worker.post(()->{closeServices();code="stopped";writeStatus();thread.quitSafely();});}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
