package local.station.agent;

import android.os.Handler;
import android.os.SystemClock;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;

/** Owned native playback on the exact 20221212 audioclient endpoint. No main-thread IO. */
final class NativeMusicPlayer {
    static final int IDLE = 1, BUFFERING = 2, PLAYING = 3, PAUSED = 4,
            STOPPED = 5, FINISHED = 6, FAILED = 7;
    private static final int PORT = 9906, MAX_COMMANDS = 24, MAX_CALLBACKS = 16;
    private static final long CONNECT_MS = 1200, WRITE_MS = 1200, PREPARE_MS = 20000;

    interface Listener {
        default void onPrepared(Snapshot state) { }
        default void onPlaying(Snapshot state) { }
        default void onProgress(Snapshot state) { }
        default void onPaused(Snapshot state) { }
        default void onStopped(Snapshot state) { }
        default void onFinished(Snapshot state) { }
        default void onError(String reason) { }
    }
    static final class Snapshot {
        final String playerId, streamId, url, title, artist;
        final int state; final long positionMs, durationMs, updatedElapsedMs;
        final boolean prepared;
        Snapshot(Track track, int state, long position, long duration, boolean prepared, long now) {
            playerId = track == null ? "" : track.playerId; streamId = track == null ? "" : track.streamId;
            url = track == null ? "" : track.url; title = track == null ? "" : track.title;
            artist = track == null ? "" : track.artist; this.state = state;
            positionMs = position; durationMs = duration; this.prepared = prepared; updatedElapsedMs = now;
        }
        long estimatedPositionMs() {
            long value = positionMs + (state == PLAYING ? Math.max(0, SystemClock.elapsedRealtime() - updatedElapsedMs) : 0);
            return durationMs > 0 ? Math.min(value, durationMs) : value;
        }
    }
    private static final class Track {
        final String playerId, streamId, url, title, artist; final long durationHint;
        Track(String player, String url, String title, String artist, long duration) {
            playerId = player; streamId = "station-agent-stream-" + UUID.randomUUID(); this.url = url;
            this.title = title == null ? "" : title; this.artist = artist == null ? "" : artist;
            durationHint = Math.max(0, Math.min(duration, 7L * 86400000));
        }
    }
    private static final class Command {
        static final int PLAY = 1, PAUSE = 2, RESUME = 3, STOP = 4, SEEK = 5;
        final Track track; final int kind; final long position;
        Command(Track track, int kind, long position) { this.track = track; this.kind = kind; this.position = position; }
        byte[] encode() {
            String request = UUID.randomUUID().toString(), player = track.playerId, stream = track.streamId;
            switch (kind) {
                case PLAY: return NativeMusicWire.play(request, player, stream, track.url, track.title, track.artist);
                case PAUSE: return NativeMusicWire.pause(request, player, stream);
                case RESUME: return NativeMusicWire.resume(request, player, stream);
                case STOP: return NativeMusicWire.stop(request, player, stream);
                case SEEK: return NativeMusicWire.seek(request, player, stream, position);
                default: throw new IllegalArgumentException("native_command");
            }
        }
    }
    private static final class Notice {
        final Track track; final int kind; final Snapshot state; final String error;
        Notice(Track track, int kind, Snapshot state, String error) {
            this.track = track; this.kind = kind; this.state = state; this.error = error;
        }
    }
    private final Object lock = new Object();
    private final ArrayDeque<Command> commands = new ArrayDeque<>();
    private final ArrayDeque<Notice> notices = new ArrayDeque<>();
    private final Handler callbackHandler; private final Listener listener; private final int endpointPort;
    private final String playerId = "station-agent-music-" + UUID.randomUUID();
    private volatile Track current;
    private volatile Track nativeTrack;
    private volatile Snapshot snapshot = new Snapshot(null, IDLE, 0, 0, false, SystemClock.elapsedRealtime());
    private volatile boolean released;
    private volatile Selector selector;
    private SocketChannel channel;
    private final ByteBuffer header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    private ByteBuffer payload;
    private long partialFrameDeadline;
    private Track awaiting; private long prepareDeadline;
    private Notice progress; private boolean noticePosted;

