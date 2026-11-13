package local.station.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Actual persistence/clock/state model, synthetic timestamps only. */
public final class StationSchedulesHostTest {
    private static int checks;
    private static final TimeZone MOSCOW = TimeZone.getTimeZone("Europe/Moscow");
    private static final long BASE = wall(MOSCOW, 2026, 11, 16, 10, 0); // Monday
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static StationSchedules.Now now(long delta) { return clock(BASE + delta, 1000000 + delta, "boot-a", MOSCOW); }
    private static StationSchedules.Now clock(long wall, long elapsed, String boot, TimeZone zone) {
        return new StationSchedules.Now(wall, elapsed, boot, zone);
    }
    private static JSONObject request(String op) throws Exception { return new JSONObject().put("op", op).put("requestId", UUID.randomUUID().toString()); }
    private static JSONObject createTimer(long duration) throws Exception { return request("create_timer").put("durationMs", duration); }
    private static JSONObject createAlarm(int hour, int minute, int mask) throws Exception {
        return request("create_alarm").put("hour", hour).put("minute", minute).put("daysMask", mask);
    }
    private static JSONObject ok(StationSchedules model, JSONObject request, StationSchedules.Now now) throws Exception {
        JSONObject value = model.execute(request, now); check(value.getBoolean("ok"), "valid operation succeeds: " + request.getString("op")); return value;
    }
    private static JSONObject entry(StationSchedules model, String id, StationSchedules.Now now) throws Exception {
        JSONArray all = model.snapshot(now).getJSONArray("entries");
        for (int i = 0; i < all.length(); i++) if (id.equals(all.getJSONObject(i).getString("id"))) return all.getJSONObject(i);
        throw new AssertionError("synthetic schedule missing");
    }
    private static void rejected(StationSchedules model, JSONObject request, String code) throws Exception {
        String before = model.save().toString(); JSONObject result = model.execute(request, now(0));
        check(!result.getBoolean("ok") && code.equals(result.getString("code")), "fixed rejected operation code " + code);
        check(before.equals(model.save().toString()), "invalid input cannot partially mutate persisted state");
        check(!result.getString("error").contains("PRIVATE"), "no caller/exception payload in diagnostics");
    }
    public static void main(String[] args) throws Exception {
        timerAndPersistence(); clocksAndBoot(); alarms(); mutationAndDedup(); strictValidation(); stopTimers();
        System.out.println("StationSchedulesHostTest: " + checks + " assertions passed; synthetic clocks, no runtime or sound");
    }
    private static void timerAndPersistence() throws Exception {
        StationSchedules model = new StationSchedules(null);
        check(model.nextWakeAtMs(now(0)) == 0 && model.ringing().length() == 0, "empty model has no sound/deadline");
        JSONObject create = createTimer(5000).put("label", "  чай  ").put("volumePercent", 5);
        JSONObject result = ok(model, create, now(0)); String id = result.getString("id");
        check(entry(model,id,now(0)).getString("label").equals("чай"), "labels are trimmed");
        check(model.nextWakeAtMs(now(0)) == BASE + 5000, "timer next wake is exact");
        check(entry(model,id,now(2000)).getLong("remainingMs") == 3000, "remaining time is derived, not decremented by reads");
        String readState = model.save().toString(); model.snapshot(now(2000)); model.ringing(); model.nextWakeAtMs(now(2000));
        check(readState.equals(model.save().toString()), "reads do not mutate state");
        check(!model.advance(now(4999)) && model.ringing().length() == 0, "timer does not fire early");
        check(model.advance(now(5000)), "timer due transition changes state");
        check(model.ringing().length() == 1 && model.ringing().getJSONObject(0).getString("id").equals(id), "exact due occurrence rings");
        check(!entry(model,id,now(5000)).getBoolean("enabled") && entry(model,id,now(5000)).getString("outcome").equals("fired"), "one-shot completes before sound");
        check(model.nextWakeAtMs(now(5000)) == BASE + 125000, "ring expiry is included in next wake");
        check(!model.advance(now(5000)), "same occurrence cannot fire twice");
        JSONObject saved = model.save(); StationSchedules restored = new StationSchedules(new JSONObject(saved.toString()));
        check(restored.ringing().length() == 1 && !restored.advance(now(5001)), "saved firing state survives reload without duplicate transition");
        check(restored.advance(now(125000)) && restored.ringing().length() == 0
                && entry(restored,id,now(125000)).getString("outcome").equals("expired"), "ring lifetime is bounded to two minutes");
        check(restored.nextWakeAtMs(now(125000)) == 0, "completed timer does not rearm");
        rejected(restored, request("set_enabled").put("id",id).put("enabled",true), "timer_finished");
        JSONObject isolated = model.save(); isolated.getJSONArray("entries").getJSONObject(0).put("ringing",false);
        check(model.ringing().length() == 1, "persistence export is a deep detached snapshot");
        model.ringing().getJSONObject(0).put("volumePercent",80);
        check(model.ringing().getJSONObject(0).getInt("volumePercent") == 5, "ringing metadata is detached");
        check(model.dismissAll(now(6000)) && !model.dismissAll(now(6000)) && model.ringing().length() == 0, "internal dismiss is once-only without request history flood");
        check(model.save().getJSONArray("requests").length() == 1, "internal transitions do not spend request IDs");
    }
    private static void clocksAndBoot() throws Exception {
        StationSchedules timer = new StationSchedules(null); String id = ok(timer,createTimer(10000),now(0)).getString("id");
        StationSchedules.Now jumped = clock(BASE + 3600000, 1002000, "boot-a", MOSCOW);
        check(entry(timer,id,jumped).getLong("remainingMs") == 8000 && timer.nextWakeAtMs(jumped) == BASE + 3608000,
                "wall clock jump cannot complete a same-boot timer");
        check(timer.advance(jumped), "wall equivalent deadline rebased for persistence");
        StationSchedules recovered = new StationSchedules(timer.save());
        StationSchedules.Now reboot = clock(BASE + 3604000, 500, "boot-b", MOSCOW);
        check(!recovered.advance(reboot) || recovered.ringing().length() == 0, "reboot retains remaining wall interval");
        check(entry(recovered,id,reboot).getLong("remainingMs") == 4000, "reboot countdown is reconstructed from saved wall deadline");
        check(recovered.advance(clock(BASE + 3608000, 4500, "boot-b", MOSCOW)) && recovered.ringing().length()==1, "reconstructed timer fires once");
        for (long late : new long[]{0, 1, 300000, 300001, 3600000}) {
            StationSchedules model = new StationSchedules(null); id=ok(model,createTimer(5000),now(0)).getString("id");
            model = new StationSchedules(model.save());
            StationSchedules.Now after = clock(BASE+5000+late, 100, "new-boot", MOSCOW);
            check(model.advance(after), "boot reconciliation is persisted");
            boolean timely = late <= 300000;
            check(entry(model,id,after).getBoolean("ringing") == timely
                    && entry(model,id,after).getString("outcome").equals(timely?"fired":"missed"), "five-minute missed grace has exact boundary");
            check(!model.advance(after), "reconciliation is idempotent even with negative original elapsed deadline");
        }
        StationSchedules ringing = new StationSchedules(null); ok(ringing,createTimer(5000),now(0)); ringing.advance(now(5000));
        check(ringing.advance(clock(BASE+125000, 0,"new-boot",MOSCOW)) && ringing.ringing().length()==0,
                "expired persisted ring never restarts after boot");
        StationSchedules wallBack = new StationSchedules(null); ok(wallBack,createTimer(5000),now(0)); wallBack.advance(now(5000));
        check(wallBack.advance(clock(BASE,0,"new-boot",MOSCOW)) && wallBack.ringing().length()==0,
                "negative cross-boot ring age fails closed instead of ringing indefinitely");
    }
    private static void alarms() throws Exception {
        StationSchedules one = new StationSchedules(null); String id=ok(one,createAlarm(9,30,0),now(0)).getString("id");
        long tomorrow = wall(MOSCOW,2026,11,17,9,30);
        check(entry(one,id,now(0)).getLong("nextAtMs")==tomorrow, "one-shot past local time schedules tomorrow");
        StationSchedules.Now due=clock(tomorrow,1000,"boot-b",MOSCOW);
        check(one.advance(due) && one.ringing().length()==1 && !entry(one,id,due).getBoolean("enabled"), "one-shot alarm disables before signal");
        one.dismissAll(due); check(!one.advance(clock(tomorrow+1,1001,"boot-b",MOSCOW)), "dismissed one-shot cannot resurrect");
        StationSchedules weekly = new StationSchedules(null); id=ok(weekly,createAlarm(10,1,1),now(0)).getString("id");
        StationSchedules.Now monday=now(60000); weekly.advance(monday);
        long nextMonday=wall(MOSCOW,2026,11,23,10,1);
        check(entry(weekly,id,monday).getBoolean("enabled") && entry(weekly,id,monday).getLong("nextAtMs")==nextMonday,
                "Monday bit zero repeats on the next Monday before sounding");
        weekly.dismissAll(monday); weekly.advance(now(0));
        check(entry(weekly,id,now(0)).getLong("nextAtMs")==nextMonday && weekly.ringing().length()==0, "time reversal cannot repeat consumed occurrence");
        StationSchedules missed = new StationSchedules(null); id=ok(missed,createAlarm(10,1,1),now(0)).getString("id");
        StationSchedules.Now tooLate=now(60000+300001); missed.advance(tooLate);
        check(entry(missed,id,tooLate).getString("outcome").equals("missed") && missed.ringing().length()==0
                && entry(missed,id,tooLate).getLong("nextAtMs")==nextMonday, "missed weekly occurrence skips to future");
        StationSchedules changed = new StationSchedules(null); id=ok(changed,createAlarm(11,0,127),now(0)).getString("id");
        TimeZone utc=TimeZone.getTimeZone("UTC"); StationSchedules.Now zone=clock(BASE,1000000,"boot-a",utc);
        changed.advance(zone);
        check(entry(changed,id,zone).getLong("nextAtMs")==wall(utc,2026,11,16,11,0), "timezone change follows the new local hour");
        // All seven weekday bits, tested independently from a Monday morning.
        for(int day=0;day<7;day++){
            StationSchedules each=new StationSchedules(null); String eachId=ok(each,createAlarm(11,0,1<<day),now(0)).getString("id");
            check(entry(each,eachId,now(0)).getLong("nextAtMs")==wall(MOSCOW,2026,11,16+day,11,0), "weekday mask mapping is Monday-first");
        }
        TimeZone berlin=TimeZone.getTimeZone("Europe/Berlin");
        long spring=wall(berlin,2026,3,28,12,0); StationSchedules dst=new StationSchedules(null);
        StationSchedules.Now springNow=clock(spring,1000,"boot-dst",berlin);
        id=ok(dst,createAlarm(2,30,1<<6),springNow).getString("id");
        check(entry(dst,id,springNow).getLong("nextAtMs")==wall(berlin,2026,3,29,3,30), "nonexistent spring hour uses Calendar's next valid local time");
        long autumn=wall(berlin,2026,10,24,12,0); StationSchedules overlap=new StationSchedules(null);
        StationSchedules.Now autumnNow=clock(autumn,1000,"boot-dst",berlin);
        id=ok(overlap,createAlarm(2,30,1<<6),autumnNow).getString("id");
        long autumnDue=entry(overlap,id,autumnNow).getLong("nextAtMs");
        StationSchedules.Now autumnRing=clock(autumnDue,2000,"boot-dst",berlin);
        overlap.advance(autumnRing); overlap.dismissAll(autumnRing);
        check(entry(overlap,id,autumnRing).getLong("nextAtMs")>autumnDue+6*86400000L, "fall overlap produces one occurrence per local day");
    }
    private static void mutationAndDedup() throws Exception {
        StationSchedules model=new StationSchedules(null); JSONObject create=createTimer(5000);
        String id=ok(model,create,now(0)).getString("id"), revision=model.snapshot(now(0)).getString("revision");
        check(ok(model,new JSONObject(create.toString()),now(1000)).getString("id").equals(id)
                && model.snapshot(now(0)).getJSONArray("entries").length()==1
                && model.snapshot(now(0)).getString("revision").equals(revision), "same request ID never duplicates a timer");
        model=new StationSchedules(model.save());
        check(ok(model,create,now(2000)).getString("id").equals(id), "idempotency survives reload");
        JSONObject conflicting=new JSONObject(create.toString()).put("durationMs",6000);
        rejected(model,conflicting,"request_conflict");
        ok(model,request("delete").put("id",id),now(0));
        check(ok(model,create,now(0)).getString("id").equals(id) && model.snapshot(now(0)).getJSONArray("entries").length()==0,
                "replaying an old create after deletion cannot resurrect it");
        JSONObject alarm=createAlarm(11,0,0).put("enabled",false); id=ok(model,alarm,now(0)).getString("id");
        ok(model,request("update_alarm").put("id",id).put("hour",12),now(0));
        check(!entry(model,id,now(0)).getBoolean("enabled") && entry(model,id,now(0)).getLong("nextAtMs")==0, "editing disabled alarm does not enable it");
        ok(model,request("set_enabled").put("id",id).put("enabled",true),now(0));
        check(entry(model,id,now(0)).getLong("nextAtMs")==wall(MOSCOW,2026,11,16,12,0), "explicit re-enable schedules a future occurrence");
        String before=model.save().toString();
        rejected(model,request("update_alarm").put("id",id).put("hour",13).put("minute",99),"bad_request");
        check(before.equals(model.save().toString()), "multi-field invalid update is atomic");
        StationSchedules ring=new StationSchedules(null); id=ok(ring,createAlarm(10,1,127),now(0)).getString("id");ring.advance(now(60000));
        long next=entry(ring,id,now(60000)).getLong("nextAtMs");
        ok(ring,request("dismiss").put("id",id),now(60001));
        check(!entry(ring,id,now(60001)).getBoolean("ringing") && entry(ring,id,now(60001)).getLong("nextAtMs")==next,
                "dismiss affects current occurrence without cancelling next weekly occurrence");
        StationSchedules history=new StationSchedules(null); id=ok(history,createAlarm(11,0,0),now(0)).getString("id");
        for(int i=0;i<70;i++)ok(history,request("update_alarm").put("id",id).put("label","test "+i),now(0));
        check(history.save().getJSONArray("requests").length()==64, "persisted deduplication history is bounded");
        StationSchedules restored=new StationSchedules(history.save());
        check(restored.save().getJSONArray("requests").length()==64, "bounded receipt history loads correctly");
    }
    private static void strictValidation() throws Exception {
        StationSchedules model=new StationSchedules(null);
        for(Object duration:new Object[]{4999,86400001,-1,0,5.5,"5000",true,JSONObject.NULL})
            rejected(model,createTimer(5000).put("durationMs",duration),"bad_request");
        for(Object volume:new Object[]{4,81,-1,5.5,"35",true,JSONObject.NULL})
            rejected(model,createTimer(5000).put("volumePercent",volume),"bad_request");
        for(String label:new String[]{"x\ny","x\u0000y","x\u202ey","x\ud800y"})
            rejected(model,createTimer(5000).put("label",label),"bad_label");
        rejected(model,createTimer(5000).put("label",repeat("x",49)),"bad_request");
        rejected(model,createTimer(5000).put("unknown","PRIVATE"),"bad_request");
        rejected(model,createTimer(5000).put("requestId","not-uuid"),"bad_request");
        rejected(model,createAlarm(24,0,0),"bad_request"); rejected(model,createAlarm(1,60,0),"bad_request");
        rejected(model,createAlarm(1,0,128),"bad_request"); rejected(model,createAlarm(1,0,-1),"bad_request");
        rejected(model,createAlarm(1,0,0).put("enabled","true"),"bad_request");
        rejected(model,request("unsupported"),"unknown_operation");
        rejected(model,new JSONObject().put("op","list").put("extra",true),"bad_request");
        for(int i=0;i<32;i++)ok(model,createTimer(86400000),now(0));
        rejected(model,createTimer(5000),"limit_reached");
        JSONObject saved=model.save();
        JSONObject badNativeToken=new JSONObject(saved.toString()).put("nativeTimerStopToken","invalid");corrupt(badNativeToken);
        JSONObject oldSchema=new JSONObject(saved.toString());oldSchema.remove("nativeTimerStopToken");
        check(new StationSchedules(oldSchema).nativeTimerStopToken().isEmpty(),"old state has no pending native stop token");
        for(Object version:new Object[]{0,2,"1",true}) corrupt(new JSONObject(saved.toString()).put("version",version));
        corrupt(new JSONObject(saved.toString()).put("entries",new JSONArray().put(new JSONObject())));
        JSONObject duplicate=new JSONObject(saved.toString());duplicate.getJSONArray("entries").put(duplicate.getJSONArray("entries").getJSONObject(0));corrupt(duplicate);
        JSONObject badId=new JSONObject(saved.toString());badId.getJSONArray("entries").getJSONObject(0).put("id","bad");corrupt(badId);
        JSONObject badClock=new JSONObject(saved.toString());badClock.getJSONArray("entries").getJSONObject(0).put("dueElapsed",-1);corrupt(badClock);
        JSONObject badVolume=new JSONObject(saved.toString());badVolume.getJSONArray("entries").getJSONObject(0).put("volumePercent",100);corrupt(badVolume);
        try { new StationSchedules.Now(BASE,-1,"boot-a",MOSCOW);throw new AssertionError("invalid clock accepted"); }
        catch(IllegalArgumentException expected){check(expected.getMessage().equals("clock_invalid"),"invalid clock fixed failure");}
    }
    private static void stopTimers() throws Exception {
        StationSchedules empty = new StationSchedules(null);
        check(ok(empty, request("stop_timer"), now(0)).getInt("stoppedTimers") == 0, "empty stop is a confirmed no-op");
        String future = ok(empty, createTimer(60000), now(0)).getString("id");
        check(ok(empty, request("stop_timer").put("ringingOnly", true), now(0)).getInt("stoppedTimers") == 0
                && entry(empty, future, now(0)).getBoolean("enabled"), "ring-only stop never cancels pending timer");
        JSONObject soleStop = request("stop_timer");
        check(ok(empty, soleStop, now(0)).getInt("stoppedTimers") == 1
                && "cancelled".equals(entry(empty, future, now(0)).getString("outcome")), "default stop cancels sole future timer");
        empty = new StationSchedules(empty.save());
        String newer = ok(empty, createTimer(10000), now(0)).getString("id");
        String beforeReplay = empty.save().toString();
        check(ok(empty, new JSONObject(soleStop.toString()), now(0)).getInt("stoppedTimers") == 1
                && entry(empty, newer, now(0)).getBoolean("enabled") && beforeReplay.equals(empty.save().toString()),
                "persisted replay returns original count and never cancels a new timer");
        rejected(empty, new JSONObject(soleStop.toString()).put("all", true), "request_conflict");

        StationSchedules many = new StationSchedules(null);
        String a = ok(many, createTimer(60000), now(0)).getString("id");
        String b = ok(many, createTimer(60000), now(0)).getString("id");
        String alarm = ok(many, createAlarm(10,1,127), now(0)).getString("id");
        rejected(many, request("stop_timer"), "timer_ambiguous");
        check(entry(many,a,now(0)).getBoolean("enabled") && entry(many,b,now(0)).getBoolean("enabled"), "ambiguous stop preserves all candidates");
        check(ok(many, request("stop_timer").put("all",true), now(0)).getInt("stoppedTimers") == 2
                && entry(many,alarm,now(0)).getBoolean("enabled"), "explicit all cancels timers and retains alarm");

        StationSchedules ringing = new StationSchedules(null);
        String r1 = ok(ringing, createTimer(60000), now(0)).getString("id");
        String r2 = ok(ringing, createTimer(60000), now(0)).getString("id");
        String pending = ok(ringing, createTimer(120000), now(0)).getString("id");
        String activeAlarm = ok(ringing, createAlarm(10,1,127), now(0)).getString("id");
        ringing.advance(now(60000));
        check(ringing.ringing().length() == 3, "two timers and weekly alarm ring together");
        JSONObject ringStop = request("stop_timer");
        check(ok(ringing,ringStop,now(60000)).getInt("stoppedTimers") == 2
                && !entry(ringing,r1,now(60000)).getBoolean("ringing") && !entry(ringing,r2,now(60000)).getBoolean("ringing"),
                "default stop clears all currently ringing timers");
        check(entry(ringing,pending,now(60000)).getBoolean("enabled") && entry(ringing,activeAlarm,now(60000)).getBoolean("ringing")
                && entry(ringing,activeAlarm,now(60000)).getBoolean("enabled"), "ring stop preserves future timer and current/next alarm");
        ringing.advance(now(120000));
        check(ok(ringing,ringStop,now(120000)).getInt("stoppedTimers") == 2
                && entry(ringing,pending,now(120000)).getBoolean("ringing"), "same ring stop receipt never stops a later ringing occurrence");
        check(ok(ringing,request("stop_timer").put("ringingOnly",true),now(120000)).getInt("stoppedTimers") == 1,
                "ringingOnly stops latest signal");

        StationSchedules all = new StationSchedules(null);
        ok(all,createTimer(5000),now(0)); ok(all,createTimer(10000),now(0)); all.advance(now(5000));
        check(ok(all,request("stop_timer").put("all",true),now(5000)).getInt("stoppedTimers") == 2
                && all.ringing().length() == 0 && all.nextWakeAtMs(now(5000)) == 0, "explicit all includes ringing and future timers");
        for (String field : new String[]{"all","ringingOnly"}) for (Object invalid : new Object[]{1,"true",JSONObject.NULL})
            rejected(all,request("stop_timer").put(field,invalid),"bad_request");
        rejected(all,request("stop_timer").put("ringingOnly",true).put("all",true),"bad_request");
        rejected(all,request("stop_timer").put("id",UUID.randomUUID().toString()),"bad_request");
        JSONObject broken = all.save(); broken.getJSONArray("requests").getJSONObject(2).put("stoppedTimers",33); corrupt(broken);
    }
    private static void corrupt(JSONObject value) {
        try { new StationSchedules(value);throw new AssertionError("corrupt state accepted"); }
        catch(IllegalArgumentException expected){check(expected.getMessage().equals("state_invalid"),"corrupt persistence fails closed with fixed code");}
    }
    private static String repeat(String value,int count){StringBuilder text=new StringBuilder();for(int i=0;i<count;i++)text.append(value);return text.toString();}
    private static long wall(TimeZone zone,int year,int month,int day,int hour,int minute){Calendar value=Calendar.getInstance(zone,Locale.ROOT);value.clear();value.set(year,month-1,day,hour,minute,0);return value.getTimeInMillis();}
}
