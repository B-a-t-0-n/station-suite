package local.station.voicebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Trace trace = new Trace(context, Intent.ACTION_BOOT_COMPLETED.equals(action));
            trace.stage("received");
            boolean configured = false, runtimeEnabled = false;
            try { configured = LocalServicesService.configured(context); trace.value("configuredKnown", true); }
            catch (Exception unavailable) { trace.value("localCode", "configuration_check_failed"); }
            try { runtimeEnabled = RuntimeSettings.enabled(context); trace.value("runtimeEnabledKnown", true); }
            catch (Exception unavailable) { trace.value("voiceCode", "runtime_settings_failed"); }
            trace.value("configured", configured); trace.value("runtimeEnabled", runtimeEnabled); trace.stage("checked");
            // A failure in one independently owned service must not suppress the other start.
            if (configured) {
                trace.stage("requesting_local_services");
                try {
                    context.startForegroundService(new Intent(context, LocalServicesService.class));
                    trace.value("localStartRequested", true); trace.value("localCode", "requested");
                } catch (SecurityException denied) { trace.value("localCode", "start_denied"); }
                catch (Exception unavailable) { trace.value("localCode", "start_failed"); }
                trace.stage("local_services_checked");
            }
            if (runtimeEnabled) {
                trace.stage("requesting_voice");
                try {
                    context.startForegroundService(new Intent(context, VoiceRuntimeService.class));
                    trace.value("voiceStartRequested", true); trace.value("voiceCode", "requested");
                } catch (SecurityException denied) { trace.value("voiceCode", "start_denied"); }
                catch (Exception unavailable) { trace.value("voiceCode", "start_failed"); }
                trace.stage("voice_checked");
            }
            trace.stage("done");
        }
    }
    /** A private delivery record, never raw Intent/extras, private configs or exception text. */
    private static final class Trace {
        final Context context; final JSONObject state = new JSONObject(); final JSONArray stages = new JSONArray();
        Trace(Context context, boolean boot) {
            this.context = context; JSONObject previous;
            try { previous = PrivateConfig.read(context, "boot-status.json"); } catch (Exception unavailable) { previous = new JSONObject(); }
            value("deliveries", increment(previous.optLong("deliveries", 0)));
            value("bootDeliveries", boot ? increment(previous.optLong("bootDeliveries", 0)) : nonnegative(previous.optLong("bootDeliveries", 0)));
            value("upgradeDeliveries", boot ? nonnegative(previous.optLong("upgradeDeliveries", 0)) : increment(previous.optLong("upgradeDeliveries", 0)));
            value("action", boot ? "boot_completed" : "package_replaced");
            value("receivedAtMs", System.currentTimeMillis()); value("receivedAtElapsedMs", SystemClock.elapsedRealtime());
            value("configuredKnown", false); value("configured", false); value("runtimeEnabledKnown", false); value("runtimeEnabled", false);
            value("localStartRequested", false); value("voiceStartRequested", false);
            value("localCode", "not_provisioned"); value("voiceCode", "disabled");
        }
        void value(String key, Object value) { try { state.put(key, value); } catch (Exception ignored) { } }
        void stage(String stage) {
            try {
                long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
                value("stage", stage); value("updatedAtMs", now); value("updatedAtElapsedMs", elapsed);
                stages.put(new JSONObject().put("stage", stage).put("atMs", now).put("elapsedMs", elapsed)); value("stages", stages);
                PrivateConfig.write(context, "boot-status.json", state);
            } catch (Exception ignored) { /* Diagnostics must never block service admission. */ }
        }
        private static long nonnegative(long value) { return Math.max(0, value); }
        private static long increment(long value) { return value == Long.MAX_VALUE ? value : nonnegative(value) + 1; }
    }
}