    NativeMusicPlayer(Handler callbackHandler, Listener listener) {
        this(callbackHandler, listener, PORT);
    }
    /** Package-local loopback endpoint seam for offline transport tests. */
    NativeMusicPlayer(Handler callbackHandler, Listener listener, int endpointPort) {
        if (callbackHandler == null || listener == null) throw new IllegalArgumentException("native_listener");
        if (endpointPort < 1024 || endpointPort > 65535) throw new IllegalArgumentException("native_port");
        this.callbackHandler = callbackHandler; this.listener = listener; this.endpointPort = endpointPort;
        Thread worker = new Thread(new Runnable() { @Override public void run() { runWorker(); } }, "StationNativeMusic");
        worker.setDaemon(true); worker.start();
    }
    void play(String url, String title, String artist, long durationHintMs) {
        NativeMusicWire.validateUrl(url);
        Track next = new Track(playerId, url, title, artist, durationHintMs);
        synchronized (lock) {
            requireOpen();
            // Keep addressed stops for prior sessions; drop superseded unsent controls/play requests.
            for (Iterator<Command> it = commands.iterator(); it.hasNext();) if (it.next().kind != Command.STOP) it.remove();
            int required = current == null ? 1 : 2;
            if (commands.size() + required > MAX_COMMANDS) throw new IllegalStateException("native_queue_full");
            if (current != null) enqueueLocked(new Command(current, Command.STOP, 0));
            current = next; snapshot = new Snapshot(next, IDLE, 0, next.durationHint, false, SystemClock.elapsedRealtime());
            notices.clear(); progress = null;
            enqueueLocked(new Command(next, Command.PLAY, 0)); wake();
        }
    }
    void pause() { control(Command.PAUSE, 0); }
    void resume() { control(Command.RESUME, 0); }
    void stop() { control(Command.STOP, 0); }
    void seekTo(long positionMs) {
        if (positionMs < 0 || positionMs > 7L * 86400000) throw new IllegalArgumentException("native_seek");
        control(Command.SEEK, positionMs);
    }
    private void control(int kind, long position) {
        synchronized (lock) {
            requireOpen(); Track track = current; if (track == null) return;
            if (kind == Command.SEEK || kind == Command.PAUSE || kind == Command.RESUME) {
                for (Iterator<Command> it = commands.iterator(); it.hasNext();) {
                    Command old = it.next();
                    if (old.track == track && (old.kind == kind
                            || ((kind == Command.PAUSE || kind == Command.RESUME)
                            && (old.kind == Command.PAUSE || old.kind == Command.RESUME)))) it.remove();
                }
            }
            if (kind == Command.STOP) {
                for (Iterator<Command> it = commands.iterator(); it.hasNext();) {
                    Command old = it.next(); if (old.track == track) it.remove();
                }
                if (nativeTrack != null && nativeTrack != track) enqueueLocked(new Command(nativeTrack, Command.STOP, 0));
            }
            enqueueLocked(new Command(track, kind, position)); wake();
        }
    }
    private void enqueueLocked(Command command) {
        if (command.kind == Command.STOP) for (Command old : commands)
            if (old.kind == Command.STOP && old.track == command.track) return;
        if (commands.size() >= MAX_COMMANDS) throw new IllegalStateException("native_queue_full");
        commands.addLast(command);
    }
    Snapshot getSnapshot() { return snapshot; }
    boolean isPlaying() { return snapshot.state == PLAYING; }
    long getCurrentPosition() { return snapshot.estimatedPositionMs(); }
    long getDuration() { return snapshot.durationMs; }
    void release() {
        synchronized (lock) {
            if (released) return;
            commands.clear();
            if (nativeTrack != null && nativeTrack != current) commands.add(new Command(nativeTrack, Command.STOP, 0));
            if (current != null) commands.add(new Command(current, Command.STOP, 0));
            released = true; notices.clear(); progress = null; wake();
        }
    }
    private void requireOpen() { if (released) throw new IllegalStateException("native_released"); }
    private void wake() { lock.notifyAll(); Selector selected = selector; if (selected != null) selected.wakeup(); }

