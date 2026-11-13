package local.station.voicebridge;

import android.content.Context;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalAsrRuntimeOwnershipHostTest {
    private static int assertions;
    private static void check(boolean value, String name) { assertions++; if (!value) throw new AssertionError(name); }
    private interface Condition { boolean get() throws Exception; }
    private static void await(Condition condition, String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) throw new AssertionError(name);
            Thread.sleep(1);
        }
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static int retained(LocalAsrRuntime runtime) throws Exception {
        Object ownership = field(runtime, "ownership");
        synchronized (ownership) { return ((Map<?, ?>) ownership).size(); }
    }
    private static int tombstones(LocalAsrRuntime runtime) throws Exception {
        Object ownership = field(runtime, "ownership");
        synchronized (ownership) { return ((Collection<?>) field(runtime, "completedPrimary")).size(); }
    }
    private static Object current(LocalAsrRuntime runtime) throws Exception {
        return ((AtomicReference<?>) field(runtime, "current")).get();
    }
    private static void startEnd(LocalAsrRuntime runtime, LoopbackVoiceServer.ReplyWriter writer) {
        check(runtime.offerStart(new LegacyVoiceSession.Metadata(0), writer), "start accepted");
        check(runtime.offerChunk(writer, new byte[]{1, 2, 3, 4}), "bounded selected input admitted");
        check(runtime.offerEnd(new LegacyVoiceSession.Metadata(4), writer), "matching end admitted");
    }
    private static void close(LocalAsrRuntime runtime) throws Exception {
        runtime.close(); await(runtime::canRelease, "worker_release");
        check(retained(runtime) == 0 && tombstones(runtime) == 0 && current(runtime) == null, "close clears all ownership");
    }
    public static void main(String[] args) throws Exception {
        LocalSpeechRecognizer.text = "";
        RuntimeVoiceHandler answers = new RuntimeVoiceHandler();
        LocalAsrRuntime runtime = new LocalAsrRuntime(new Context(), answers, null);
        await(runtime::isReady, "model_ready");
        try {
            // Actual recognize/finally path closes the writer before the transport would call
            // primary. Simulate its isOpen guard skipping primary on every turn.
            for (int i = 0; i < 40; i++) {
                LoopbackVoiceServer.ReplyWriter writer = new LoopbackVoiceServer.ReplyWriter();
                startEnd(runtime, writer);
                await(() -> current(runtime) == null && retained(runtime) == 0, "completed_turn_must_not_leak");
                check(!writer.isOpen() && writer.closes.get() > 0, "fast local completion closes own token");
                check(retained(runtime) == 0 && tombstones(runtime) <= 2, "primary skipped: no Turn leak and bounded tombstones");
            }
            check(answers.transfers.get() == 0 && tombstones(runtime) == 2, "no speech never starts cloud; history bounded2");
            // A delayed primary callback must recognize already-transferred ownership and not
            // quiet/close the still-open writer that now belongs to the cloud answer handler.
            LocalSpeechRecognizer.text = "сколько будет два плюс два";
            LoopbackVoiceServer.ReplyWriter transferred = new LoopbackVoiceServer.ReplyWriter();
            startEnd(runtime, transferred);
            await(() -> current(runtime) == null && retained(runtime) == 0 && answers.transfers.get() == 1, "cloud_transfer_finished");
            runtime.commandReady(new byte[4], new LegacyVoiceSession.Metadata(4), transferred);
            check(transferred.isOpen() && transferred.closes.get() == 0 && transferred.quiet.get() == 0, "late primary preserves transferred writer");
            check(tombstones(runtime) <= 1 && retained(runtime) == 0, "late callback consumes only bounded marker");

            // Primary arrives first, while final recognition is blocked: the worker must not
            // leave a completed marker or cancel the transferred writer on cleanup.
            LocalSpeechRecognizer.finishEntered = new CountDownLatch(1);
            LocalSpeechRecognizer.finishRelease = new CountDownLatch(1);
            LoopbackVoiceServer.ReplyWriter firstPrimary = new LoopbackVoiceServer.ReplyWriter();
            startEnd(runtime, firstPrimary);
            check(LocalSpeechRecognizer.finishEntered.await(2, TimeUnit.SECONDS), "recognizer held before final");
            int before = tombstones(runtime);
            runtime.commandReady(new byte[4], new LegacyVoiceSession.Metadata(4), firstPrimary);
            LocalSpeechRecognizer.finishEntered = null; LocalSpeechRecognizer.finishRelease.countDown();
            await(() -> current(runtime) == null && retained(runtime) == 0 && answers.transfers.get() == 2, "primary_before_finish_cleanup");
            check(firstPrimary.isOpen() && firstPrimary.closes.get() == 0 && tombstones(runtime) == before, "seen primary adds no tombstone");

            // Old cancellation is identity-scoped and cannot cancel the newer active turn.
            LoopbackVoiceServer.ReplyWriter next = new LoopbackVoiceServer.ReplyWriter();
            check(runtime.offerStart(new LegacyVoiceSession.Metadata(0), next), "next turn starts");
            runtime.cancel(transferred, LoopbackVoiceServer.CancelReason.WRITER_CLOSED);
            check(current(runtime) != null && field(current(runtime), "writer") == next, "old callback leaves new ownership");
            check(runtime.offerChunk(next, new byte[]{1, 2, 3, 4})
                    && runtime.offerEnd(new LegacyVoiceSession.Metadata(4), next), "new turn remains admissible after old cancel");
            await(() -> current(runtime) == null && retained(runtime) == 0 && answers.transfers.get() == 3, "new_turn_completes");
            // A valid recognized negation is handled locally without an actuator or
            // cloud call. Native must receive its real final text, not empty speech.
            LocalSpeechRecognizer.text = "не делай громче";
            LoopbackVoiceServer.ReplyWriter negative = new LoopbackVoiceServer.ReplyWriter();
            startEnd(runtime, negative);
            await(() -> current(runtime) == null && !negative.isOpen(), "negative_completed_locally");
            check(negative.quiet.get() == 1 && negative.quietHadText, "local quiet result preserves final recognized speech");
            check(answers.transfers.get() == 3, "negated command never enters cloud");
            check("REJECTED".equals(runtime.snapshot().optString("lastLocalDecision"))
                    && "rejected".equals(runtime.snapshot().optString("lastLocalResultCode"))
                    && !runtime.snapshot().optBoolean("lastLocalAccepted")
                    && runtime.snapshot().optString("lastLocalAction").isEmpty(),
                    "a rejected local command is observable and never reported as an accepted actuator");
        } finally { close(runtime); }

        // Deterministically pause offerStart before its publication lock, close the runtime,
        // then resume. The old one-time closed check would publish an abandoned turn here.
        LocalAsrRuntime closing = new LocalAsrRuntime(new Context(), new RuntimeVoiceHandler(), null);
        await(closing::isReady, "second_model_ready");
        LoopbackVoiceServer.ReplyWriter racing = new LoopbackVoiceServer.ReplyWriter();
        racing.checkEntered = new CountDownLatch(1); racing.checkRelease = new CountDownLatch(1);
        CountDownLatch entered = racing.checkEntered;
        AtomicReference<Boolean> admitted = new AtomicReference<>();
        Thread start = new Thread(() -> admitted.set(closing.offerStart(new LegacyVoiceSession.Metadata(0), racing)));
        start.start(); check(entered.await(2, TimeUnit.SECONDS), "admission paused after initial closed check");
        closing.close(); racing.checkRelease.countDown(); start.join(2000);
        check(!start.isAlive() && Boolean.FALSE.equals(admitted.get()), "close prevents subsequent publication");
        await(closing::canRelease, "second_worker_release");
        check(retained(closing) == 0 && tombstones(closing) == 0
                && ((java.util.concurrent.BlockingQueue<?>) field(closing, "pending")).isEmpty(), "closed runtime has no orphaned admission");
        check(current(closing) == null && !closing.isReady(), "closed state remains final");
        System.out.println("LocalAsrRuntimeOwnershipHostTest: " + assertions + " assertions passed; fake dependencies/no actions");
    }
}
