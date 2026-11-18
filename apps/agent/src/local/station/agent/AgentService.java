package local.station.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.station.agent.AgentMediaQueue.Track;

/** Hosts the Station web agent, an owned native music client, and a media session. */
public final class AgentService extends Service implements BluetoothMediaCommands.Target, StationScheduleRuntime.Host {
    static final String CHANNEL_ID = "station_agent";
    private static final int NOTIFICATION_ID = 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private NativeMusicPlayer nativePlayer;
    private AgentEqualizer equalizer;
    private StationDisplaySettings displaySettings;
    private StationHardwareMetrics hardwareMetrics;
    private AgentHttpServer server;
    private BluetoothRecoveryServer bluetooth;
    private MediaSession session;
    private StationScheduleRuntime schedules;
    private StationScheduleDisplay scheduleDisplay;
    private volatile boolean prepared;
    private volatile boolean playing;
    private volatile String title = "";
    private volatile String artist = "";
    private volatile String trackUrl = "";
    private volatile int volumeBeforeMute = 30;
    private volatile String musicError = "";
    private String activeMusicStream = "";
    private final AgentMediaQueue mediaQueue = new AgentMediaQueue();
    private final AgentMediaDispatch.Dispatcher mediaThread = new AgentHandlerDispatcher(main);

