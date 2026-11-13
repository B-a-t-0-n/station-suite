package local.station.agent;

import android.media.session.MediaSession;

/** Routes hardware/media-button events to the agent player. */
final class AgentMediaCallback extends MediaSession.Callback {
    private final AgentService service;

    AgentMediaCallback(AgentService service) {
        this.service = service;
    }

    @Override public void onPlay() { service.resume(); }
    @Override public void onPause() { service.pause(); }
    @Override public void onStop() { service.stop(); }
    @Override public void onSkipToNext() { service.next(); }
    @Override public void onSkipToPrevious() { service.previous(); }
    @Override public void onSeekTo(long position) { service.seekTo((int) position); }
}
