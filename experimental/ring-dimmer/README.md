# Ring dimmer experiment

This is a bounded, supervised hardware experiment for Station Max
`yandexstation_2`, firmware `PPR1.180610.011.20221212`. It is not an application
setting, music animation, persistent service, or an installer.

The exposed control changes the native ring's overall brightness; it does not
select a colour or draw an animation. Its effect may only become visible on the
driver's next framebuffer sync.

## Helpers

| Helper | Required existing access | Backend |
| --- | --- | --- |
| `hidl-ring-dimmer.sh` | Quasar UID `10005`, its private prepared directory and DEX | Fixed SystemControl HIDL calls through `RingBrightnessBackend` |
| `ring-dimmer.sh` | UID `0` | Fixed `/sys/class/backlight/fb5/brightness` |

Both default to **read-only `status`**. The only other accepted argument is
`probe`; no custom paths, brightness values, durations, or extra arguments are
accepted. Neither helper obtains privileges or changes permissions, mounts,
system services, firmware, or boot configuration.

A probe checks the exact device/firmware, ring name and maximum brightness
`255`, then rejects brightness below `64`. It saves the current value and dims
to `floor(old × 3 / 4)` for a nominal **0.75 seconds**. It never turns the ring
off or increases its initial brightness. Restoration only writes the saved value
if the current value still equals this probe's target; a different value is
reported as `OWNER_CHANGED` and is preserved.

The HIDL variant expects the existing private directory
`/data/user/0/ru.yandex.quasar.app/files/station-ring-dimmer` and a matching
`classes.dex` there. Its backend commands are `current`, `dim OLD`,
`restore OLD`, and the read-only `ticks` clock operation. Build instructions are
in [backend](backend/).

## Recovery boundaries

An exclusive private lock rejects concurrent probes and pre-existing locks.
The independent watchdog confirms readiness before the dim operation. It
requests restoration on normal completion, owner exit, or a monotonic deadline:
1.5 seconds for direct sysfs and 6 seconds for HIDL. Those deadlines start when
the watchdog becomes ready, not when a visible animation appears.

The HIDL helper gets monotonic centiseconds from a fixed `System.nanoTime`
backend command because this app UID cannot read `/proc/uptime`. Each clock
client is killed after a nominal 2 seconds if it stalls. A clock read requires
an Android runtime launch; the deadline can therefore be observed late, and
the visible dim interval includes the restore client's launch time in addition
to the 0.75-second sleep. Post-kill process checks use a bounded retry count
rather than another clock client, so clock failure cannot itself block that
part of recovery.

For HIDL each client waits for a gate before calling the backend. Its PID and
Linux process start time are registered before the gate opens. At timeout the
watchdog stops the parent, kills the registered client, verifies that the client
has exited, and then starts restoration. A child whose registration was
interrupted cannot use a later restore client's gate. Normal shell signal traps
also request restoration.

**This is not a hard hardware lease.** Scheduling delays, a hung kernel/HIDL
service, failed restore IPC, power loss, or termination of the watchdog can
prevent or delay recovery. Killing an `app_process` client cannot cancel a write
already accepted by the privileged SystemControl server; server-side calls may
run concurrently. A delayed server write can therefore outlive the client.
Readback is a sysfs confirmation, not proof of the ring's visible state.

The ownership check compares brightness values; it cannot detect another writer
that chooses the same value. The lock isolates this experiment, not the native
LED renderer. The same Quasar UID is trusted. These limits rule out presenting
this experiment as a reliable permanent ring setting.

Run any first device probe under independent supervision with post-probe
readback and a separate recovery path. A stale lock is deliberately not stolen
or deleted automatically: first establish that the original parent, watchdog,
and client are gone and inspect the brightness. Do not simply rerun a probe
after `RESTORE_UNCONFIRMED`, `WORKER_STILL_ALIVE`, or `PARENT_STILL_ALIVE`.

## Off-device tests

From the repository root on this Windows workspace:

```powershell
python -m unittest discover -s experimental/ring-dimmer/tests -p 'test_*.py' -v
```

The shell tests require the installed Git Bash at
`C:/Program Files/Git/bin/bash.exe`. They execute copies of the actual helpers
against temporary fixtures, replacing fixed paths and system commands only in
those copies. They do not call ADB, Android services, real sysfs, or a network.
They exercise normal restoration, killed/stalled owners, a hanging registered
client, an unregistered orphan, concurrent probes, ownership changes, and
fail-closed preflights. Git Bash does not reliably implement Linux `SIGSTOP`, so
the pause scenarios use deterministic sleeps injected into a test copy. Android
process and Binder behaviour still need separate supervised validation.
