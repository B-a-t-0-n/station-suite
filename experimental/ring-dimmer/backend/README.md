# Fixed-path HIDL backend

Build from the repository root with an existing Java toolchain and the Android
parts prepared by the suite. This command only creates DEX on the computer:

```powershell
python experimental/ring-dimmer/backend/build.py `
  --java-home C:/path/to/jdk `
  --javac-home C:/path/to/jdk8 `
  --android-parts C:/path/to/sdk-parts
```

Output: `experimental/ring-dimmer/build/backend/dex/classes.dex`. No vendor
JAR is compiled into it. At runtime it loads the installed
`/vendor/framework/droidlogic.jar` through the fixed classpath in the supervisor.
The exact firmware and existing Quasar application access are required; see the
[experiment's prerequisites and recovery limits](../README.md).

The protocol is deliberately small:

| Command | Output | Device operation |
| --- | --- | --- |
| `current` | `CURRENT n` | Read exact ring name, maximum and brightness |
| `ticks` | `TICKS n` | Read Android's monotonic clock, in centiseconds; no HIDL |
| `dim OLD` | `DIMMED OLD TARGET` | Verify current, write 75% of OLD, verify readback |
| `restore OLD` | `RESTORED` | Restore only if current still equals TARGET, then read back |

Errors return a fixed diagnostic and exit code 1. Arguments cannot select a
different path, colour or arbitrary brightness. Directly calling write commands
bypasses the shell supervisor; they are internal components of this diagnostic
experiment and are not a supported application API.

The backend tests compile and execute this actual Java implementation with a
fake HIDL service on the host. Set `RING_TEST_JAVAC` and `RING_TEST_JAVA` to tool
executables if `JAVA_HOME`/`PATH` do not select the desired JDK:

```powershell
python -m unittest discover -s experimental/ring-dimmer/tests -p test_backend.py -v
```
