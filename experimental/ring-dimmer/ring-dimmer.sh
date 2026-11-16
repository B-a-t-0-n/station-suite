#!/system/bin/sh
# Experimental, fixed-target dimmer probe. Never sources user configuration.
# Execute as its own process; do not source this script into an interactive shell.
PATH=/system/bin:/system/xbin
export PATH
umask 077
set -f

BRIGHTNESS=/sys/class/backlight/fb5/brightness
MAX_BRIGHTNESS=/sys/class/backlight/fb5/max_brightness
FB_NAME=/sys/class/graphics/fb5/name
BACKLIGHT=/sys/class/backlight/fb5
EXPECTED_BACKLIGHT=/sys/devices/virtual/graphics/fb5/fb5
LOCK_PARENT=/dev
LOCK=/dev/.station-ring-dimmer-probe
PARENT_PID=$$
LOCKED=0
WATCHDOG_PID=
FINISHED=0
OLD=
TARGET=
TOKEN=

say() { printf '%s\n' "$1"; }
fail() { say "$1"; exit 1; }
number() {
    case "$1" in ''|*[!0-9]*|0[0-9]*) return 1;; esac
    [ "${#1}" -le 3 ] && [ "$1" -le 255 ]
}
read_brightness() {
    CURRENT=$(cat "$BRIGHTNESS" 2>/dev/null) || return 1
    number "$CURRENT"
}
# /proc identity protects the timeout kill from accidental PID reuse.
process_identity() {
    PROC_LINE=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    PROC_FIELDS=${PROC_LINE##*) }
    set -- $PROC_FIELDS
    [ "$#" -ge 20 ] || return 1
    case "$1" in Z|X) return 1;; esac
    printf '%s\n' "${20}"
}
parent_alive() {
    CHECK_ID=$(process_identity "$PARENT_PID") || return 1
    [ "$CHECK_ID" = "$PARENT_ID" ]
}
# Linux /proc/uptime uses monotonic seconds with exactly two decimal places.
# Read with the shell builtin: command-start overhead must not extend the lease.
monotonic_ticks() {
    IFS=' ' read -r UP_VALUE UP_REST < /proc/uptime || return 1
    UP_SECONDS=${UP_VALUE%%.*}
    UP_FRACTION=${UP_VALUE#*.}
    case "$UP_SECONDS:$UP_FRACTION" in *[!0-9:]*|:*|*:) return 1;; esac
    case "$UP_FRACTION" in [0-9][0-9]) ;; *) return 1;; esac
    TICKS=$((UP_SECONDS * 100 + 1$UP_FRACTION - 100))
}
owned_lock() {
    [ "$LOCKED" = 1 ] && [ ! -L "$LOCK" ] &&
        [ "$(cat "$LOCK/owner" 2>/dev/null)" = "$TOKEN" ]
}
cleanup_lock() {
    owned_lock || return
    # Fixed files only, never recursive deletion or stale-lock takeover.
    rm -f "$LOCK/ready" "$LOCK/restore" "$LOCK/done" "$LOCK/ack" "$LOCK/owner"
    rmdir "$LOCK" 2>/dev/null || :
}
restore_value() {
    owned_lock || { RESULT=LOCK_LOST; return 1; }
    read_brightness || { RESULT=RESTORE_READ_FAILED; return 1; }
    if [ "$CURRENT" = "$OLD" ]; then
        RESULT=RESTORED
        return 0
    fi
    # Do not overwrite a different privileged writer's new brightness choice.
    [ "$CURRENT" = "$TARGET" ] || { RESULT=OWNER_CHANGED; return 1; }
    printf '%s\n' "$OLD" > "$BRIGHTNESS" 2>/dev/null || {
        RESULT=RESTORE_WRITE_FAILED; return 1;
    }
    read_brightness && [ "$CURRENT" = "$OLD" ] || {
        RESULT=RESTORE_READBACK_FAILED; return 1;
    }
    RESULT=RESTORED
}
watchdog() {
    # A closed caller terminal must not cancel the independent return attempt.
    trap '' HUP INT TERM
    trap - 0
    monotonic_ticks || exit 1
    WD_DEADLINE=$((TICKS + 150))
    printf '%s\n' "$TOKEN" > "$LOCK/ready" || exit 1
    WD_TICK=0
    WD_TIMEOUT=0
    while :; do
        [ -f "$LOCK/restore" ] && break
        parent_alive || break
        monotonic_ticks || break
        [ "$TICKS" -lt "$WD_DEADLINE" ] || { WD_TIMEOUT=1; break; }
        sleep 0.05
        WD_TICK=$((WD_TICK + 1))
    done
    if [ "$WD_TIMEOUT" = 1 ] && parent_alive; then
        # Prevent a paused parent from waking after this watchdog and dimming late.
        kill -KILL "$PARENT_PID" 2>/dev/null || :
        WD_WAIT=0
        while parent_alive && [ "$WD_WAIT" -lt 20 ]; do
            sleep 0.01
            WD_WAIT=$((WD_WAIT + 1))
        done
        if parent_alive; then
            # Kernel-stuck IO cannot be made bounded by a shell supervisor.
            RESULT=PARENT_STILL_ALIVE
            printf '%s\n' "$RESULT" > "$LOCK/done"
            exit 1
        fi
    fi
    restore_value || :
    printf '%s\n' "$RESULT" > "$LOCK/done" || exit 1
    WD_ACK=0
    while parent_alive && [ ! -f "$LOCK/ack" ] && [ "$WD_ACK" -lt 50 ]; do
        sleep 0.01
        WD_ACK=$((WD_ACK + 1))
    done
    cleanup_lock
    [ "$RESULT" = RESTORED ]
}
finish() {
    [ "$FINISHED" = 0 ] || return
    FINISHED=1
    if [ -n "$WATCHDOG_PID" ]; then
        owned_lock && : > "$LOCK/restore"
        FIN_WAIT=0
        FIN_RESULT=
        while [ "$FIN_WAIT" -lt 100 ]; do
            if owned_lock && [ -f "$LOCK/done" ]; then
                FIN_RESULT=$(cat "$LOCK/done" 2>/dev/null)
                : > "$LOCK/ack"
                break
            fi
            kill -0 "$WATCHDOG_PID" 2>/dev/null || break
            sleep 0.02
            FIN_WAIT=$((FIN_WAIT + 1))
        done
        if [ -z "$FIN_RESULT" ]; then
            # If the watchdog died unexpectedly, make a final owned-value attempt.
            if ! kill -0 "$WATCHDOG_PID" 2>/dev/null; then
                restore_value || :
                FIN_RESULT=$RESULT
                cleanup_lock
            else
                FIN_RESULT=RESTORE_UNCONFIRMED
            fi
        fi
        say "$FIN_RESULT"
        [ "$FIN_RESULT" = RESTORED ]
    else
        cleanup_lock
    fi
}

