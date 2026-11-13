package local.station.voicebridge;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.Map;
import org.json.JSONObject;

/** The real Service owns lifecycle; these dependencies perform no sockets or cloud calls. */
public final class LocalServicesServiceHostTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static JSONObject state(LocalServicesService service) { return PrivateConfig.states.get(service); }
    private static LocalServicesService start() {
        LocalServicesService service = new LocalServicesService(); service.onCreate(); Handler.drain(); return service;
    }
    private static void destroy(LocalServicesService service) { service.onDestroy(); Handler.drain(); }
    public static void main(String[] args) throws Exception {
        Context context = new Context(); File directory = context.getFilesDir();
        check(!LocalServicesService.configured(context), "empty directory configured");
        LocalServicesService missing = start();
        check(!state(missing).getBoolean("healthy") && !state(missing).getString("code").equals("ready"), "missing bootstrap reported healthy");
        check(NativeConfigServer.starts == 0 && LocalConfigPushServer.starts == 0, "missing bootstrap opened listeners"); destroy(missing);
        Files.write(new File(directory, NativeConfigStore.TEMPLATE_FILE).toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "native-tls.p12").toPath(), new byte[]{1});
        Files.write(new File(directory, "native-tls-password.txt").toPath(), "synthetic-only-password".getBytes(StandardCharsets.US_ASCII));
        check(!LocalServicesService.configured(context), "missing music config considered complete");
        Files.write(new File(directory,"music-config.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        check(LocalServicesService.configured(context), "complete private files not configured");
        LocalServicesService service = start();
        check(state(service).getBoolean("healthy") && state(service).getString("code").equals("ready"), "valid bootstrap not healthy");
        check(NativeConfigServer.latest.active && LocalConfigPushServer.latest.active && LocalMusicServer.latest.running, "valid bootstrap did not start all listeners");
        check(NativeConfigStore.syncs == 1, "bootstrap not validated exactly once");
        for (char value : NativeConfigServer.lastPassword) check(value == '\0', "TLS password not wiped");
        NativeConfigStore.listener.changed(); check(LocalConfigPushServer.signals == 1, "committed display request not connected to push");
        int configStarts = NativeConfigServer.starts, pushStarts = LocalConfigPushServer.starts;
        check(service.onStartCommand(null, 0, 1) == android.app.Service.START_STICKY, "service not sticky"); Handler.drain();
        check(configStarts == NativeConfigServer.starts && pushStarts == LocalConfigPushServer.starts, "duplicate start rebound listeners");
        long sequence = state(service).getLong("heartbeatSeq"); Handler.runDelayed(); Handler.drain();
        check(state(service).getLong("heartbeatSeq") > sequence && state(service).getInt("servicePid") == 42, "heartbeat absent");
        destroy(service); sequence = state(service).getLong("heartbeatSeq");
        check(!state(service).getBoolean("healthy") && !NativeConfigServer.latest.active && !LocalConfigPushServer.latest.active, "destroy left listeners healthy/open");
        Handler.runDelayed(); Handler.drain(); check(state(service).getLong("heartbeatSeq") == sequence, "destroy left scheduled heartbeat");
        NativeConfigServer.failStart = true;
        LocalServicesService failed = start();
        check(!state(failed).getBoolean("healthy") && !LocalConfigPushServer.latest.active && !NativeConfigServer.latest.active, "partial bind failure leaked listener");
        check(state(failed).getLong("retryInMs")==5000,"initial retry not five seconds");
        android.os.SystemClock.now+=5001;Handler.runDelayed();Handler.drain();
        check(state(failed).getLong("retryInMs")==30000,"repeat retry not capped at thirty seconds");
        NativeConfigServer.failStart=false;android.os.SystemClock.now+=30001;Handler.runDelayed();Handler.drain();
        check(state(failed).getBoolean("healthy"),"listener bind failure never retried");
        LocalMusicServer.latest.running=false;android.os.SystemClock.now+=30001;Handler.runDelayed();Handler.drain();
        check(state(failed).getBoolean("healthy")&&LocalMusicServer.latest.running,"dead music listener not recovered");destroy(failed);
        NativeConfigServer.failStart = false; NativeConfigStore.failSync = true;
        configStarts = NativeConfigServer.starts; pushStarts = LocalConfigPushServer.starts;
        LocalServicesService invalid = start();
        check(!state(invalid).getBoolean("healthy") && configStarts == NativeConfigServer.starts && pushStarts == LocalConfigPushServer.starts,
                "invalid bootstrap admitted native traffic"); destroy(invalid); NativeConfigStore.failSync = false;
        Files.write(new File(directory, "native-tls-password.txt").toPath(), new byte[5]);
        LocalServicesService shortPassword = start(); check(!state(shortPassword).getBoolean("healthy"), "short private password accepted"); destroy(shortPassword);
        check(!state(shortPassword).toString().contains("synthetic-only-password"), "private material in status");
        try{LocalServicesService.readPassword(new File(directory,"native-tls-password.txt"));throw new AssertionError("short password accepted");}catch(IllegalStateException expected){assertions++;}
        try(java.io.RandomAccessFile huge=new java.io.RandomAccessFile(new File(directory,"native-tls-password.txt"),"rw")){huge.setLength(64L*1024*1024);}
        try{LocalServicesService.readPassword(new File(directory,"native-tls-password.txt"));throw new AssertionError("unbounded password accepted");}catch(IllegalStateException expected){assertions++;}
        System.out.println(assertions + " local services lifecycle assertions passed; actual Service, no sockets/API/device");
    }
}

