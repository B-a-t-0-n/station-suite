#!/system/bin/sh
# Experimental fixed HIDL dimmer probe. No su, permissions changes or raw sysfs IO.
# Run as a separate script under the existing Quasar application UID.
PATH=/system/bin:/system/xbin
export PATH
umask 077
set -f

APP_DIR=/data/user/0/ru.yandex.quasar.app/files/station-ring-dimmer
DEX=/data/user/0/ru.yandex.quasar.app/files/station-ring-dimmer/classes.dex
CLASSPATH=/vendor/framework/droidlogic.jar:/data/user/0/ru.yandex.quasar.app/files/station-ring-dimmer/classes.dex
export CLASSPATH
LOCK=/data/user/0/ru.yandex.quasar.app/files/station-ring-dimmer/probe.lock
PARENT_PID=$$
LOCKED=0
WATCHDOG_PID=
FINISHED=0
OLD=
TOKEN=
REPLY=
RESULT=

say() { printf '%s\n' "$1"; }
fail() { say "$1"; exit 1; }
number() {
    case "$1" in ''|*[!0-9]*|0[0-9]*) return 1;; esac
    [ "${#1}" -le 3 ] && [ "$1" -le 255 ]
}
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
monotonic_ticks() {
    # priv_app cannot read /proc/uptime on this firmware. This fixed backend
    # command reads System.nanoTime only and never connects to SystemControl.
    # timeout limits a stuck clock client; it is not a hardware write lease.
    CLOCK_REPLY=$(timeout -s KILL 2 app_process /system/bin RingBrightnessBackend ticks 2>/dev/null) || {
        say CLOCK_BACKEND_FAILED >&2
        return 1
    }
    case "$CLOCK_REPLY" in 'TICKS '*) TICKS=${CLOCK_REPLY#TICKS };;
        *) say INVALID_CLOCK_REPLY >&2; return 1;; esac
    case "$TICKS" in ''|*[!0-9]*|0[0-9]*) say INVALID_CLOCK_REPLY >&2; return 1;; esac
    [ "${#TICKS}" -le 12 ] || { say INVALID_CLOCK_REPLY >&2; return 1; }
}
owned_lock() {
    [ "$LOCKED" = 1 ] && [ ! -L "$LOCK" ] &&
        [ "$(cat "$LOCK/owner" 2>/dev/null)" = "$TOKEN" ]
}
cleanup_lock() {
    owned_lock || return
    rm -f "$LOCK/ready" "$LOCK/restore" "$LOCK/done" "$LOCK/ack" \
        "$LOCK/worker" "$LOCK/backend-out" "$LOCK/owner"
    rmdir "$LOCK" 2>/dev/null || :
}
stop_worker() {
    owned_lock || return 1
    [ -f "$LOCK/worker" ] || return 0
    IFS=' ' read -r WORKER_PID WORKER_ID EXTRA < "$LOCK/worker" || return 1
    case "$WORKER_PID:$WORKER_ID" in *[!0-9:]*|:*|*:) return 1;; esac
    [ -z "$EXTRA" ] && [ "$WORKER_PID" -gt 1 ] &&
        [ "$WORKER_PID" != "$PARENT_PID" ] || return 1
    LIVE_ID=$(process_identity "$WORKER_PID") || { rm -f "$LOCK/gate.$WORKER_PID"; return 0; }
    [ "$LIVE_ID" = "$WORKER_ID" ] || { rm -f "$LOCK/gate.$WORKER_PID"; return 0; }
    kill -KILL "$WORKER_PID" 2>/dev/null || :
    STOP_CHECKS=0
    while :; do
        LIVE_ID=$(process_identity "$WORKER_PID") || { rm -f "$LOCK/gate.$WORKER_PID"; return 0; }
        [ "$LIVE_ID" = "$WORKER_ID" ] || { rm -f "$LOCK/gate.$WORKER_PID"; return 0; }
        [ "$STOP_CHECKS" -lt 40 ] || return 1
        sleep 0.01
        STOP_CHECKS=$((STOP_CHECKS + 1))
    done
}
run_backend() {
    owned_lock || return 1
    IFS=' ' read -r RUNNER_STAT < /proc/self/stat || return 1
    RUNNER_PID=${RUNNER_STAT%% *}
    RUNNER_ID=$(process_identity "$RUNNER_PID") || return 1
    rm -f "$LOCK/worker" "$LOCK/backend-out"
    # The unregistered child can only wait. It cannot issue a HIDL call until
    # the parent records PID+starttime, then creates the gate. exec keeps PID.
    (
        trap '' HUP INT TERM
        trap - 0
        IFS=' ' read -r SELF_STAT < /proc/self/stat || exit 1
        CHILD_REAL_PID=${SELF_STAT%% *}
        case "$CHILD_REAL_PID" in ''|*[!0-9]*) exit 1;; esac
        CHILD_GATE=$LOCK/gate.$CHILD_REAL_PID
        GATE_CHECKS=0
        while [ ! -f "$CHILD_GATE" ]; do
            owned_lock || exit 1
            GATE_OWNER_ID=$(process_identity "$RUNNER_PID") || exit 1
            [ "$GATE_OWNER_ID" = "$RUNNER_ID" ] || exit 1
            [ "$GATE_CHECKS" -lt 500 ] || exit 1
            sleep 0.01
            GATE_CHECKS=$((GATE_CHECKS + 1))
        done
        owned_lock || exit 1
        exec app_process /system/bin RingBrightnessBackend "$@"
    ) > "$LOCK/backend-out" 2>/dev/null &
    BACKEND_PID=$!
    BACKEND_ID=$(process_identity "$BACKEND_PID") || {
        kill -KILL "$BACKEND_PID" 2>/dev/null || :
        return 1
    }
    printf '%s %s\n' "$BACKEND_PID" "$BACKEND_ID" > "$LOCK/worker" || {
        kill -KILL "$BACKEND_PID" 2>/dev/null || :
        return 1
    }
    : > "$LOCK/gate.$BACKEND_PID" || { stop_worker; return 1; }
    wait "$BACKEND_PID"
    BACKEND_RESULT=$?
    REPLY=$(cat "$LOCK/backend-out" 2>/dev/null)
    rm -f "$LOCK/worker" "$LOCK/gate.$BACKEND_PID"
    return "$BACKEND_RESULT"
}
restore_value() {
    stop_worker || { RESULT=WORKER_STILL_ALIVE; return 1; }
    run_backend restore "$OLD"
    RESTORE_RESULT=$?
    case "$REPLY" in
        RESTORED) [ "$RESTORE_RESULT" = 0 ] || { RESULT=RESTORE_FAILED; return 1; }; RESULT=RESTORED;;
        OWNER_CHANGED) RESULT=OWNER_CHANGED; return 1;;
        *) RESULT=RESTORE_UNCONFIRMED; return 1;;
    esac
}
watchdog() {
    trap '' HUP INT TERM
    trap - 0
    monotonic_ticks || exit 1
    WD_DEADLINE=$((TICKS + 600))
    printf '%s\n' "$TOKEN" > "$LOCK/ready" || exit 1
    WD_TIMEOUT=0
    while :; do
        [ -f "$LOCK/restore" ] && break
        parent_alive || break
        monotonic_ticks || { WD_TIMEOUT=1; break; }
        [ "$TICKS" -lt "$WD_DEADLINE" ] || { WD_TIMEOUT=1; break; }
        sleep 0.05
    done
    if [ "$WD_TIMEOUT" = 1 ] && parent_alive; then
        # Stop the owner before inspecting its registered child. An unregistered
        # child never received a gate, so it cannot issue a delayed dim call.
        kill -KILL "$PARENT_PID" 2>/dev/null || :
        WD_KILL_CHECKS=0
        while parent_alive; do
            [ "$WD_KILL_CHECKS" -lt 40 ] || {
                printf '%s\n' PARENT_STILL_ALIVE > "$LOCK/done"
                exit 1
            }
            sleep 0.01
            WD_KILL_CHECKS=$((WD_KILL_CHECKS + 1))
        done
    fi
    restore_value || :
    printf '%s\n' "$RESULT" > "$LOCK/done" || exit 1
    WD_ACK=0
    while parent_alive && [ ! -f "$LOCK/ack" ] && [ "$WD_ACK" -lt 50 ]; do
        sleep 0.01; WD_ACK=$((WD_ACK + 1))
    done
    cleanup_lock
    [ "$RESULT" = RESTORED ]
}
finish() {
    [ "$FINISHED" = 0 ] || return
    FINISHED=1
    if [ -n "$WATCHDOG_PID" ]; then
        owned_lock && : > "$LOCK/restore"
        FIN_WAIT=0; FIN_RESULT=
        while [ "$FIN_WAIT" -lt 300 ]; do
            if owned_lock && [ -f "$LOCK/done" ]; then
                FIN_RESULT=$(cat "$LOCK/done" 2>/dev/null)
                : > "$LOCK/ack"
                break
            fi
            kill -0 "$WATCHDOG_PID" 2>/dev/null || break
            sleep 0.02; FIN_WAIT=$((FIN_WAIT + 1))
        done
        if [ -z "$FIN_RESULT" ]; then
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
        stop_worker || :
        cleanup_lock
    fi
}

