package local.station.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Production queue, protocol and main-thread admission with fake actions only. */
public final class AgentMediaHostTest {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++; if (!value) throw new AssertionError(description);
    }
    private static AgentMediaQueue.Track track(int number) {
        return new AgentMediaQueue.Track("http://127.0.0.1:17686/audio/track-" + String.format(java.util.Locale.ROOT, "%032x", number) + ".mp3",
                "Synthetic title " + number, "Synthetic artist", 180 + number);
    }
    private static JSONObject data(String reply) throws Exception {
        return new JSONObject(reply.substring(reply.indexOf(' ') + 1));
    }
    private static String command(FakeTarget target, String command) throws Exception {
        return BluetoothMediaCommands.execute(command.split("\\s+"), target);
    }
    private static void failure(AgentMediaQueue queue, String revision, int index, String code) throws Exception {
        String before = queue.revision();
        try { queue.select(revision, index); throw new AssertionError("unsafe selection accepted"); }
        catch (AgentMediaQueue.Failure expected) { check(code.equals(expected.code), "fixed selection failure"); }
        check(before.equals(queue.revision()), "rejected selection cannot change queue or revision");
    }
    private static void seekFailure(AgentMediaQueue queue, String revision, int position,
                                    boolean prepared, int duration, String code) throws Exception {
        try { queue.seekPosition(revision, position, prepared, duration); throw new AssertionError("unsafe seek accepted"); }
        catch (AgentMediaQueue.Failure expected) { check(code.equals(expected.code), "fixed seek failure"); }
    }
    public static void main(String[] args) throws Exception {
        AgentMediaQueue queue = new AgentMediaQueue();
        String emptyRevision = queue.revision();
        JSONObject empty = queue.snapshot();
        check(empty.getInt("index") == -1 && empty.getInt("total") == 0
                && empty.getJSONArray("tracks").length() == 0 && !empty.getBoolean("truncated"), "empty queue represented honestly");
        check(queue.revision().equals(emptyRevision), "reading queue never starts playback or advances revision");
        failure(queue, emptyRevision, 0, "queue_index");
        queue.replace(Arrays.asList(track(1), track(2), track(3)));
        String loadedRevision = queue.revision();
        check(!loadedRevision.equals(emptyRevision), "catalog replacement invalidates old snapshots");
        JSONObject loaded = queue.snapshot();
        check(loaded.getInt("index") == -1 && loaded.getInt("total") == 3, "catalog need not already be prepared");
        failure(queue, emptyRevision, 0, "queue_stale");
        failure(queue, loadedRevision, -1, "queue_index");
        failure(queue, loadedRevision, 3, "queue_index");
        AgentMediaQueue.Track selected = queue.select(loadedRevision, 1);
        check(selected.url.equals(track(2).url), "index selects exactly the snapshotted item");
        String activeRevision = queue.revision();
        check(!activeRevision.equals(loadedRevision) && queue.snapshot().getInt("index") == 1, "selection advances revision and current index");
        failure(queue, loadedRevision, 2, "queue_stale");
        check(queue.seekPosition(activeRevision, 0, true, 200000) == 0, "seek accepts zero");
        check(queue.seekPosition(activeRevision, 12345, true, 200000) == 12345, "seek uses exact milliseconds");
        check(queue.seekPosition(activeRevision, 200000, true, 200000) == 200000, "seek accepts exact duration endpoint");
        seekFailure(queue, activeRevision, Integer.MAX_VALUE, true, 200000, "seek_position");
        seekFailure(queue, activeRevision, -1, true, 200000, "seek_position");
        seekFailure(queue, loadedRevision, 100, true, 200000, "queue_stale");
        seekFailure(queue, activeRevision, 100, false, 200000, "media_unavailable");
        seekFailure(queue, activeRevision, 100, true, 0, "duration_unavailable");
        queue.select(activeRevision, 1);
        check(!queue.revision().equals(activeRevision), "explicit restart of same item invalidates an old seek");
        JSONObject status = new JSONObject(); queue.putStatus(status);
        check(status.getInt("queueIndex") == 1 && status.getInt("queueSize") == 3
                && status.getBoolean("hasNext") && status.getBoolean("hasPrevious")
                && status.getString("queueRevision").equals(queue.snapshot().getString("revision")), "status and queue agree");
        String previous = queue.revision();
        check(queue.relative(1).url.equals(track(3).url) && !queue.revision().equals(previous), "legacy next invalidates snapshots");
        previous = queue.revision();
        check(queue.relative(1) == null && queue.revision().equals(previous), "end-of-queue read does not mutate");
        check(queue.relative(-1).url.equals(track(2).url), "legacy previous retains behaviour");
        previous = queue.revision(); queue.play(track(2).url, "ignored", "ignored");
        check(!queue.revision().equals(previous) && queue.snapshot().getInt("total") == 3, "old HTTP play keeps queue and invalidates seeks");
        queue.play(track(9).url, "new title", "new artist");
        check(queue.snapshot().getInt("total") == 1 && queue.snapshot().getInt("index") == 0, "old HTTP play outside queue replaces it");
        previous = queue.revision(); queue.replace(Arrays.asList(track(7), track(8)));
        seekFailure(queue, previous, 100, true, 200000, "queue_stale");
        check(queue.snapshot().getInt("index") == -1 && queue.seekPosition(queue.revision(), 100, true, 200000) == 100,
                "fresh status can seek a detached prepared player after catalog replacement");
        AgentMediaQueue other = new AgentMediaQueue();
        check(!other.revision().equals(new AgentMediaQueue().revision()), "service instances have distinct opaque revisions");
        List<AgentMediaQueue.Track> large = new ArrayList<>();
        String label = repeat("Ж漢", 100) + "\r\nPRIVATE_CONTROL\u202e\ud800";
        for (int i = 0; i < 201; i++) large.add(new AgentMediaQueue.Track(track(i).url, label, label, Integer.MAX_VALUE));
        other.replace(large); JSONObject snapshot = other.snapshot();
        check(snapshot.getInt("total") == 201 && snapshot.getBoolean("truncated")
                && snapshot.getJSONArray("tracks").length() == 100, "queue response has a strict item bound");
        check(snapshot.toString().getBytes(StandardCharsets.UTF_8).length < 80 * 1024, "bounded Unicode metadata fits Bluetooth response budget");
        for (int i = 0; i < 100; i++) {
            JSONObject item = snapshot.getJSONArray("tracks").getJSONObject(i);
            check(item.length() == 5 && item.getBoolean("available") && !item.has("url") && item.getInt("index") == i, "minimal indexed snapshot never includes URLs");
            check(item.getString("title").length() <= 96 && !item.getString("title").contains("\n")
                    && item.getLong("durationMs") == 86400000L, "labels and numeric metadata bounded");
        }
        snapshot.getJSONArray("tracks").getJSONObject(0).put("title", "mutated client copy");
        check(!other.snapshot().getJSONArray("tracks").getJSONObject(0).getString("title").equals("mutated client copy"), "snapshots are detached");
        other.replace(Arrays.asList(new AgentMediaQueue.Track("https://evil.invalid/a.mp3", "bad", "bad", 0)));
        failure(other, other.revision(), 0, "queue_track_unavailable");
        other.replace(Arrays.asList(track(1), new AgentMediaQueue.Track("", "Metadata only", "Known artist", 9), track(3)));
        JSONObject unavailable = other.snapshot().getJSONArray("tracks").getJSONObject(1);
        check(unavailable.getString("title").equals("Metadata only") && !unavailable.getBoolean("available") && other.snapshot().getInt("total") == 3,
                "unavailable track remains visible without guessed URL");
        failure(other, other.revision(), 1, "queue_track_unavailable");
        other.select(other.revision(), 0); check(other.relative(1).url.equals(track(3).url), "next skips unavailable metadata-only track");
        for (String invalid : new String[]{null, "", "http://192.168.77.1:8899/audio/a.mp3", "http://localhost:17686/audio/track-00000000000000000000000000000001.mp3",
                "http://127.0.0.1:17686/audio/track-00000000000000000000000000000001.mp3?x=1", "http://127.0.0.1:17686/audio/track-ABCDEF00000000000000000000000001.mp3",
                "http://127.0.0.1:17686/audio/../track-00000000000000000000000000000001.mp3", "http://127.0.0.1:17687/audio/track-00000000000000000000000000000001.mp3"})
            check(!AgentMediaQueue.playbackUrl(invalid), "only exact local opaque track URL admitted");
        protocol(); dispatch(); concurrentSnapshots();
        System.out.println("AgentMediaHostTest: " + checks + " assertions passed; fake actions only");
    }
    private static void protocol() throws Exception {
        FakeTarget target = new FakeTarget();
        for (String old : new String[]{"PING", "STATUS", "APPS", "HOME", "MEDIA NEXT", "VOLUME 30", "LAUNCH a b"})
            check(command(target, old) == null && target.calls == 0, "legacy commands remain owned by old dispatcher");
        String revision = target.queue.revision();
        JSONObject listing = data(command(target, "QUEUE"));
        check(listing.getBoolean("ok") && target.played == 0 && target.seeked == 0, "QUEUE is read-only");
        for (String malformed : new String[]{"QUEUE extra", "QUEUE_PLAY", "QUEUE_PLAY x 0", "SEEK", "SEEK 0",
                "QUEUE_PLAY " + revision + " -1", "QUEUE_PLAY " + revision + " 01", "QUEUE_PLAY " + revision + " +1",
                "QUEUE_PLAY " + revision + " 2147483648", "SEEK " + revision + " 1.5", "SEEK " + revision + " 1e3",
                "SEEK " + revision + " 1 extra", "QUEUE_PLAY http://evil.invalid 0", "QUEUE_PLAY " + revision + " 0;HOME"}) {
            int calls = target.calls;
            String rejected = command(target, malformed);
            check(rejected.startsWith("ERR ") && data(rejected).getString("code").equals("bad_arguments")
                    && target.calls == calls, "malformed new command cannot reach the player");
        }
        // STOP leaves a known queue but no prepared player. Selecting a track remains useful.
        target.prepared = false;
        JSONObject played = data(command(target, "QUEUE_PLAY " + revision + " 1"));
        check(played.getBoolean("ok") && target.played == 1 && target.selected == 1, "STOP then valid queue selection starts exactly one item");
        check(command(target, "QUEUE_PLAY " + revision + " 0").startsWith("ERR ") && target.played == 1, "replayed selection with stale revision is rejected");
        revision = target.queue.revision();
        check(command(target, "SEEK " + revision + " 100").startsWith("ERR ") && target.seeked == 0, "seek requires prepared player");
        target.prepared = true;
        check(command(target, "SEEK " + revision + " 999999").startsWith("ERR ") && target.seeked == 0,
                "seek beyond actual duration never reaches the player");
        JSONObject sought = data(command(target, "SEEK " + revision + " 150000"));
        check(sought.getBoolean("ok") && target.seeked == 1 && target.position == 150000, "protocol seek uses exact valid current-track position");
        target.queue.select(revision, 0);
        check(command(target, "SEEK " + revision + " 100").startsWith("ERR ") && target.seeked == 1, "seek from previous drag cannot affect a new track");
    }
    private static void dispatch() throws Exception {
        AtomicInteger actions = new AtomicInteger(); FakeDispatcher dispatcher = new FakeDispatcher();
        JSONObject result = AgentMediaDispatch.call(dispatcher, () -> { actions.incrementAndGet(); return new JSONObject().put("ok", true); }, 10);
        check(result.getString("code").equals("main_timeout") && actions.get() == 0, "blocked main-thread queue times out without playback");
        Runnable expired = dispatcher.task; expired.run();
        check(actions.get() == 0 && dispatcher.removed == 1, "even a late queued callback cannot perform an expired action");
        dispatcher.current = true;
        result = AgentMediaDispatch.call(dispatcher, () -> { actions.incrementAndGet(); return new JSONObject().put("ok", true); }, 10);
        check(result.getBoolean("ok") && actions.get() == 1, "already-main call executes exactly once");
        result = AgentMediaDispatch.call(dispatcher, () -> { throw new IllegalStateException("PRIVATE URL AND ACCOUNT"); }, 10);
        check(result.getString("code").equals("media_failure") && !result.toString().contains("PRIVATE"), "player exceptions have fixed safe feedback");
        AgentMediaQueue queue = new AgentMediaQueue(); queue.replace(Arrays.asList(track(1), track(2)));
        String stale = queue.revision(); dispatcher = new FakeDispatcher();
        FakeDispatcher pending = dispatcher;
        AtomicReference<JSONObject> reply = new AtomicReference<>();
        Thread waiting = new Thread(() -> {
            try { reply.set(AgentMediaDispatch.call(pending, () -> {
                queue.select(stale, 0); actions.incrementAndGet(); return new JSONObject().put("ok", true);
            }, 2000)); } catch (Exception error) { throw new AssertionError(error); }
        });
        waiting.start(); check(dispatcher.posted.await(1, TimeUnit.SECONDS), "selection admitted to fake main queue");
        queue.replace(Arrays.asList(track(9))); dispatcher.task.run(); waiting.join(1000);
        check(!waiting.isAlive() && reply.get().getString("code").equals("queue_stale") && actions.get() == 1,
                "revision is validated on execution, after a pending queue replacement");
    }
    private static void concurrentSnapshots() throws Exception {
        AgentMediaQueue queue = new AgentMediaQueue(); queue.replace(Arrays.asList(track(1), track(2)));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread mutate = new Thread(() -> {
            try { for (int i = 0; i < 500; i++) { queue.replace(Arrays.asList(track(1), track(2))); queue.play(track(2).url, "", ""); } }
            catch (Throwable error) { failure.set(error); }
        });
        mutate.start();
        for (int i = 0; i < 500; i++) {
            JSONObject value = queue.snapshot();
            check(value.getJSONArray("tracks").length() == value.getInt("total")
                    && value.getInt("index") < value.getInt("total"), "concurrent snapshot has coherent index and membership");
        }
        mutate.join(2000); check(!mutate.isAlive() && failure.get() == null, "concurrent replacement/snapshot finishes safely");
    }
    private static String repeat(String text, int count) { StringBuilder value = new StringBuilder(); for (int i=0;i<count;i++) value.append(text); return value.toString(); }
    private static final class FakeDispatcher implements AgentMediaDispatch.Dispatcher {
        volatile Runnable task; boolean current; int removed;
        final CountDownLatch posted = new CountDownLatch(1);
        public boolean isCurrentThread() { return current; }
        public boolean post(Runnable value) { task = value; posted.countDown(); return true; }
        public void remove(Runnable value) { if (task == value) removed++; }
    }
    private static final class FakeTarget implements BluetoothMediaCommands.Target {
        final AgentMediaQueue queue = new AgentMediaQueue();
        int calls, played, seeked, selected, position; boolean prepared;
        FakeTarget() { queue.replace(Arrays.asList(track(1), track(2))); }
        public JSONObject queue() throws Exception { calls++; return queue.snapshot(); }
        public JSONObject queuePlay(String revision, int index) throws Exception {
            calls++;
            try { queue.select(revision, index); played++; selected=index; return new JSONObject().put("ok",true).put("accepted",true); }
            catch (AgentMediaQueue.Failure error) { return BluetoothMediaCommands.rejected(error.code); }
        }
        public JSONObject seek(String revision, int requested) throws Exception {
            calls++;
            try { position=queue.seekPosition(revision, requested, prepared, 200000); seeked++; return new JSONObject().put("ok",true); }
            catch (AgentMediaQueue.Failure error) { return BluetoothMediaCommands.rejected(error.code); }
        }
    }
}
