package local.station.voicebridge;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Actual Service/settings/receivers, deterministic dependencies, no network or disk configuration. */
public final class VoiceRuntimeServiceHostTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static VoiceRuntimeService start(boolean enabled) {
        VoiceRuntimeService service = new VoiceRuntimeService(); RuntimeSettings.setEnabled(service, enabled);
        service.onCreate(); service.onStartCommand(new Intent(), 0, 1); Handler.drain(); return service;
    }
    private static void heartbeat(long advance) { SystemClock.advance(advance); Handler.runDelayed(); Handler.drain(); }
    private static void destroy(VoiceRuntimeService service) { service.onDestroy(); Handler.drain(); }
    private static JSONObject status(VoiceRuntimeService service) { return PrivateConfig.last(service); }

    private static void disabledAndReceivers() {
        int instances = RuntimeVoiceHandler.instances, starts = LoopbackVoiceServer.starts;
        VoiceRuntimeService service = start(false);
        check(service.stopCalls > 0, "disabled service did not stop");
        check(RuntimeVoiceHandler.instances == instances && LoopbackVoiceServer.starts == starts,
                "disabled service opened a listener");
        check(!status(service).optBoolean("enabled") && !status(service).optBoolean("listenerReady"), "disabled health wrong");
        Context context = new Context(); int launched = Context.launched;
        new BootReceiver().onReceive(context, new Intent().setAction(Intent.ACTION_BOOT_COMPLETED));
        new RuntimeReceiver().onReceive(context, new Intent().putExtra("operation", "runtime_start"));
        check(Context.launched == launched, "disabled choice was ignored by boot/start receiver");
        new RuntimeReceiver().onReceive(context, new Intent().putExtra("operation", "runtime_enable"));
        check(RuntimeSettings.enabled(context) && Context.launched == launched + 1, "enable was not persisted/launched");
        new BootReceiver().onReceive(context, new Intent().setAction("unrelated"));
        check(Context.launched == launched + 1, "unrelated boot action started service");
        new BootReceiver().onReceive(context, new Intent().setAction(Intent.ACTION_MY_PACKAGE_REPLACED));
        check(Context.launched == launched + 2, "enabled upgrade did not start service");
        new RuntimeReceiver().onReceive(context, new Intent().putExtra("operation", "runtime_disable"));
        check(!RuntimeSettings.enabled(context) && Context.launched == launched + 3, "disable was not persisted/delivered");
        destroy(service);
    }
    private static void enabledAndDeadListenerRecovery() {
        VoiceRuntimeService service = start(true);
        check(status(service).optBoolean("enabled") && status(service).optBoolean("healthy"), "enabled runtime not healthy");
        check(status(service).optBoolean("listenerReady"), "enabled listener absent");
        int count = LoopbackVoiceServer.starts; LoopbackVoiceServer.latest.active = false;
        heartbeat(1); check(LoopbackVoiceServer.starts == count, "listener restart ignored cooldown");
        heartbeat(2500); check(LoopbackVoiceServer.starts == count + 1, "dead listener not restarted");
        check(status(service).optBoolean("healthy"), "restarted listener health false");
        LoopbackVoiceServer.latest.active = false; LoopbackVoiceServer.failNext = true;
        heartbeat(2500); count = LoopbackVoiceServer.starts;
        check(!status(service).optBoolean("healthy"), "bind failure reported healthy");
        heartbeat(1000); check(LoopbackVoiceServer.starts == count, "bind failure retried before backoff");
        heartbeat(4000); check(LoopbackVoiceServer.starts == count + 1 && status(service).optBoolean("healthy"),
                "bind failure did not recover");
        destroy(service);
        check(RuntimeSettings.enabled(service), "onDestroy erased enabled choice");
        check(!status(service).optBoolean("healthy") && !status(service).optBoolean("listenerReady"), "destroyed health wrong");
    }
    private static void disableDuringCleanup() {
        VoiceRuntimeService service = start(true); RuntimeVoiceHandler handler = RuntimeVoiceHandler.latest;
        handler.begin(); RuntimeSettings.setEnabled(service, false); service.onStartCommand(new Intent(), 0, 2); Handler.drain();
        check(handler.closed && !LoopbackVoiceServer.latest.active, "disable left admission open");
        check(service.stopCalls == 0 && VoiceCloudGate.isBusy(), "disable released cleanup too early");
        check(!status(service).optBoolean("enabled") && status(service).optBoolean("cloudBusy"), "cleanup health wrong");
        heartbeat(2000); check(service.stopCalls == 0, "heartbeat stopped in-flight worker");
        handler.finish(); Handler.drain(); heartbeat(2000);
        check(service.stopCalls > 0 && !VoiceCloudGate.isBusy(), "finished cleanup did not release disabled service");
        destroy(service); check(!RuntimeSettings.enabled(service), "disabled preference lost");
    }
    private static void recreatedServiceCannotOverlapCleanup() {
        VoiceRuntimeService first = start(true); RuntimeVoiceHandler old = RuntimeVoiceHandler.latest; old.begin();
        int instances = RuntimeVoiceHandler.instances; destroy(first);
        check(RuntimeSettings.enabled(first) && old.closed && VoiceCloudGate.isBusy(), "destroy cancelled choice/cleanup");
        VoiceRuntimeService second = new VoiceRuntimeService(); second.onCreate(); second.onStartCommand(null, 0, 1); Handler.drain();
        check(RuntimeVoiceHandler.instances == instances, "recreation overlapped previous owner");
        check(!status(second).optBoolean("listenerReady"), "blocked recreation listener was ready");
        old.finish(); Handler.drain(); heartbeat(2000);
        check(RuntimeVoiceHandler.instances == instances + 1 && status(second).optBoolean("healthy"),
                "recreation did not acquire cleaned owner");
        destroy(second); check(!VoiceCloudGate.isBusy(), "recreated service gate leak");
    }
    private static void probeOwnershipBlocksRuntimeStartup() {
        ProbeService.busy = true; int instances = RuntimeVoiceHandler.instances;
        VoiceRuntimeService service = start(true);
        check(RuntimeVoiceHandler.instances == instances && !status(service).optBoolean("healthy"), "probe owner not respected");
        ProbeService.busy = false; heartbeat(2000);
        check(RuntimeVoiceHandler.instances == instances + 1 && status(service).optBoolean("healthy"), "probe cleanup not recovered");
        destroy(service);
    }
    private static void reenableWhileOldWorkerCleansUp() {
        VoiceRuntimeService service = start(true); RuntimeVoiceHandler old = RuntimeVoiceHandler.latest; old.begin();
        int instances = RuntimeVoiceHandler.instances;
        RuntimeSettings.setEnabled(service, false); service.onStartCommand(new Intent(), 0, 2); Handler.drain();
        RuntimeSettings.setEnabled(service, true);
        check(service.onStartCommand(new Intent(), 0, 3) == android.app.Service.START_STICKY, "reenable was not sticky");
        Handler.drain(); heartbeat(2000);
        check(RuntimeVoiceHandler.instances == instances && service.stopCalls == 0 && VoiceCloudGate.isBusy(),
                "reenable overlapped or stopped old cleanup");
        old.finish(); Handler.drain(); heartbeat(2500);
        check(RuntimeVoiceHandler.instances == instances + 1 && RuntimeVoiceHandler.latest != old,
                "reenable reused closed handler or failed to reopen");
        check(status(service).optBoolean("enabled") && status(service).optBoolean("healthy") && service.stopCalls == 0,
                "reenabled runtime did not become healthy");
        destroy(service);
    }
    private static void heartbeatSequenceSurvivesSameProcessRecreation() {
        VoiceRuntimeService first = start(true); destroy(first);
        long sequence = status(first).optLong("heartbeatSeq"); int pid = status(first).optInt("servicePid");
        VoiceRuntimeService second = start(true);
        check(status(second).optInt("servicePid") == pid, "host PID changed unexpectedly");
        check(status(second).optLong("heartbeatSeq") > sequence, "same-PID heartbeat sequence regressed");
        destroy(second);
    }
    private static void localModelCleanupBlocksServiceRecreation() throws Exception {
        PrivateConfig.config.put("voice_local_asr",true);
        try {
            VoiceRuntimeService first=start(true);
            LocalAsrRuntime old=LocalAsrRuntime.latest;
            check(old!=null&&status(first).has("localAsr"),"local ASR owner missing from service");
            old.cleaning=true;
            int instances=LocalAsrRuntime.instances,handlers=RuntimeVoiceHandler.instances;
            destroy(first);
            check(old.closed&&!old.canRelease(),"destroy released local model during native cleanup");
            VoiceRuntimeService second=new VoiceRuntimeService();second.onCreate();
            second.onStartCommand(null,0,1);Handler.drain();
            check(LocalAsrRuntime.instances==instances&&RuntimeVoiceHandler.instances==handlers,
                    "service recreation overlapped old local model cleanup");
            check(!status(second).optBoolean("listenerReady"),"recreated listener ignored old model owner");
            old.finish();Handler.drain();heartbeat(2500);
            check(LocalAsrRuntime.instances==instances+1&&RuntimeVoiceHandler.instances==handlers+1,
                    "recreated service did not acquire freed model owner");
            check(status(second).optBoolean("healthy"),"recreated local service not healthy");
            destroy(second);
        } finally {PrivateConfig.config=new JSONObject();}
    }
    private static void localServicesIndependentFromVoiceChoice() {
        Context context = new Context(); RuntimeSettings.setEnabled(context, false);
        int launched = Context.launched, stopped = Context.stopped;
        LocalServicesService.provisioned = true;
        try {
            new BootReceiver().onReceive(context, new Intent().setAction(Intent.ACTION_BOOT_COMPLETED));
            check(Context.launched == ++launched && Context.lastStarted == LocalServicesService.class,
                    "voice disabled prevented provisioned local config boot");
            new BootReceiver().onReceive(context, new Intent().setAction(Intent.ACTION_MY_PACKAGE_REPLACED));
            check(Context.launched == ++launched && Context.lastStarted == LocalServicesService.class,
                    "local config upgrade restart depended on voice");
            new RuntimeReceiver().onReceive(context, new Intent().putExtra("operation", "runtime_start"));
            check(Context.launched == launched, "disabled voice unexpectedly started");
            new RuntimeReceiver().onReceive(context, new Intent().putExtra("operation", "local_services_start"));
            check(Context.launched == ++launched && Context.lastStarted == LocalServicesService.class,
                    "explicit local config start depended on voice");
            new RuntimeReceiver().onReceive(context, new Intent().putExtra("operation", "runtime_enable"));
            check(Context.launched == ++launched && Context.lastStarted == VoiceRuntimeService.class,
                    "voice enable launched wrong service");
            new RuntimeReceiver().onReceive(context, new Intent().putExtra("operation", "runtime_disable"));
            check(Context.launched == ++launched && Context.lastStarted == VoiceRuntimeService.class && Context.stopped == stopped,
                    "voice disable stopped independent local config");
            new RuntimeReceiver().onReceive(context, new Intent().putExtra("operation", "local_services_stop"));
            check(Context.stopped == stopped + 1 && Context.lastStopped == LocalServicesService.class,
                    "explicit config stop targeted wrong service");
            LocalServicesService.provisioned = false;
            new RuntimeReceiver().onReceive(context, new Intent().putExtra("operation", "local_services_start"));
            check(Context.launched == launched, "unprovisioned local config launched");
        } finally { LocalServicesService.provisioned = false; RuntimeSettings.setEnabled(context, false); }
    }
    private static void bootDeliveryTraceAndIndependentStartFailure() throws Exception {
        Context context = new Context(); BootReceiver receiver = new BootReceiver();
        RuntimeSettings.setEnabled(context, false); LocalServicesService.provisioned = false;
        int launched = Context.launched;
        receiver.onReceive(context, new Intent().setAction(Intent.ACTION_BOOT_COMPLETED));
        JSONObject first = PrivateConfig.lastBoot(context);
        check(first.getString("stage").equals("done") && first.getString("action").equals("boot_completed"), "boot delivery stages missing");
        check(first.getLong("deliveries") == 1 && first.getLong("bootDeliveries") == 1 && first.getLong("upgradeDeliveries") == 0,
                "boot delivery counters wrong");
        check(first.getBoolean("configuredKnown") && !first.getBoolean("configured") && first.getBoolean("runtimeEnabledKnown") && !first.getBoolean("runtimeEnabled"),
                "boot decision not observed");
        check(!first.getBoolean("localStartRequested") && !first.getBoolean("voiceStartRequested") && Context.launched == launched,
                "disabled unprovisioned boot opened services");
        check(first.getString("localCode").equals("not_provisioned") && first.getString("voiceCode").equals("disabled"), "fixed skipped reasons absent");
        String snapshot = first.toString();
        receiver.onReceive(context, null); receiver.onReceive(context, new Intent().setAction("untrusted_action"));
        check(PrivateConfig.lastBoot(context).toString().equals(snapshot), "unrecognized broadcast persisted raw action");
        RuntimeSettings.setEnabled(context, true); LocalServicesService.provisioned = true; Context.failStartTarget = LocalServicesService.class;
        try {
            receiver.onReceive(context, new Intent().setAction(Intent.ACTION_MY_PACKAGE_REPLACED).putExtra("secret", "RAW_PRIVATE_INTENT_EXTRA"));
            JSONObject next = PrivateConfig.lastBoot(context);
            check(next.getLong("deliveries") == 2 && next.getLong("bootDeliveries") == 1 && next.getLong("upgradeDeliveries") == 1,
                    "upgrade counters did not preserve prior boot");
            check(next.getString("localCode").equals("start_denied") && !next.getBoolean("localStartRequested"), "denied start was called requested");
            check(next.getBoolean("voiceStartRequested") && next.getString("voiceCode").equals("requested") && Context.lastStarted == VoiceRuntimeService.class,
                    "local service failure suppressed voice admission");
            check(next.getJSONArray("stages").length() == 7 && next.getLong("updatedAtElapsedMs") >= next.getLong("receivedAtElapsedMs"),
                    "bounded monotonic stage trace missing");
            check(!next.toString().contains("RAW_PRIVATE") && !next.toString().contains("SecurityException"), "boot diagnostics leaked messages or extras");
        } finally { Context.failStartTarget = null; }
        LocalServicesService.failCheck = true;
        try {
            receiver.onReceive(context, new Intent().setAction(Intent.ACTION_BOOT_COMPLETED));
            JSONObject next = PrivateConfig.lastBoot(context);
            check(!next.getBoolean("configuredKnown") && next.getString("localCode").equals("configuration_check_failed"),
                    "failed provisioning check fabricated known state");
            check(next.getBoolean("runtimeEnabled") && next.getBoolean("voiceStartRequested"), "provisioning check failure suppressed independent voice");
        } finally { LocalServicesService.failCheck = false; LocalServicesService.provisioned = false; RuntimeSettings.setEnabled(context, false); }
    }
    public static void main(String[] args) throws Exception {
        disabledAndReceivers(); enabledAndDeadListenerRecovery(); disableDuringCleanup();
        recreatedServiceCannotOverlapCleanup(); probeOwnershipBlocksRuntimeStartup();
        reenableWhileOldWorkerCleansUp(); heartbeatSequenceSurvivesSameProcessRecreation();
        localModelCleanupBlocksServiceRecreation();
        localServicesIndependentFromVoiceChoice();
        bootDeliveryTraceAndIndependentStartFailure();
        System.out.println(assertions + " runtime service lifecycle assertions passed; actual service/settings/receivers, no API/device");
    }
}