MODE=${1-status}
[ "$#" -le 1 ] || { say INVALID_ARGUMENTS; exit 64; }
case "$MODE" in status|probe) ;; *) say INVALID_ARGUMENTS; exit 64;; esac
[ "$(id -u 2>/dev/null)" = 10005 ] || fail QUASAR_UID_REQUIRED
[ "$(getprop ro.product.model 2>/dev/null)" = yandexstation_2 ] &&
    [ "$(getprop ro.product.device 2>/dev/null)" = yandexstation_2 ] || fail WRONG_MODEL
[ "$(getprop ro.build.display.id 2>/dev/null)" = PPR1.180610.011.20221212 ] || fail WRONG_FIRMWARE
[ ! -L "$APP_DIR" ] && [ ! -L "$DEX" ] && [ -f "$DEX" ] || fail UNSAFE_BACKEND
case "$(stat -c '%u:%a' "$APP_DIR" 2>/dev/null)" in
    10005:700|10005:755|10005:711) ;; *) fail UNSAFE_LOCK_PARENT;;
esac
case "$(stat -c '%u:%a' "$DEX" 2>/dev/null)" in
    10005:600|10005:644) ;; *) fail UNSAFE_BACKEND;;
esac
if [ "$MODE" = status ]; then
    # The backend current operation has no writer path and checks name/range.
    exec app_process /system/bin RingBrightnessBackend current
