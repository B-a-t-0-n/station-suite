# ProbeService lifecycle host check

Run `python tools/tests/test_probe_service_lifecycle.py` from the project folder.
It compiles the unchanged production `ProbeService`, `LegacyPipelineHandler`,
`LegacyVoiceSession` and `NativeOggInput` sources with Java 8 compatibility.

The Android service/main queue, provider and transport dependencies are replaced
by deterministic host stubs. The fake provider blocks on a latch until the test
releases it; all result/configuration data stays in memory. The transport creates
no sockets. The only audio input is the public synthetic legacy reply fixture,
which passes through the real normalizer. No ADB, API, microphone, playback or
real configuration access occurs.

The 133 assertions cover stop and lease expiry while cleanup is blocked,
unexpected service destruction, completion queued just before destruction,
completion queued after destruction, rejection by another service instance,
next cloud admission after cleanup, and an old queued stop racing a new owner.
BUSY is observed by reflection, never replaced or reset by the harness.

This verifies service/handler ownership transitions against a serialized Android
main-queue model. It does not test Android's process killer, actual socket/TLS
behavior or provider cancellation; those belong to separate tests and device
observations.
