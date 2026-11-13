package local.station.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;

/** Android ownership adapter. A capability probe never prepares or starts its MediaPlayer. */
final class AndroidAgentEqualizer implements AgentEqualizer.Backend {
    static AgentEqualizer create(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("agent_equalizer", Context.MODE_PRIVATE);
        return new AgentEqualizer(new AndroidAgentEqualizer(), new AgentEqualizer.Store() {
            @Override public String load() { return preferences.getString("curve", null); }
            @Override public boolean save(String value) { return preferences.edit().putString("curve", value).commit(); }
        });
    }
    @Override public AgentEqualizer.Engine probe() {
        MediaPlayer owner = new MediaPlayer();
        try { return new AndroidEngine(owner.getAudioSessionId(), owner); }
        catch (RuntimeException failure) { owner.release(); throw failure; }
    }
    @Override public AgentEqualizer.Engine attach(int sessionId) { return new AndroidEngine(sessionId, null); }

    private static final class AndroidEngine implements AgentEqualizer.Engine {
        private final Equalizer effect;
        private final MediaPlayer owner;
        AndroidEngine(int sessionId, MediaPlayer owner) {
            if (sessionId <= 0) throw new IllegalArgumentException("owned_session_required");
            effect = new Equalizer(0, sessionId); this.owner = owner;
        }
        @Override public int bandCount() { return effect.getNumberOfBands(); }
        @Override public int minLevelMb() { return effect.getBandLevelRange()[0]; }
        @Override public int maxLevelMb() { return effect.getBandLevelRange()[1]; }
        @Override public int centerMilliHz(int band) { return effect.getCenterFreq((short) band); }
        @Override public void setLevelMb(int band, int value) { effect.setBandLevel((short) band, (short) value); }
        @Override public int levelMb(int band) { return effect.getBandLevel((short) band); }
        @Override public void setEnabled(boolean enabled) {
            if (effect.setEnabled(enabled) != 0) throw new IllegalStateException("effect_enable_failed");
        }
        @Override public boolean enabled() { return effect.getEnabled(); }
        @Override public boolean hasControl() { return effect.hasControl(); }
        @Override public void release() {
            try { effect.release(); } finally { if (owner != null) owner.release(); }
        }
    }
}
