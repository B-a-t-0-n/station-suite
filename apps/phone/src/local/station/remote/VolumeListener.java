package local.station.remote;
import android.widget.SeekBar;
final class VolumeListener implements SeekBar.OnSeekBarChangeListener {
    private final MainActivity activity;
    VolumeListener(MainActivity activity) { this.activity = activity; }
    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser) activity.volumePreview(progress); }
    @Override public void onStartTrackingTouch(SeekBar bar) { activity.volumeDragging(true); }
    @Override public void onStopTrackingTouch(SeekBar bar) { activity.volumeReleased(bar.getProgress()); }
}
