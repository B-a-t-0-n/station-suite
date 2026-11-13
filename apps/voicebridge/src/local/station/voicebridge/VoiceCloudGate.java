package local.station.voicebridge;

import java.util.concurrent.atomic.AtomicReference;

/** Process-wide identity ownership. Release only after upload/delete finally has returned. */
public final class VoiceCloudGate {
    private static final AtomicReference<Object> OWNER = new AtomicReference<>();
    private VoiceCloudGate() { }
    public static boolean tryAcquire(Object owner) {
        if (owner == null) throw new IllegalArgumentException("owner_required");
        return OWNER.compareAndSet(null, owner);
    }
    public static boolean release(Object owner) {
        return owner != null && OWNER.compareAndSet(owner, null);
    }
    public static boolean isBusy() { return OWNER.get() != null; }
}
