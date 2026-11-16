# Passive native state diagnostic

Receive-only diagnostic for Station Max firmware **20221212**. It observes native
loopback port `9910` through an already authorized host forward. It does not create
ADB forwards, publish IPC, change a setting, open a framebuffer, or grant RGB use.

[Device observations and remaining RGB limits](../../docs/research/ring-rgb.md).

The fixed profile is derived from the embedded descriptors of `maind` SHA256
`08a0050f4ad26e1054321c46d2e3631a6b02805e5e0355c10cbcbf53ea82ea6d`.
Selecting this profile declares the expected firmware; it does not independently
verify the connected device. Other firmware profiles are deliberately absent.

## Run

Use the port of an existing host forward to the Station's native `127.0.0.1:9910`:

```powershell
python experimental/ring-state/ring_state.py --profile station-max-20221212 --port HOST_PORT --seconds 3
```

The socket destination is always host loopback. The monitor only calls `recv` and
`settimeout`; zero native protocol bytes are sent. Each frame is little-endian
32-bit length-prefixed protobuf. Limits: 64 KiB/frame, 256 KiB total, 128 frames,
maximum 30 seconds. Malformed lengths, wire types and duplicate selected singular
fields fail closed. No native payloads are saved or printed. Output contains only
whitelisted states, counts, field names and local arrival age.

## Exact selected paths

`QuasarMessage.io_event(85).device_state(34)`:

| DeviceState path | Type / accepted values |
| --- | --- |
| `mics_state(1).mics_state(1)` | enum: ON=0, OFF=1 |
| `alice_state(2).state(1)` | enum: IDLE=0, LISTENING=1, SPEAKING=2, BUSY=3, LONG_LISTENING_IDLE=4, SHAZAM=5, NONE=6 |
| `alarms_state(5).alarm_playing(2)` | bool |
| `alarms_state(5).timer_playing(3)` | bool |
| `configuration_state(6)` | enum: UNKNOWN_STATE=0, CONFIGURING=1, CONFIGURED=2, DATA_RESET_WAIT_CONFIRM=3, DATA_RESET_IN_PROGRESS=4 |
| `update_state(7).state(1)` | enum: NONE=1, DOWNLOADING=2, APPLYING=3 |
| `reminder_state(10).reminder_playing(1)` | bool |
| `notification_state(12)` | enum: NONE=0, AVAILABLE=1, PASSIVE=2 |
| `calld_state(13).status(3)` | enum: NEW=0, DIALING=1, RINGING=2, ACCEPTING=3, CONNECTING=4, CONNECTED=5, ENDED=6, NOCALL=7 |

Optional field absence is **unknown**, including an empty nested message. Protobuf
defaults are not observed state. Unknown enum values are unknown. Each DeviceState
stands alone: a later partial message never inherits values from an earlier one.
Recognized speech, VINS answers, URLs, music descriptors, network names, account
identifiers and caller data are excluded.

`quietCandidate` means only that all selected fields were explicit and quiet in
this particular received message. It is not a rendering decision. Both top-level
and nested `renderPermit` remain **false**, even for complete quiet state.
`nativeOwnershipVerified` also remains false. The native arbiter has additional
states and can change immediately after an observation; LED ownership, RGB access
and hardware restoration have not been established by reading this socket.

There is no inspected native timestamp/version in DeviceState. `arrivalAgeMs`
measures only elapsed host time since receipt. An open socket, traffic from other
topics, repeated data or a recent packet cannot prove the snapshot is current.

## Tests

```powershell
python -m unittest discover -s experimental/ring-state/tests -p 'test_*.py' -v
```

Synthetic tests exercise the actual decoder, fragmented frames, message limits,
unknown/present/absent states, partial snapshots, enum changes, malformed input,
privacy filtering and a receive-only fake socket without any send/write API.
They do not replace live observation or establish safe native ownership.