final class PrivateConfig {
    static final Map<Context, JSONObject> states = new IdentityHashMap<Context, JSONObject>();
    static void write(Context context, String name, JSONObject value) throws Exception { states.put(context, new JSONObject(value.toString())); }
}
final class NativeConfigStore {
    static final String TEMPLATE_FILE = "native-sync.json";
    interface Clock { long nowMillis(); }
    interface PushSubscription { JSONObject get(long now) throws Exception; }
    interface CommitListener { void changed() throws Exception; }
    static int syncs; static boolean failSync; static CommitListener listener;
    NativeConfigStore(File directory, Clock clock, PushSubscription push, CommitListener commits) { listener = commits; }
    JSONObject sync() throws Exception { syncs++; if (failSync) throw new Exception("synthetic invalid template"); return new JSONObject(); }
}
final class NativeConfigServer {
    static int starts; static boolean failStart; static NativeConfigServer latest; static char[] lastPassword; boolean active;
    NativeConfigServer(NativeConfigStore store, javax.net.ssl.SSLContext tls) { latest = this; }
    static javax.net.ssl.SSLContext tlsFromPrivateFile(File path, char[] password) throws Exception { lastPassword = password; return javax.net.ssl.SSLContext.getDefault(); }
    void start() throws Exception { starts++; if (failStart) throw new Exception("synthetic bind failure"); active = true; }
    void stop() { active = false; }
    JSONObject status() throws Exception { return new JSONObject().put("active", active); }
}
final class LocalConfigPushServer {
    static int starts, signals; static LocalConfigPushServer latest; boolean active;
    LocalConfigPushServer() { latest = this; }
    LocalConfigPushServer(javax.net.ssl.SSLContext tls) { latest = this; }
    void start() { starts++; active = true; }
    void stop() { active = false; }
    void signalRefresh() { signals++; }
    JSONObject status() throws Exception { return new JSONObject().put("listening", active); }
}
final class LocalMusicServer {
    static LocalMusicServer latest;boolean running;
    LocalMusicServer(File directory){latest=this;}
    void start(){running=true;}
    void close(){running=false;}
    String statusJson(){return "{\"running\":"+running+"}";}
}
