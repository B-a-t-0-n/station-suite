package local.station.agent;

import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded main-thread admission. A job which expires while queued cannot mutate a later track. */
final class AgentMediaDispatch {
    interface Dispatcher {
        boolean isCurrentThread();
        boolean post(Runnable task);
        void remove(Runnable task);
    }
    interface Operation { JSONObject run() throws Exception; }
    private AgentMediaDispatch() { }

    static JSONObject call(Dispatcher dispatcher, Operation operation, long timeoutMs) throws Exception {
        if (dispatcher.isCurrentThread()) return perform(operation);
        final AtomicInteger phase = new AtomicInteger(); // queued, running, completed/cancelled
        final AtomicReference<JSONObject> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Runnable task = () -> {
            if (!phase.compareAndSet(0, 1)) return;
            result.set(perform(operation));
            phase.set(2); done.countDown();
        };
        if (!dispatcher.post(task)) return BluetoothMediaCommands.rejected("main_timeout");
        try {
            if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) return result.get();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        phase.compareAndSet(0, 2);
        dispatcher.remove(task);
        return BluetoothMediaCommands.rejected("main_timeout");
    }
    private static JSONObject perform(Operation operation) {
        try { return operation.run(); }
        catch (AgentMediaQueue.Failure rejected) { return failure(rejected.code); }
        catch (Exception ignored) { return failure("media_failure"); }
    }
    private static JSONObject failure(String code) {
        try { return BluetoothMediaCommands.rejected(code); }
        catch (Exception impossible) { return new JSONObject(); }
    }
}
