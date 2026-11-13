package local.station.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executes production StationScheduleDisplay against bounded fake native/runtime peers. */
public final class ScheduleDisplayHostTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static JSONObject screen(String token, boolean timer, boolean ringing) throws Exception {
        JSONArray timers = new JSONArray();
        if (timer) timers.put(new JSONObject().put("id","timer-id").put("enabled",!ringing).put("ringing",ringing).put("remainingMs",ringing?0:10000));
        return new JSONObject().put("ready",true).put("nativeStopToken",token).put("timers",timers);
    }
    public static void main(String[] ignored) throws Exception {
        StationScheduleRuntime runtime = new StationScheduleRuntime();
        StationScheduleDisplay display = new StationScheduleDisplay(runtime,false);
        try {
            runtime.screen = screen("",true,false); display.run();
            check(NativeTimerDisplay.stops == 0 && NativeTimerDisplay.shows == 1,"pending timer starts with no stop");
            // Native rang and voice stopped between display polls; no prior ringing screen.
            runtime.screen = screen("stop-a",false,false); NativeTimerDisplay.failStop = true; display.run();
            check(NativeTimerDisplay.stops == 1 && runtime.acks == 0,"failed native send never consumes intent");
            check("transport_unavailable".equals(display.status().getString("code")),"failed send fixed diagnostic");
            NativeTimerDisplay.failStop = false; NativeTimerDisplay.failClear = true; display.run();
            check(NativeTimerDisplay.stops == 2 && runtime.acks == 1 && runtime.screen.getString("nativeStopToken").isEmpty(),"unobserved ringing sends and acks durable stop");
            display.run();check(NativeTimerDisplay.stops == 2,"addressed removal retry cannot repeat global stop");
            NativeTimerDisplay.failClear = false; display.run();
            check(display.status().getInt("ownedTimers") == 0,"addressed cleanup eventually removes old timer");

            runtime.screen = screen("stop-b",false,false); runtime.failAck = true; display.run();
            check(NativeTimerDisplay.stops == 3 && runtime.acks == 2,"first successful delivery attempts disk ack");
            display.run();check(NativeTimerDisplay.stops == 3 && runtime.acks == 3,"failed disk ack retries without resending STOP");
            runtime.failAck = false; display.run();
            check(NativeTimerDisplay.stops == 3 && runtime.acks == 4 && runtime.screen.getString("nativeStopToken").isEmpty(),"disk ack clears token only after success");
            runtime.screen = screen("stop-c",false,false); display.run();
            check(NativeTimerDisplay.stops == 4,"new token gets one new native stop");
            display.run();check(NativeTimerDisplay.stops == 4,"consumed token does not repeat");
        } finally { display.destroy(); }

        // A durable unconsumed intent is delivered even by a newly created mirror.
        runtime.screen = screen("after-recreate",false,false);
        StationScheduleDisplay recreated = new StationScheduleDisplay(runtime,false);
        try { recreated.run();check(NativeTimerDisplay.stops == 5,"recreated display sends persisted unconsumed intent"); }
        finally { recreated.destroy(); }

        // Legacy observation fallback still stops once and survives removal retries.
        StationScheduleDisplay fallback = new StationScheduleDisplay(runtime,false);
        try {
            runtime.screen = screen("",true,true);fallback.run();
            runtime.screen = screen("",false,false);NativeTimerDisplay.failClear=true;fallback.run();fallback.run();
            check(NativeTimerDisplay.stops == 6,"observed-ring fallback sends global stop once");
            NativeTimerDisplay.failClear=false;fallback.run();
            check(fallback.status().getInt("ownedTimers")==0,"legacy fallback removes obsolete entry");
        } finally { fallback.destroy(); }
        System.out.println(checks + " display assertions passed; fake native/runtime only");
    }
}

final class StationScheduleRuntime {
    JSONObject screen;
    int acks; boolean failAck;
    JSONObject screenState() throws Exception { return new JSONObject(screen.toString()); }
    boolean acknowledgeNativeStop(String token) throws Exception {
        acks++; if(failAck) return false;
        if(token.equals(screen.optString("nativeStopToken"))) screen.put("nativeStopToken","");
        return true;
    }
}
final class NativeTimerDisplay {
    static final String LEGACY_ID="legacy";
    static int stops, shows; static boolean failStop, failClear;
    static Map<String,Long> ownedTimers(){return new LinkedHashMap<>();}
    static String ownedId(String id){return "owned-"+id;}
    static boolean clear(String id){return LEGACY_ID.equals(id)||!failClear;}
    static boolean stopSignals(){stops++;return !failStop;}
    static boolean show(String id,long wall,long left){shows++;return true;}
}
