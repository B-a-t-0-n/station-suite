package local.station.agent;

import android.os.Handler;
import android.os.Looper;

/** Named adapter for the bundled Android toolchain; no anonymous class metadata. */
final class AgentHandlerDispatcher implements AgentMediaDispatch.Dispatcher {
    private final Handler main;
    AgentHandlerDispatcher(Handler main) { this.main=main; }
    @Override public boolean isCurrentThread() { return Looper.myLooper()==main.getLooper(); }
    @Override public boolean post(Runnable task) { return main.post(task); }
    @Override public void remove(Runnable task) { main.removeCallbacks(task); }
}
