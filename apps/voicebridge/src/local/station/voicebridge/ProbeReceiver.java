package local.station.voicebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Shell-only test entry point; keys are never accepted in Intent extras. */
public final class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String operation = intent.getStringExtra("operation");
        if (!"probe".equals(operation) && !"ask".equals(operation)
                && !"tts_info".equals(operation) && !"preflight".equals(operation)
                && !"transport_start".equals(operation) && !"transport_stop".equals(operation)
                && !"transport_status".equals(operation) && !"audio_probe".equals(operation)
                && !"legacy_canary".equals(operation) && !"legacy_fixture".equals(operation)
                && !"legacy_pipeline".equals(operation)
                && !"speechify_probe".equals(operation) && !"pipeline_probe".equals(operation)
                && !"local_asr_probe".equals(operation) && !"sherpa_asr_probe".equals(operation)
                && !"sherpa_endpoint_probe".equals(operation)) return;
        context.startForegroundService(new Intent(context, ProbeService.class)
                .putExtra("operation", operation));
    }
}