final class PrivateConfig {
    private static final Map<Context, JSONObject> results = Collections.synchronizedMap(new IdentityHashMap<Context, JSONObject>());
    private static final Map<Context, JSONObject> boots = Collections.synchronizedMap(new IdentityHashMap<Context, JSONObject>());
    static JSONObject config=new JSONObject();
    static JSONObject read(Context context, String name) { return "boot-status.json".equals(name) ? boots.getOrDefault(context, new JSONObject()) : config; }
    static void write(Context context, String name, JSONObject value) throws Exception {
        ("boot-status.json".equals(name) ? boots : results).put(context, new JSONObject(value.toString())); }
    static JSONObject last(Context context) { return results.get(context); }
    static JSONObject lastBoot(Context context) { return boots.get(context); }
}
final class ProbeService { static boolean busy; static boolean isBusy() { return busy; } }
final class VoiceHistory {
    static VoiceHistory get(Context context) { return new VoiceHistory(); }
}
final class VoiceHistoryServer {
    VoiceHistoryServer(VoiceHistory history) { }
    void start() throws java.io.IOException { }
    void close() { }
}
final class RuntimeVoiceHandler {
    interface ConfigSource { JSONObject snapshot() throws Exception; }
    static int instances; static RuntimeVoiceHandler latest;
    final Runnable changed; boolean closed; int workers;
    RuntimeVoiceHandler(Context context, ConfigSource config, Runnable changed, TvVoiceControl tv, VoiceHistory history) {
        this.changed = changed; latest = this; instances++; changed.run();
    }
    void begin() { if (!VoiceCloudGate.tryAcquire(this)) throw new AssertionError("gate busy"); workers = 1; }
    void finish() { workers = 0; VoiceCloudGate.release(this); changed.run(); }
    void close() { closed = true; }
    boolean canRelease() { return closed && workers == 0; }
    boolean refreshConfiguration() { changed.run(); return true; }
    JSONObject snapshot() {
        try { return new JSONObject().put("credentialsReady", true).put("healthy", !closed)
                    .put("cloudBusy", VoiceCloudGate.isBusy()).put("activeWorkers", workers); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
final class LoopbackVoiceServer {
    static int starts; static boolean failNext; static LoopbackVoiceServer latest; boolean active;
    static LoopbackVoiceServer runtime(RuntimeVoiceHandler handler) { latest = new LoopbackVoiceServer(); return latest; }
    static LoopbackVoiceServer runtime(LocalAsrRuntime handler,LocalAsrRuntime progressive) { latest = new LoopbackVoiceServer(); return latest; }
    void start() { starts++; active = !failNext; failNext = false; }
    void stop() { active = false; }
    JSONObject status() { try { return new JSONObject().put("active", active); } catch (Exception error) { throw new AssertionError(error); } }
}

final class LocalAsrRuntime {
    static int instances;static LocalAsrRuntime latest;
    final Runnable changed;boolean closed,cleaning;
    LocalAsrRuntime(Context context,RuntimeVoiceHandler handler,Runnable changed) {
        this.changed=changed;latest=this;instances++;changed.run();
    }
    LocalAsrRuntime(Context context,RuntimeVoiceHandler handler,Runnable changed,String engine,int threads) {
        this(context,handler,changed);
    }
    LocalAsrRuntime(Context context,RuntimeVoiceHandler handler,Runnable changed,String engine,int threads,boolean observe) {
        this(context,handler,changed);
    }
    LocalAsrRuntime(Context context,RuntimeVoiceHandler handler,Runnable changed,String engine,int threads,boolean observe,
                    boolean endpoint,MediaVoiceContext media) {
        this(context,handler,changed);
    }
    void close() { closed=true; }
    void finish() { cleaning=false;changed.run(); }
    boolean canRelease() { return closed&&!cleaning; }
    boolean isReady() { return !closed; }
    JSONObject snapshot() { return new JSONObject(); }
}
final class TvVoiceControl { TvVoiceControl(Context context, MediaVoiceContext media) {} }
final class TvPlaybackAccessibilityService {
    static MediaVoiceContext.Mode foregroundMedia() { return MediaVoiceContext.Mode.NONE; }
}