MODE=${1-status}
[ "$#" -le 1 ] || { say INVALID_ARGUMENTS; exit 64; }
case "$MODE" in status|probe) ;; *) say INVALID_ARGUMENTS; exit 64;; esac
if [ "$MODE" = probe ]; then
    [ "${0##*/}" = ring-dimmer.sh ] || fail EXECUTE_AS_SEPARATE_SCRIPT
    [ "$(id -u 2>/dev/null)" = 0 ] || fail ROOT_REQUIRED
fi
[ "$(getprop ro.product.model 2>/dev/null)" = yandexstation_2 ] &&
    [ "$(getprop ro.product.device 2>/dev/null)" = yandexstation_2 ] || fail WRONG_MODEL
[ "$(getprop ro.build.display.id 2>/dev/null)" = PPR1.180610.011.20221212 ] || fail WRONG_FIRMWARE
[ "$(readlink -f "$BACKLIGHT" 2>/dev/null)" = "$EXPECTED_BACKLIGHT" ] || fail WRONG_BACKLIGHT
[ "$(cat "$FB_NAME" 2>/dev/null)" = 'YNDX LED ring' ] || fail WRONG_FRAMEBUFFER
[ "$(cat "$MAX_BRIGHTNESS" 2>/dev/null)" = 255 ] || fail WRONG_RANGE
read_brightness || fail BRIGHTNESS_UNREADABLE
if [ "$MODE" = status ]; then
    say HARDWARE_MATCH_READABLE
    exit 0
fi
[ -w "$BRIGHTNESS" ] || fail BRIGHTNESS_NOT_WRITABLE
[ "$CURRENT" -ge 64 ] || fail ALREADY_DIM
# Root-only parent prevents another app renaming our lock after mkdir.
[ ! -L "$LOCK_PARENT" ] || fail UNSAFE_LOCK_PARENT
case "$(stat -c '%u:%a' "$LOCK_PARENT" 2>/dev/null)" in
    0:755|0:711|0:700) ;; *) fail UNSAFE_LOCK_PARENT;;
esac
mkdir "$LOCK" 2>/dev/null || fail BUSY_OR_STALE_LOCK
LOCKED=1
[ "$(stat -c '%u:%a' "$LOCK" 2>/dev/null)" = 0:700 ] || {
    rmdir "$LOCK" 2>/dev/null || :
    LOCKED=0
    fail UNSAFE_LOCK
}
PARENT_ID=$(process_identity "$PARENT_PID") || {
    rmdir "$LOCK" 2>/dev/null || :
    LOCKED=0
    fail PROCESS_IDENTITY_UNAVAILABLE
}
monotonic_ticks || {
    rmdir "$LOCK" 2>/dev/null || :
    LOCKED=0
    fail MONOTONIC_CLOCK_UNAVAILABLE
}
TOKEN=$PARENT_PID:$PARENT_ID
printf '%s\n' "$TOKEN" > "$LOCK/owner" || fail LOCK_SETUP_FAILED
trap 'finish; exit 129' HUP
trap 'finish; exit 130' INT
trap 'finish; exit 143' TERM
trap 'finish' 0
# Read the original only after exclusive ownership, before the watchdog starts.
read_brightness || fail BRIGHTNESS_UNREADABLE
OLD=$CURRENT
[ "$OLD" -ge 64 ] || fail ALREADY_DIM
TARGET=$((OLD * 3 / 4))
[ "$TARGET" -gt 0 ] && [ "$TARGET" -lt "$OLD" ] || fail INVALID_TARGET
watchdog &
WATCHDOG_PID=$!
HANDSHAKE=0
while [ "$HANDSHAKE" -lt 50 ]; do
    if [ "$(cat "$LOCK/ready" 2>/dev/null)" = "$TOKEN" ]; then break; fi
    kill -0 "$WATCHDOG_PID" 2>/dev/null || fail WATCHDOG_START_FAILED
    sleep 0.01
    HANDSHAKE=$((HANDSHAKE + 1))
done
[ "$HANDSHAKE" -lt 50 ] || fail WATCHDOG_START_TIMEOUT
# The watchdog kills this process before its final restore on timeout.
read_brightness && [ "$CURRENT" = "$OLD" ] || fail OWNER_CHANGED
printf '%s\n' "$TARGET" > "$BRIGHTNESS" 2>/dev/null || fail DIM_WRITE_FAILED
read_brightness && [ "$CURRENT" = "$TARGET" ] || fail DIM_READBACK_FAILED
say DIMMED
sleep 0.75
finish || exit 1
trap - 0 HUP INT TERM
exit 0
