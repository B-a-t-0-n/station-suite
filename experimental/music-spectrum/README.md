# Output-mix spectrum diagnostic

Standalone Android 9 diagnostic for a Station Max with its existing native player.
This is not a new player, ring renderer, boot service, or microphone recorder.
The source is independently implemented; OPENSTATION code and assets are not used.

Device findings and interpretation: [спектр штатного плеера Макс](../../docs/research/music-spectrum.md).

The APK requests `RECORD_AUDIO` because Android `Visualizer` requires it to inspect
output session `0`. There is no `AudioRecord`, microphone stream, waveform capture,
network permission, raw FFT/audio file, or background restart. FFT callbacks are
reduced immediately to 24 logarithmic relative-level bands; only bounded numerical
aggregates are retained. These are not calibrated loudness measurements.

## Explicit, bounded runs

Only the exported service is callable. It is protected with
`android.permission.DUMP` (available to an authorized ADB shell); there is no
activity or launcher entry. Modes are fixed labels: `idle`, `music`, `paused`,
`voice`, `control`. All modes except `control` only inspect output audio.
Unrecognized actions/modes are refused. A repeated start never extends an active
window. The service is `START_NOT_STICKY`, has no boot receiver, and stops after
**30 seconds of elapsed time**, independently of FFT callbacks.

`control` is deliberately audible: three seconds after the Visualizer is enabled,
it generates a fixed **880 Hz, two-second** mono tone at at most **0.02 full-scale**.
It neither requests audio focus nor changes system volume. This positive control
helps distinguish an unusable analyzer from a native-player path that bypasses
Android's output mix. It is never played in any other mode.

The dedicated watchdog invalidates the run and calls `stopSelf()` before reporting
or native cleanup. Visualizer and AudioTrack cleanup run separately from the
watchdog, from each other, and from report IO. Late initialization checks the
finished state and releases its newly returned handle instead of enabling it.
This is an application-level bound: it cannot forcibly cancel a hung Android
native call. A supervising host should force-stop **this diagnostic package only**
in a `finally` block after each run, including failed runs.

## Build

Use the same installed toolchain and an existing external signer as the suite;
the builder does not download tools, install an APK, or run ADB:

```powershell
python experimental/music-spectrum/build.py `
  --java-home C:/path/to/jdk `
  --javac-home C:/path/to/jdk8 `
  --android-parts C:/path/to/sdk-parts `
  --signing-dir C:/private/signing `
  --keystore station-suite.p12 --alias station-suite
```

The signing directory must remain outside the public checkout and contain the
existing keystore and `password.txt`. The password is passed to `apksigner` through
a temporary environment variable, never a command argument or build record.
Output: `dist/station-spectrum-probe-0.1.apk`, SHA256 and a build-only record.

## Host-controlled validation

Install this separate package and grant its runtime permission explicitly:

```text
adb -s SERIAL install -r station-spectrum-probe-0.1.apk
adb -s SERIAL shell pm grant local.station.spectrum android.permission.RECORD_AUDIO
adb -s SERIAL shell am start-foreground-service -n local.station.spectrum/.SpectrumProbeService -a local.station.spectrum.START --es mode idle
```

Read the private aggregate report through this debug package:

```text
adb -s SERIAL shell run-as local.station.spectrum cat files/report-idle.json
```

Each report contains a unique `runId`, `finished`, `result`, `reason`, setup stage,
capture configuration, frame/nonzero/above-floor counts, peak, 24 mean/peak bands,
and per-second frame counts/peaks. `visualizerCreated` / `visualizerReleased` and
`toneCreated` / `toneReleased` distinguish cleanup from a setup failure. No raw
sound or FFT frames are saved. At most eight per-run JSON reports are kept under
`files/spectrum-probe/`, plus `latest.json` and the five fixed mode reports.

Do not accept a stale report from an earlier run: check a new `runId` and
`finished=true`. Wait for release flags when the corresponding resource was
created. A result `ok` means valid frames were received, not that music or a
particular player was detected. Compare `idle`, `music`, `paused`, and an
explicitly authorized `control` run before interpreting the result.

To cancel, send the protected `local.station.spectrum.STOP` action to the same
service. In the host's `finally`, use `am force-stop local.station.spectrum` to
contain late native work. No command in this document stops Quasar, changes a
player, or changes any ring setting.
