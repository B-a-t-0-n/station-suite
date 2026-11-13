package local.station.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One coherent, versioned Agent queue. Public snapshots never include playback URLs. */
final class AgentMediaQueue {
    static final int MAX_SNAPSHOT_TRACKS = 100, MAX_LABEL_CHARS = 96;
    private final List<Track> tracks = new ArrayList<>();
    private String session = UUID.randomUUID().toString();
    private long revision = 1;
    private int index = -1;

    static final class Track {
        final String url, title, artist;
        final int duration;
        Track(String url, String title, String artist, int duration) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.duration = duration;
        }
    }
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }

    synchronized void replace(List<Track> replacement) {
        tracks.clear(); tracks.addAll(replacement); index = -1; changed();
    }
    synchronized Track play(String url, String title, String artist) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).url.equals(url)) { index = i; changed(); return tracks.get(i); }
        }
        Track selected = new Track(url, title, artist, 0);
        tracks.clear(); tracks.add(selected); index = 0; changed(); return selected;
    }
    synchronized Track relative(int step) {
        int next = index + step;
        while (next >= 0 && next < tracks.size() && !playbackUrl(tracks.get(next).url)) next += step < 0 ? -1 : 1;
        if (next < 0 || next >= tracks.size()) return null;
        index = next; changed(); return tracks.get(index);
    }
    synchronized boolean hasNext() {
        for (int next = Math.max(0, index + 1); index >= 0 && next < tracks.size(); next++) if (playbackUrl(tracks.get(next).url)) return true;
        return false;
    }
    synchronized Track select(String expectedRevision, int selected) throws Failure {
        checkRevision(expectedRevision);
        if (selected < 0 || selected >= tracks.size()) throw new Failure("queue_index");
        Track track = tracks.get(selected);
        if (!playbackUrl(track.url))
            throw new Failure("queue_track_unavailable");
        // Every explicit playback invalidates old seeks, even when replaying the same index.
        index = selected; changed(); return track;
    }
    synchronized int seekPosition(String expectedRevision, int requested, boolean prepared, int durationMs) throws Failure {
        checkRevision(expectedRevision);
        // A catalog lookup can replace the queue while the current player keeps playing.
        // Fresh revision + the actual prepared player are sufficient for its seek.
        if (!prepared) throw new Failure("media_unavailable");
        if (requested < 0) throw new Failure("seek_position");
        if (durationMs <= 0) throw new Failure("duration_unavailable");
        if (requested > durationMs) throw new Failure("seek_position");
        return requested;
    }
    synchronized void putStatus(JSONObject status) throws Exception {
        status.put("queueRevision", revision()).put("queueIndex", index).put("queueSize", tracks.size())
                .put("hasNext", hasNext()).put("hasPrevious", index > 0);
    }
    synchronized JSONObject snapshot() throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(tracks.size(), MAX_SNAPSHOT_TRACKS); i++) {
            Track track = tracks.get(i);
            result.put(new JSONObject().put("index", i).put("title", label(track.title)).put("artist", label(track.artist))
                    .put("durationMs", Math.max(0L, Math.min(86400L, track.duration)) * 1000L)
                    .put("available", playbackUrl(track.url)));
        }
        return new JSONObject().put("ok", true).put("revision", revision()).put("index", index)
                .put("total", tracks.size()).put("tracks", result)
                .put("truncated", tracks.size() > MAX_SNAPSHOT_TRACKS);
    }
    synchronized String revision() { return session + ":" + revision; }
    static boolean playbackUrl(String value) {
        return value != null && value.matches("http://127\\.0\\.0\\.1:17686/audio/track-[a-f0-9]{32}\\.mp3");
    }
    private void checkRevision(String expected) throws Failure {
        if (expected == null || !revision().equals(expected)) throw new Failure("queue_stale");
    }
    private void changed() {
        if (revision == Long.MAX_VALUE) { session = UUID.randomUUID().toString(); revision = 1; }
        else revision++;
    }
    static String label(String text) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < text.length() && safe.length() < MAX_LABEL_CHARS; i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c) && !Character.isSurrogate(c)
                    && Character.getType(c) != Character.FORMAT) safe.append(c);
        }
        return safe.toString();
    }
}
