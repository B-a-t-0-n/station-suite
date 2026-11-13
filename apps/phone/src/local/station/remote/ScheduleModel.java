package local.station.remote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/** Bounded Station snapshots and commands. This helper never schedules or rings on the phone. */
final class ScheduleModel {
    static final int LIMIT=32, MIN_VOLUME=5, MAX_VOLUME=80;
    static final long MAX_TIMER_MS=24*60*60*1000L;
    private static final String[] DAYS={"Пн","Вт","Ср","Чт","Пт","Сб","Вс"};

    static final class Entry {
        final String id,type,label,outcome;
        final boolean enabled,ringing;
        final int volume,hour,minute,daysMask;
        final long remainingMs,nextAtMs;
        Entry(JSONObject source,long now) {
            id=text(source.optString("id",""),80,false);
            type=source.optString("type","");
            if (!"timer".equals(type)&&!"alarm".equals(type)) throw invalid();
            label=text(source.optString("label",""),48,true);
            outcome=text(source.optString("outcome",""),16,true);
            if (!(source.opt("enabled") instanceof Boolean)) throw invalid();
            enabled=source.optBoolean("enabled");
            if (source.has("ringing")&&!(source.opt("ringing") instanceof Boolean)) throw invalid();
            ringing=source.optBoolean("ringing");
            volume=integer(source,"volumePercent",-1); range(volume,MIN_VOLUME,MAX_VOLUME);
            if ("timer".equals(type)) {
                long value=source.has("remainingMs")?number(source,"remainingMs",-1)
                        : positiveDifference(number(source,"dueAtMs",-1),now);
                if (value<0||value>MAX_TIMER_MS) throw invalid();
                remainingMs=value; hour=minute=daysMask=0; nextAtMs=0;
            } else {
                hour=integer(source,"hour",-1); minute=integer(source,"minute",-1);
                daysMask=integer(source,"daysMask",-1);
                range(hour,0,23); range(minute,0,59); range(daysMask,0,127);
                nextAtMs=number(source,"nextAtMs",0); if (nextAtMs<0) throw invalid();
                remainingMs=0;
            }
        }
        boolean timer() { return "timer".equals(type); }
        String name() { return label.isEmpty()?(timer()?"Таймер":"Будильник"):label; }
        String state() {
            if (ringing) return "Сигнал звучит";
            if ("missed".equals(outcome)) return timer()?"Пропущен":"Последний сигнал пропущен";
            if ("dismissed".equals(outcome)) return "Сигнал остановлен";
            if ("expired".equals(outcome)||"fired".equals(outcome)) return "Сигнал завершён";
            if ("cancelled".equals(outcome)) return timer()?"Отменён":"Выключен";
            return enabled?"Включён":timer()?"Неактивен":"Выключен";
        }
    }

    static final class Snapshot {
        final String revision,timezone;
        final long nowMs;
        final List<Entry> entries;
        Snapshot(JSONObject source) {
            if (source==null||source.has("ok")&&!source.optBoolean("ok")) throw invalid();
            revision=text(source.optString("revision",""),128,false);
            timezone=text(source.optString("timezone",""),80,false);
            boolean known=false;
            for (String id:TimeZone.getAvailableIDs()) if (id.equals(timezone)) { known=true; break; }
            if (!known) throw invalid();
            nowMs=number(source,"nowMs",-1); if (nowMs<0) throw invalid();
            JSONArray values=source.optJSONArray("entries");
            if (values==null||values.length()>LIMIT) throw invalid();
            List<Entry> result=new ArrayList<>(); Set<String> ids=new HashSet<>();
            for (int i=0;i<values.length();i++) {
                JSONObject value=values.optJSONObject(i); if (value==null) throw invalid();
                Entry entry=new Entry(value,nowMs); if (!ids.add(entry.id)) throw invalid(); result.add(entry);
            }
            entries=Collections.unmodifiableList(result);
        }
    }