fi
[ "${0##*/}" = hidl-ring-dimmer.sh ] || fail EXECUTE_AS_SEPARATE_SCRIPT
mkdir "$LOCK" 2>/dev/null || fail BUSY_OR_STALE_LOCK
LOCKED=1
[ "$(stat -c '%u:%a' "$LOCK" 2>/dev/null)" = 10005:700 ] || {
    rmdir "$LOCK" 2>/dev/null || :; LOCKED=0; fail UNSAFE_LOCK;
}
PARENT_ID=$(process_identity "$PARENT_PID") || {
    rmdir "$LOCK" 2>/dev/null || :; LOCKED=0; fail PROCESS_IDENTITY_UNAVAILABLE;
}
monotonic_ticks || { rmdir "$LOCK" 2>/dev/null || :; LOCKED=0; fail MONOTONIC_CLOCK_UNAVAILABLE; }
TOKEN=$PARENT_PID:$PARENT_ID
printf '%s\n' "$TOKEN" > "$LOCK/owner" || fail LOCK_SETUP_FAILED
trap 'finish; exit 129' HUP
trap 'finish; exit 130' INT
trap 'finish; exit 143' TERM
trap 'finish' 0
run_backend current || fail BACKEND_PREFLIGHT_FAILED
case "$REPLY" in 'CURRENT '*) OLD=${REPLY#CURRENT };; *) fail INVALID_BACKEND_REPLY;; esac
number "$OLD" && [ "$OLD" -ge 64 ] || fail ALREADY_DIM_OR_INVALID
TARGET=$((OLD * 3 / 4))
watchdog &
WATCHDOG_PID=$!
HANDSHAKE=0
while [ "$HANDSHAKE" -lt 100 ]; do
    [ "$(cat "$LOCK/ready" 2>/dev/null)" = "$TOKEN" ] && break
    kill -0 "$WATCHDOG_PID" 2>/dev/null || fail WATCHDOG_START_FAILED
    sleep 0.01; HANDSHAKE=$((HANDSHAKE + 1))
done
[ "$HANDSHAKE" -lt 100 ] || fail WATCHDOG_START_TIMEOUT
run_backend dim "$OLD" || fail DIM_FAILED
[ "$REPLY" = "DIMMED $OLD $TARGET" ] || fail INVALID_BACKEND_REPLY
say DIMMED
sleep 0.75
finish || exit 1
trap - 0 HUP INT TERM
exit 0
