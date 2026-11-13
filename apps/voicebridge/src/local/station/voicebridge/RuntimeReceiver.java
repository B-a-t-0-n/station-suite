package local.station.voicebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Shell-only lifecycle controls; never accepts credentials or configuration paths. */
public final class RuntimeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String operation = intent == null ? "" : intent.getStringExtra("operation");
        if ("local_services_start".equals(operation)) {
            if (LocalServicesService.configured(context))
                context.startForegroundService(new Intent(context, LocalServicesService.class));
            return;
        }
        if ("local_services_stop".equals(operation)) {
            context.stopService(new Intent(context, LocalServicesService.class)); return;
        }
        if ("runtime_enable".equals(operation)) {
            if (!RuntimeSettings.setEnabled(context, true)) return;
        } else if ("runtime_disable".equals(operation)) {
            if (!RuntimeSettings.setEnabled(context, false)) return;
        } else if (!"runtime_start".equals(operation)) return;
        if (RuntimeSettings.enabled(context) || "runtime_disable".equals(operation))
            context.startForegroundService(new Intent(context, VoiceRuntimeService.class));
    }
}