    static JSONObject list() { return object("list"); }
    static JSONObject timer(int seconds,String label,int volume) {
        range(seconds,5,24*60*60); range(volume,MIN_VOLUME,MAX_VOLUME);
        return put(put(put(mutation("create_timer"),"durationMs",seconds*1000L),"label",text(label,48,true)),"volumePercent",volume);
    }
    static JSONObject alarm(String id,int hour,int minute,int days,String label,int volume,boolean enabled) {
        range(hour,0,23); range(minute,0,59); range(days,0,127); range(volume,MIN_VOLUME,MAX_VOLUME);
        JSONObject value=mutation(id==null?"create_alarm":"update_alarm");
        if (id!=null) put(value,"id",text(id,80,false));
        put(value,"hour",hour); put(value,"minute",minute); put(value,"daysMask",days);
        put(value,"label",text(label,48,true)); put(value,"volumePercent",volume); return put(value,"enabled",enabled);
    }
    static JSONObject enabled(String id,boolean enabled) { return put(existing("set_enabled",id),"enabled",enabled); }
    static JSONObject delete(String id) { return existing("delete",id); }
    static JSONObject dismiss(String id) { return existing("dismiss",id); }
    static String command(JSONObject value) {
        String command="SCHEDULE "+value;
        if (command.length()>512) throw invalid(); return command;
    }
    static int timerSeconds(String hours,String minutes,String seconds) {
        int h=number(hours,0,24),m=number(minutes,0,59),s=number(seconds,0,59);
        int result=h*3600+m*60+s; range(result,5,86400); return result;
    }
    private static int number(String value,int min,int max) {
        if (value==null||value.isEmpty()) return 0;
        if (value.length()>2) throw invalid();
        for (int i=0;i<value.length();i++) if (value.charAt(i)<'0'||value.charAt(i)>'9') throw invalid();
        int number=Integer.parseInt(value); range(number,min,max); return number;
    }
    static long remaining(Entry entry,long receivedAt,long now) {
        // Visual interpolation only. Zero never rings, sends a command, or creates a phone alarm.
        long elapsed=entry.enabled&&now>=receivedAt?now-receivedAt:0;
        if (elapsed<0) elapsed=Long.MAX_VALUE;
        return Math.max(0,entry.remainingMs-Math.min(entry.remainingMs,elapsed));
    }
    static String countdown(long millis) {
        long seconds=Math.max(0,millis)/1000+(millis>0&&millis%1000!=0?1:0);
        return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
                :String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }
    static String alarmTime(int hour,int minute) { return String.format(Locale.ROOT,"%02d:%02d",hour,minute); }
    static String days(int mask) {
        if (mask==0) return "Один раз";
        if (mask==127) return "Каждый день";
        StringBuilder value=new StringBuilder();
        for (int i=0;i<7;i++) if ((mask&(1<<i))!=0) { if (value.length()>0) value.append(" · "); value.append(DAYS[i]); }
        return value.toString();
    }
    static String next(long when,String zone) {
        if (when<=0) return "";
        SimpleDateFormat format=new SimpleDateFormat("EEE, d MMM · HH:mm",new Locale("ru"));
        format.setTimeZone(TimeZone.getTimeZone(zone)); return format.format(new Date(when));
    }
    static String error(String value) {
        if (value==null||value.trim().isEmpty()) return "Станция не ответила. Обнови список";
        StringBuilder result=new StringBuilder();
        for (int i=0;i<value.length()&&result.length()<160;i++) {
            char ch=value.charAt(i); if (!Character.isISOControl(ch)) result.append(ch);
        }
        return result.toString();
    }
    private static JSONObject existing(String op,String id) { return put(mutation(op),"id",text(id,80,false)); }
    private static JSONObject mutation(String op) { return put(object(op),"requestId",UUID.randomUUID().toString()); }
    private static JSONObject object(String op) { return put(new JSONObject(),"op",op); }
    private static JSONObject put(JSONObject value,String key,Object item) {
        try { return value.put(key,item); } catch (JSONException error) { throw invalid(); }
    }
    private static String text(String value,int max,boolean empty) {
        if (value==null) throw invalid();
        for (int i=0;i<value.length();i++) if (Character.isISOControl(value.charAt(i))) throw invalid();
        value=value.trim();
        if (value.length()>max||!empty&&value.isEmpty()) throw invalid();
        for (int i=0;i<value.length();i++) {
            char ch=value.charAt(i); if (Character.isISOControl(ch)) throw invalid();
            if (Character.isHighSurrogate(ch)) { if (++i>=value.length()||!Character.isLowSurrogate(value.charAt(i))) throw invalid(); }
            else if (Character.isLowSurrogate(ch)) throw invalid();
        }
        return value;
    }
    private static void range(int value,int min,int max) { if (value<min||value>max) throw invalid(); }
    private static long number(JSONObject value,String key,long fallback) {
        Object item=value.opt(key); if (item==null||item==JSONObject.NULL) return fallback;
        if (!(item instanceof Integer||item instanceof Long||item instanceof Short||item instanceof Byte)) throw invalid();
        return ((Number)item).longValue();
    }
    private static int integer(JSONObject value,String key,int fallback) {
        long number=number(value,key,fallback);
        if (number<Integer.MIN_VALUE||number>Integer.MAX_VALUE) throw invalid(); return (int)number;
    }
    private static long positiveDifference(long future,long now) {
        if (future<0) return -1;
        return future<=now?0:future-now;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Некорректные данные часов"); }
}