    private void runWorker() {
        try {
            selector = Selector.open();
            while (true) {
                Command command;
                synchronized (lock) {
                    command = commands.pollFirst();
                    if (released && command == null) break;
                    if (command == null && channel == null) { lock.wait(250); continue; }
                }
                try {
                    if (command != null) {
                        synchronized (lock) {
                            if (command.kind == Command.PLAY) {
                                if (released) continue;
                                nativeTrack = command.track;
                            }
                        }
                        connect(); write(command.encode());
                        if (command.kind == Command.PLAY && current == command.track) {
                            awaiting = command.track; prepareDeadline = SystemClock.elapsedRealtime() + PREPARE_MS;
                        }
                        if (command.kind == Command.STOP && awaiting == command.track) awaiting = null;
                    }
                    readEvents();
                    if (awaiting != null && current == awaiting && SystemClock.elapsedRealtime() >= prepareDeadline) {
                        Track failed = awaiting; awaiting = null; cleanup(failed); fail(failed, "native_prepare_timeout");
                    }
                    selector.select(100); selector.selectedKeys().clear();
                } catch (Exception failed) {
                    Track cleanupTarget = nativeTrack; closeChannel(); awaiting = null;
                    synchronized (lock) {
                        commands.clear();
                        if (!released) fail(current, "native_ipc_unavailable");
                    }
                    // Never replay play after a failed connection. Best-effort addressed cleanup only.
                    if (cleanupTarget != null) cleanup(cleanupTarget);
                }
            }
        } catch (Exception unavailable) {
            if (!released) fail(current, "native_worker_unavailable");
        } finally {
            closeChannel(); Selector selected = selector; selector = null;
            if (selected != null) try { selected.close(); } catch (IOException ignored) { }
        }
    }
    private void connect() throws IOException {
        if (channel != null && channel.isConnected()) return;
        channel = SocketChannel.open(); channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(true);
        channel.register(selector, SelectionKey.OP_CONNECT);
        channel.connect(new InetSocketAddress("127.0.0.1", endpointPort));
        long deadline = SystemClock.elapsedRealtime() + CONNECT_MS;
        while (!channel.finishConnect()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("connect_timeout");
            selector.select(50); selector.selectedKeys().clear();
        }
        channel.keyFor(selector).interestOps(SelectionKey.OP_READ); header.clear(); payload = null;
    }
    private void write(byte[] message) throws IOException {
        ByteBuffer bytes = NativeMusicWire.frame(message);
        long deadline = SystemClock.elapsedRealtime() + WRITE_MS;
        channel.keyFor(selector).interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        while (bytes.hasRemaining()) {
            int n = channel.write(bytes); if (n < 0) throw new IOException("ipc_eof");
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("write_timeout");
            if (n == 0) { selector.select(50); selector.selectedKeys().clear(); }
        }
        channel.keyFor(selector).interestOps(SelectionKey.OP_READ);
    }
    private void readEvents() throws IOException {
        if (channel == null) return;
        if (partialFrameDeadline != 0 && SystemClock.elapsedRealtime() >= partialFrameDeadline)
            throw new IOException("ipc_partial_frame_timeout");
        for (int count = 0; count < 32; count++) {
            ByteBuffer destination = payload == null ? header : payload;
            int n = channel.read(destination);
            if (n < 0) throw new IOException("ipc_eof");
            if (n == 0) return;
            if (partialFrameDeadline == 0) partialFrameDeadline = SystemClock.elapsedRealtime() + 5000;
            if (destination.hasRemaining()) continue;
            if (payload == null) {
                header.flip(); int size = header.getInt(); header.clear();
                if (size < 1 || size > NativeMusicWire.MAX_FRAME) throw new IOException("ipc_frame_size");
                payload = ByteBuffer.allocate(size);
            } else {
                byte[] message = payload.array(); payload = null; partialFrameDeadline = 0;
                for (NativeMusicWire.Event event : NativeMusicWire.events(message)) apply(event);
            }
        }
    }
    private void apply(NativeMusicWire.Event event) {
        synchronized (lock) {
        Track track = current;
        if (track == null || !event.matches(track.playerId, track.streamId) || event.state < IDLE || event.state > FAILED) return;
        Snapshot old = snapshot;
        if (!old.streamId.equals(track.streamId)) return;
        long now = SystemClock.elapsedRealtime();
        long position = event.positionMs >= 0 ? event.positionMs : old.estimatedPositionMs();
        long duration = event.durationMs > 0 ? event.durationMs : old.durationMs;
        boolean prepared = old.prepared || event.state == PLAYING || event.state == PAUSED;
        Snapshot next = new Snapshot(track, event.state, position, duration, prepared, now);
        snapshot = next;
        if (prepared && awaiting == track) awaiting = null;
        if (!old.prepared && prepared) notice(new Notice(track, 10, next, null));
        if (event.state != old.state) {
            if (event.state == PLAYING) notice(new Notice(track, PLAYING, next, null));
            else if (event.state == PAUSED) notice(new Notice(track, PAUSED, next, null));
            else if (event.state == STOPPED) { awaiting = null; notice(new Notice(track, STOPPED, next, null)); }
            else if (event.state == FINISHED) { awaiting = null; notice(new Notice(track, FINISHED, next, null)); }
            else if (event.state == FAILED) { awaiting = null; notice(new Notice(track, FAILED, next, "native_playback_failed")); }
        }
        notice(new Notice(track, 11, next, null));
        }
    }
    private void cleanup(Track track) {
        try { connect(); write(NativeMusicWire.stop(UUID.randomUUID().toString(), track.playerId, track.streamId)); }
        catch (Exception ignored) { } finally { closeChannel(); }
    }
    private void fail(Track track, String reason) {
        synchronized (lock) {
        if (track != current || track == null) return;
        Snapshot old = snapshot;
        Snapshot next = new Snapshot(track, FAILED, old.estimatedPositionMs(), old.durationMs, old.prepared, SystemClock.elapsedRealtime());
        snapshot = next; notice(new Notice(track, FAILED, next, reason));
        }
    }
    private void closeChannel() {
        if (channel != null) try { channel.close(); } catch (IOException ignored) { }
        channel = null; payload = null; partialFrameDeadline = 0; header.clear();
    }
    private void notice(Notice notice) {
        synchronized (lock) {
            if (released || notice.track != current) return;
            if (notice.kind == 11) progress = notice;
            else {
                if (notices.size() >= MAX_CALLBACKS) notices.removeFirst();
                notices.addLast(notice);
            }
            if (!noticePosted) {
                noticePosted = true;
                if (!callbackHandler.post(deliverNotices)) { noticePosted = false; notices.clear(); progress = null; }
            }
        }
    }
    private final Runnable deliverNotices = new Runnable() {
        @Override public void run() {
            for (int count = 0; count <= MAX_CALLBACKS; count++) {
                Notice notice;
                synchronized (lock) {
                    notice = notices.pollFirst();
                    if (notice == null && progress != null) { notice = progress; progress = null; }
                    if (notice == null || released) { noticePosted = false; return; }
                }
                if (notice.track != current) continue;
                switch (notice.kind) {
                    case 10: listener.onPrepared(notice.state); break;
                    case PLAYING: listener.onPlaying(notice.state); break;
                    case PAUSED: listener.onPaused(notice.state); break;
                    case STOPPED: listener.onStopped(notice.state); break;
                    case FINISHED: listener.onFinished(notice.state); break;
                    case FAILED: listener.onError(notice.error); break;
                    case 11: listener.onProgress(notice.state); break;
                    default: break;
                }
            }
            synchronized (lock) {
                if (!released && (!notices.isEmpty() || progress != null)) {
                    if (!callbackHandler.post(this)) { noticePosted = false; notices.clear(); progress = null; }
                } else noticePosted = false;
            }
        }
    };
}