    @Override public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        equalizer = AndroidAgentEqualizer.create(this);
        nativePlayer = new NativeMusicPlayer(main, new NativeMusicPlayer.Listener() {
            @Override public void onPrepared(NativeMusicPlayer.Snapshot state) { nativeState(state); }
            @Override public void onPlaying(NativeMusicPlayer.Snapshot state) { nativeState(state); }
            @Override public void onPaused(NativeMusicPlayer.Snapshot state) { nativeState(state); }
            @Override public void onStopped(NativeMusicPlayer.Snapshot state) { nativeState(state); }
            @Override public void onProgress(NativeMusicPlayer.Snapshot state) { nativeState(state); }
            @Override public void onFinished(NativeMusicPlayer.Snapshot state) {
                if (!nativeState(state)) return;
                // Keep completion and queue advancement in one main-thread operation.
                Track selected = mediaQueue.hasNext() ? mediaQueue.relative(1) : null;
                if (selected != null) startPlayback(selected);
            }
            @Override public void onError(String reason) {
                if (activeMusicStream.isEmpty()) return;
                musicError = reason; playing = false; prepared = false; updateNotification();
                if (session != null) session.setActive(false);
            }
        });
        displaySettings = StationDisplaySettings.create();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Station Agent",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(NOTIFICATION_ID, notification("http://192.168.77.100:18080"));
        createMediaSession();
        schedules = new StationScheduleRuntime(this, this);
        hardwareMetrics = new StationHardwareMetrics(this);
        server = new AgentHttpServer(this, this);
        server.start();
        scheduleDisplay = new StationScheduleDisplay(schedules);
        // Native config, push and music now belong to the Station-local services.
        // The retired global-proxy PoC must not reopen its legacy PC routes.
        bluetooth = new BluetoothRecoveryServer(this);
        bluetooth.start();
    }

    private void createMediaSession() {
        try {
            session = new MediaSession(this, "StationAgent");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new AgentMediaCallback(this));
            session.setActive(false);
            updateSession();
        } catch (Exception error) {
            session = null;
        }
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Station Agent")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("http://192.168.77.100:18080"));
        if (schedules != null) schedules.onEvent(intent);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (displaySettings != null) displaySettings.close();
        if (scheduleDisplay != null) scheduleDisplay.destroy();
        if (server != null) server.stop();
        if (bluetooth != null) bluetooth.stop();
        if (schedules != null) schedules.destroy();
        releasePlayer();
        if (nativePlayer != null) nativePlayer.release();
        if (session != null) {
            try { session.setActive(false); session.release(); } catch (Exception ignored) { }
            session = null;
        }
        super.onDestroy();
    }

    public JSONObject status() {
        JSONObject value = new JSONObject();
        try {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            value.put("playing", playing);
            value.put("prepared", prepared);
            value.put("version", LocalDiagnostics.VERSION);
            value.put("title", title);
            value.put("artist", artist);
            value.put("url", trackUrl);
            value.put("volume", max <= 0 ? 0 : Math.round(100f * current / max));
            value.put("maxVolume", max);
            value.put("durationMs", safeDuration());
            value.put("positionMs", safePosition());
            value.put("musicRuntime", musicRuntime());
            mediaQueue.putStatus(value);
            JSONObject scheduleStatus = schedules == null ? new JSONObject().put("ready", false) : schedules.status();
            value.put("scheduleRuntime", scheduleStatus);
            value.put("scheduleRevision", scheduleStatus.optString("revision", ""));
            value.put("scheduleDisplay", scheduleDisplay == null ? new JSONObject() : scheduleDisplay.status());
        } catch (Exception error) {
            try { value.put("error", error.getClass().getSimpleName()); } catch (Exception ignored) { }
        }
        return value;
    }

    private int safeDuration() {
        try { return (int) Math.min(Integer.MAX_VALUE, nativePlayer.getDuration()); } catch (Exception ignored) { return 0; }
    }

    private int safePosition() {
        try { return (int) Math.min(Integer.MAX_VALUE, nativePlayer.getCurrentPosition()); } catch (Exception ignored) { return 0; }
    }

    private boolean nativeState(NativeMusicPlayer.Snapshot state) {
        if (activeMusicStream.isEmpty() || !activeMusicStream.equals(state.streamId)) return false;
        playing = state.state == NativeMusicPlayer.PLAYING;
        prepared = state.prepared && state.state != NativeMusicPlayer.STOPPED
                && state.state != NativeMusicPlayer.FINISHED && state.state != NativeMusicPlayer.FAILED;
        musicError = "";
        if (session != null) session.setActive(playing || prepared);
        updateNotification();
        return true;
    }

    JSONObject musicRuntime() throws Exception {
        NativeMusicPlayer.Snapshot value = nativePlayer == null ? null : nativePlayer.getSnapshot();
        int state = value == null ? NativeMusicPlayer.IDLE : value.state;
        String name = !musicError.isEmpty() ? "error" : state == NativeMusicPlayer.PLAYING ? "playing"
                : state == NativeMusicPlayer.PAUSED ? "paused" : state == NativeMusicPlayer.BUFFERING ? "loading"
                : state == NativeMusicPlayer.FAILED ? "error" : state == NativeMusicPlayer.STOPPED
                || state == NativeMusicPlayer.FINISHED ? "stopped"
                : value != null && !value.url.isEmpty() ? "loading" : "idle";
        return new JSONObject().put("backend", "native").put("state", name)
                .put("nativeConfirmed", musicError.isEmpty() && value != null
                        && state != NativeMusicPlayer.IDLE && state != NativeMusicPlayer.FAILED)
                .put("equalizerSupported", false);
    }

    public void play(final String url, final String newTitle, final String newArtist) {
        main.post(() -> startPlayback(mediaQueue.play(url, newTitle, newArtist)));
    }

    private void startPlayback(Track track) {
        releasePlayer();
        title = track.title;
        artist = track.artist;
        trackUrl = track.url;
        prepared = false;
        playing = false;
        musicError = "";
        if (session != null) session.setActive(true);
        try {
            nativePlayer.play(track.url, track.title, track.artist, Math.max(0L, track.duration) * 1000L);
            activeMusicStream = nativePlayer.getSnapshot().streamId;
            updateNotification();
        } catch (Exception error) {
            playing = false;
            prepared = false;
            musicError = "native_start_failed";
            updateNotification();
        }
    }

    public void pause() {
        main.post(() -> {
            if (nativePlayer != null && !activeMusicStream.isEmpty()) nativePlayer.pause();
        });
    }

    /** ScheduleSignal invokes this on the player thread before its offline alarm starts. */
    @Override public void pauseMusic() {
        if (Looper.myLooper() != main.getLooper()) { main.post(this::pauseMusic); return; }
        if (nativePlayer != null && !activeMusicStream.isEmpty()) nativePlayer.pause();
    }

    JSONObject scheduleCommand(JSONObject request) throws Exception {
        if (schedules == null) return new JSONObject().put("ok", false).put("code", "schedule_unavailable")
                .put("error", "Часы Станции пока недоступны");
        return schedules.execute(request);
    }

    JSONObject displayCommand(JSONObject request) throws Exception {
        if (displaySettings == null) return StationDisplaySettings.unavailable("display_unavailable");
        return displaySettings.execute(request).put("musicRuntime", musicRuntime());
    }

    JSONObject equalizerCommand(JSONObject request) throws Exception {
        JSONObject result = mediaCommand(() -> {
            if (equalizer == null) return new JSONObject().put("ok", false).put("code", "equalizer_unavailable");
            // Keep saved Android presets, but never claim that they affect the native client.
            JSONObject eqState = equalizer.execute(new JSONObject().put("op", "get"), 0);
            boolean get = request != null && request.length() == 1 && "get".equals(request.opt("op"));
            eqState.put("ok", get).put("available", false).put("attached", false).put("effectiveEnabled", false)
                    .put("code", "native_player_eq_unsupported");
            if (!get) eqState.put("error", "Эквалайзер пока не поддерживается штатным плеером");
            return eqState;
        });
        // Preserve a predictable shape for admission failures as well as hardware failures.
        if (!result.has("scope")) result.put("available", false).put("scope", "agent_player")
                .put("enabled", false).put("attached", false).put("minLevelMb", 0)
                .put("maxLevelMb", 0).put("bands", new JSONArray());
        return result;
    }

    @Override public void showScheduleSignal() {
        // Timers use native alarmd display/sound; Agent audio here is for alarm-clock entries.
    }

    @Override public boolean useNativeTimers() { return true; }

    void scheduleDisplayAssetServed() { if (scheduleDisplay != null) scheduleDisplay.served(); }
    byte[] scheduleTimerGif() { return scheduleDisplay == null ? new byte[0] : scheduleDisplay.timerGif(); }


    public void resume() {
        main.post(() -> {
            if (nativePlayer != null && !activeMusicStream.isEmpty()) {
                if (session != null) session.setActive(true);
                nativePlayer.resume();
            }
        });
    }

    public void stop() {
        main.post(this::releasePlayer);
    }

    public void next() {
        main.post(() -> {
            Track selected = mediaQueue.relative(1);
            if (selected != null) startPlayback(selected);
        });
    }

    public void previous() {
        main.post(() -> {
            Track selected = mediaQueue.relative(-1);
            if (selected != null) startPlayback(selected);
        });
    }

    public void seekTo(int position) {
        main.post(() -> {
            if (nativePlayer != null && prepared) {
                try {
                    nativePlayer.seekTo(Math.max(0, position));
                    updateNotification();
                } catch (Exception ignored) { }
            }
        });
    }

    @Override public JSONObject queue() throws Exception {
        return mediaQueue.snapshot();
    }

    JSONObject bluetoothStatus() throws Exception {
        return mediaCommand(this::status);
    }

    @Override public JSONObject queuePlay(final String revision, final int index) throws Exception {
        return mediaCommand(() -> {
            Track selected = mediaQueue.select(revision, index);
            // Selection is a new explicit play request; it also works after STOP.
            startPlayback(selected);
            JSONObject result = new JSONObject().put("ok", true).put("accepted", true).put("target", "agent_player");
            mediaQueue.putStatus(result);
            return result;
        });
    }

    @Override public JSONObject seek(final String revision, final int positionMs) throws Exception {
        return mediaCommand(() -> {
            int position = mediaQueue.seekPosition(revision, positionMs, nativePlayer != null && prepared, safeDuration());
            nativePlayer.seekTo(position);
            updateNotification();
            return new JSONObject().put("ok", true).put("accepted", true).put("target", "agent_player")
                    .put("positionMs", position).put("queueRevision", mediaQueue.revision());
        });
    }

    private JSONObject mediaCommand(AgentMediaDispatch.Operation operation) throws Exception {
        return AgentMediaDispatch.call(mediaThread, operation, 1500);
    }

    private void releasePlayer() {
        activeMusicStream = "";
        if (equalizer != null) equalizer.detach();
        if (nativePlayer != null) nativePlayer.stop();
        playing = false;
        prepared = false;
        if (session != null) {
            try { session.setActive(false); } catch (Exception ignored) { }
        }
        updateNotification();
    }

    public int setVolumePercent(int percent) {
        int target = Math.max(0, Math.min(100, percent));
        int current = currentVolume();
        if (target == 0) {
            if (NativeCommands.send("sound_mute")) {
                sleep(350);
                return currentVolume();
            }
        } else if (current == 0 && NativeCommands.send("sound_unmute")) {
            sleep(350);
            current = currentVolume();
        }
        int guard = 0;
        while (current < target - 6 && guard++ < 12 && NativeCommands.send("sound_louder")) {
            sleep(450);
            current = currentVolume();
        }
        guard = 0;
        while (current > target + 6 && guard++ < 12 && NativeCommands.send("sound_quiter")) {
            sleep(450);
            current = currentVolume();
        }
        if (current < target - 6 || current > target + 6) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * target / 100f), 0);
            current = currentVolume();
        }
        return current;
    }

    public int adjustVolume(int delta) {
        if (NativeCommands.send(delta >= 0 ? "sound_louder" : "sound_quiter")) {
            sleep(250);
            return currentVolume();
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                delta >= 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        return currentVolume();
    }

    public int mute(boolean on) {
        if (NativeCommands.send(on ? "sound_mute" : "sound_unmute")) {
            sleep(250);
            return currentVolume();
        }
        if (on) {
            int current = currentVolume();
            if (current > 0) volumeBeforeMute = current;
            return setVolumePercent(0);
        }
        return setVolumePercent(volumeBeforeMute > 0 ? volumeBeforeMute : 30);
    }

    private int currentVolume() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0 : Math.round(100f * current / max);
    }

    private static void sleep(int millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    public String search(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            String body = requestJson("http://127.0.0.1:17686/api/search?q=" + encoded);
            setQueueFromJson(body);
            return body;
        } catch (Exception error) {
            return "{\"error\":\"" + error.getClass().getSimpleName() + "\"}";
        }
    }

    public String library() {
        String body = requestJson("http://127.0.0.1:17686/api/library");
        setQueueFromJson(body);
        return body;
    }

    public String playlists() {
        return requestJson("http://127.0.0.1:17686/api/playlists");
    }

    public String playlist(String ownerId, String playlistId) {
        try {
            String owner = URLEncoder.encode(ownerId, "UTF-8");
            String list = URLEncoder.encode(playlistId, "UTF-8");
            String body = requestJson("http://127.0.0.1:17686/api/playlist?owner_id=" + owner + "&id=" + list);
            setQueueFromJson(body);
            return body;
        } catch (Exception error) {
            return "{\"error\":\"" + error.getClass().getSimpleName() + "\"}";
        }
    }

    private void setQueueFromJson(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray tracks = root.optJSONArray("tracks");
            if (tracks == null) return;
            List<Track> replacement = new ArrayList<>();
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject item = tracks.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "");
                if (!AgentMediaQueue.playbackUrl(url)) url = "";
                replacement.add(new Track(url, item.optString("title", ""),
                        item.optString("artist", ""), item.optInt("duration", 0)));
            }
            main.post(() -> {
                mediaQueue.replace(replacement);
                updateSession();
            });
        } catch (Exception ignored) { }
    }

    public String apps() {
        try {
            JSONArray result = new JSONArray();
            PackageManager manager = getPackageManager();
            for (ResolveInfo info : launchableActivities(manager)) {
                JSONObject item = new JSONObject();
                item.put("package", info.activityInfo.packageName);
                item.put("activity", info.activityInfo.name);
                item.put("label", info.loadLabel(manager).toString());
                result.put(item);
            }
            return new JSONObject().put("apps", result).toString();
        } catch (Exception error) {
            return "{\"error\":\"" + error.getClass().getSimpleName() + "\"}";
        }
    }

    public String tvKey(String code) {
        return StationInputClient.key(code).toString();
    }

    public String tvStatus() {
        try {
            return new JSONObject().put("ok", true).put("scope", "station_local")
                    .put("station", new JSONObject().put("connected", true).put("foreground", JSONObject.NULL))
                    .put("agent", LocalDiagnostics.agent(status())).put("input", StationInputClient.status()).toString();
        } catch (Exception unavailable) { return "{\"ok\":false,\"code\":\"local_status_unavailable\"}"; }
    }

    public String localStatus() {
        try { return LocalDiagnostics.snapshot(status(), StationInputClient.status()).toString(); }
        catch (Exception unavailable) { return "{\"ok\":false,\"code\":\"local_status_unavailable\"}"; }
    }

    String hardwareStatus() {
        try { return hardwareMetrics.snapshot(); }
        catch (Exception unavailable) { return "{\"ok\":false,\"code\":\"hardware_status_unavailable\"}"; }
    }

    public JSONObject proxyStatus() {
        JSONObject value = new JSONObject();
        try {
            value.put("enabled", false);
            value.put("active", false);
            value.put("code", "retired_local_proxy");
            value.put("port", LocalProxyServer.PORT);
            value.put("connections", 0);
            value.put("checkUpdatesHits", 0);
            value.put("localResponses", 0);
            value.put("lastEvent", "");
        } catch (Exception ignored) { }
        return value;
    }

    public String vpnStart() {
        return "{\"ok\":false,\"error\":\"VPN test disabled: it can cut ADB/Agent on this TV\"}";
    }

    public String vpnStop() {
        try {
            stopService(new Intent(this, StationVpnService.class));
            stopService(new Intent(this, DnsGuardVpnService.class));
            return "{\"ok\":true,\"state\":\"stopping\"}";
        } catch (Exception error) {
            return "{\"ok\":false,\"error\":\"" + error.getClass().getSimpleName() + "\"}";
        }
    }

    public JSONObject dnsGuardStatus() {
        JSONObject value = new JSONObject();
        try {
            value.put("active", DnsGuardVpnService.isActive());
            value.put("startedAt", DnsGuardVpnService.startedAt());
            value.put("queries", DnsGuardVpnService.queries());
            value.put("blocked", DnsGuardVpnService.blocked());
            value.put("pinned", DnsGuardVpnService.pinned());
            value.put("forwarded", DnsGuardVpnService.forwarded());
            value.put("errors", DnsGuardVpnService.errors());
            value.put("lastEvent", DnsGuardVpnService.lastEvent());
        } catch (Exception ignored) { }
        return value;
    }

    public String dnsGuardStart() {
        try {
            Intent intent = new Intent(this, VpnConsentActivity.class);
            intent.putExtra("start", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"ok\":true,\"state\":\"consent\"}";
        } catch (Exception error) {
            return "{\"ok\":false,\"error\":\"" + error.getClass().getSimpleName() + "\"}";
        }
    }

    public String dnsGuardStop() {
        stopService(new Intent(this, DnsGuardVpnService.class));
        return "{\"ok\":true,\"state\":\"stopping\"}";
    }

    public JSONObject vpnStatus() {
        JSONObject value = new JSONObject();
        try {
            value.put("active", StationVpnService.isActive());
            value.put("startedAt", StationVpnService.startedAt());
            value.put("packetsDropped", StationVpnService.packetsDropped());
        } catch (Exception ignored) { }
        return value;
    }

    void stopVpnFromBluetooth() {
        stopService(new Intent(this, StationVpnService.class));
        stopService(new Intent(this, DnsGuardVpnService.class));
    }

    void goHomeFromBluetooth() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }

    void startAgentFromBluetooth() {
        startService(new Intent(this, AgentService.class));
    }

    public String launch(String packageName, String activityName) {        try {
            if (packageName == null || activityName == null) {
                return "{\"ok\":false,\"error\":\"package and activity are required\"}";
            }
            ComponentName target = new ComponentName(packageName, activityName);
            boolean allowed = false;
            for (ResolveInfo info : launchableActivities(getPackageManager())) {
                if (info.activityInfo.packageName.equals(packageName)
                        && info.activityInfo.name.equals(activityName)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return "{\"ok\":false,\"error\":\"not a launchable app\"}";
            }
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.setComponent(target);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return "{\"ok\":true}";
        } catch (Exception error) {
            return "{\"ok\":false,\"error\":\"" + error.getClass().getSimpleName() + "\"}";
        }
    }

    private List<ResolveInfo> launchableActivities(PackageManager manager) {
        List<ResolveInfo> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        addLaunchables(manager, intent, all, seen);
        intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        addLaunchables(manager, intent, all, seen);
        Collections.sort(all, Comparator.comparing(
                info -> info.loadLabel(manager).toString().toLowerCase()));
        return all;
    }

    private void addLaunchables(PackageManager manager, Intent intent,
                                List<ResolveInfo> all, Set<String> seen) {
        for (ResolveInfo info : manager.queryIntentActivities(intent, 0)) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) {
                continue;
            }
            String key = info.activityInfo.packageName + "/" + info.activityInfo.name;
            if (seen.add(key)) all.add(info);
        }
    }

    private String requestJson(String address) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return "{\"error\":\"PC backend returned " + code + "\"}";
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            reader.close();
            return text.toString();
        } catch (Exception error) {
            return "{\"error\":\"" + error.getClass().getSimpleName() + "\"}";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void updateSession() {
        if (session == null) return;
        try {
            int state = playing ? PlaybackState.STATE_PLAYING
                    : prepared ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_NONE;
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                    | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            PlaybackState.Builder playback = new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(state, safePosition(), playing ? 1f : 0f);
            session.setPlaybackState(playback.build());
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, safeDuration()));
            session.setMetadata(metadata.build());
        } catch (Exception ignored) { }
    }

    private void updateNotification() {
        String text = playing ? "Играет: " + artist + " — " + title
                : prepared ? "Пауза: " + artist + " — " + title
                : "http://192.168.77.100:18080";
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
        updateSession();
    }
}
