package local.station.voicebridge;

import android.content.Context;

/** User choice persists across process death, upgrade and reboot. Contains no API credentials. */
final class RuntimeSettings {
    private RuntimeSettings() { }
    static boolean enabled(Context context) {
        return context.getSharedPreferences("voice_runtime", Context.MODE_PRIVATE).getBoolean("enabled", false);
    }
    static boolean setEnabled(Context context, boolean enabled) {
        return context.getSharedPreferences("voice_runtime", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).commit();
    }
}
