package local.station.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict device-reported capabilities and typed EQ commands. No local audio effects. */
final class EqualizerModel {
    static final int MAX_BANDS=32;
    private static final String[] PRESET_IDS={"normal","bass","vocal","soft","energetic","cinema"};
    static final class Preset {
        final String id,name,description;
        Preset(JSONObject value) {
            if(value==null)throw invalid();
            id=text(value,"id",24);if(!knownPreset(id))throw invalid();
            name=text(value,"name",40);description=text(value,"description",160);
        }
    }
    static final class Band {
        final int index,levelMb;
        final double centerHz;
        Band(JSONObject value,int minimum,int maximum) {
            if (value==null) throw invalid();
            index=integer(value,"index");
            if (index<0||index>=MAX_BANDS) throw invalid();
            Object raw=value.opt("centerHz");
            if (!(raw instanceof Number)) throw invalid();
            centerHz=((Number)raw).doubleValue();
            if (Double.isNaN(centerHz)||Double.isInfinite(centerHz)||centerHz<=0||centerHz>200000) throw invalid();
            levelMb=integer(value,"levelMb");
            if (levelMb<minimum||levelMb>maximum) throw invalid();
        }
    }
    static final class Snapshot {
        final boolean ok,available,enabled,attached,effectiveEnabled;
        final int minLevelMb,maxLevelMb;
        final List<Band> bands;
        final List<Preset> presets;
        final String selectedPreset;
        final String code;
        Snapshot(JSONObject source) {
            if (source==null||!"agent_player".equals(source.opt("scope"))) throw invalid();
            ok=bool(source,"ok"); available=bool(source,"available");
            enabled=bool(source,"enabled"); attached=bool(source,"attached");
            effectiveEnabled=bool(source,"effectiveEnabled");
            Object rawCode=source.opt("code");code=rawCode instanceof String?(String)rawCode:"";
            if(effectiveEnabled&&(!attached||!enabled))throw invalid();
            minLevelMb=integer(source,"minLevelMb"); maxLevelMb=integer(source,"maxLevelMb");
            if (minLevelMb<Short.MIN_VALUE||maxLevelMb>Short.MAX_VALUE||minLevelMb>maxLevelMb) throw invalid();
            JSONArray values=source.optJSONArray("bands");
            if (values==null||values.length()>MAX_BANDS||available&&values.length()==0
                    ||!available&&(attached||values.length()!=0&&!nativePlayerUnsupported())) throw invalid();
            List<Band> copy=new ArrayList<>(); Set<Integer> indices=new HashSet<>();
            double last=0;
            for (int i=0;i<values.length();i++) {
                Band band=new Band(values.optJSONObject(i),minLevelMb,maxLevelMb);
                if (!indices.add(band.index)||band.centerHz<=last) throw invalid();
                last=band.centerHz; copy.add(band);
            }
            bands=Collections.unmodifiableList(copy);
            JSONArray choices=source.optJSONArray("presets");
            if(choices==null||choices.length()!=PRESET_IDS.length)throw invalid();
            List<Preset> catalogue=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(int i=0;i<choices.length();i++){
                Preset preset=new Preset(choices.optJSONObject(i));
                if(!ids.add(preset.id))throw invalid();catalogue.add(preset);
            }
            presets=Collections.unmodifiableList(catalogue);
            selectedPreset=text(source,"selectedPreset",24);
            if(!"custom".equals(selectedPreset)&&!ids.contains(selectedPreset))throw invalid();
        }
        Preset preset(String id){for(Preset preset:presets)if(preset.id.equals(id))return preset;throw invalid();}
        boolean acknowledges(String chosen){return chosen==null||!ok||chosen.equals(selectedPreset);}
        boolean canChoose(){return available&&(ok||"effect_not_attached".equals(code));}
        boolean nativePlayerUnsupported(){return !available&&"native_player_eq_unsupported".equals(code);}
        String detail(){
            if(nativePlayerUnsupported())return "Музыка играет через штатный плеер для анимаций дисплея. Наши пресеты в нём недоступны. Прежние настройки сохранены.";
            if(!available)return "Эквалайзер нашего плеера недоступен на Станции";
            if(!ok)return error(code);
            if(!attached)return "Сохранено на Станции. Применится к следующей песне нашего плеера";
            if(enabled&&effectiveEnabled)return "Сейчас применяется к музыке нашего плеера";
            if(!enabled&&!effectiveEnabled)return "Исходное звучание без обработки";
            return "Применение режима пока не подтверждено";
        }
        String notice(){
            if(!available||!ok)return detail();
            return attached?state():"Режим сохранён и включится со следующей песней";
        }
        String state(){
            if(!available)return "Недоступен";
            if(!ok)return "Не применён";
            if(attached)return enabled&&effectiveEnabled?"Применён к музыке":!enabled&&!effectiveEnabled?"Без обработки":"Не применён";
            return "Сохранён на Станции";
        }
        Band band(int index) {
            for (Band band:bands) if (band.index==index) return band;
            throw invalid();
        }
    }
    static String get() { return command(object("get")); }
    static String choose(Snapshot snapshot,String id){
        if(snapshot==null||!snapshot.canChoose()||!knownPreset(id))throw invalid();
        snapshot.preset(id);
        return command(put(object("set_preset"),"preset",id));
    }
    static String error(String code) {
        if ("native_player_eq_unsupported".equals(code)) return "Наши пресеты недоступны в штатном плеере. Прежние настройки сохранены.";
        if ("equalizer_unavailable".equals(code)) return "На этой Станции эквалайзер плеера недоступен";
        if ("storage_unavailable".equals(code)) return "Не удалось сохранить настройку на Станции";
        if ("effect_not_attached".equals(code)) return "Настройка пока не применилась к песне. Нажми нужный режим ещё раз";
        if ("invalid_preset".equals(code)) return "Станция не поддерживает этот режим. Нажми «Обновить»";
        if ("invalid_band".equals(code)||"invalid_level".equals(code)) return "Параметры звука изменились. Обнови эквалайзер";
        if ("main_timeout".equals(code)) return "Станция пока не ответила. Нажми «Обновить», чтобы проверить настройку";
        return "Не удалось подтвердить настройку. Нажми «Обновить»";
    }
    private static JSONObject object(String operation) { return put(new JSONObject(),"op",operation); }
    private static boolean knownPreset(String id){for(String known:PRESET_IDS)if(known.equals(id))return true;return false;}
    private static String text(JSONObject source,String key,int maximum){
        Object raw=source.opt(key);if(!(raw instanceof String))throw invalid();String value=(String)raw;
        if(value.isEmpty()||value.length()>maximum)throw invalid();
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i))||Character.isSurrogate(value.charAt(i)))throw invalid();
        return value;
    }
    private static JSONObject put(JSONObject value,String key,Object item) {
        try { value.put(key,item); return value; } catch (Exception invalid) { throw invalid(); }
    }
    private static String command(JSONObject value) { return "EQUALIZER "+value.toString(); }
    private static boolean bool(JSONObject source,String key) {
        Object value=source.opt(key); if (!(value instanceof Boolean)) throw invalid(); return (Boolean)value;
    }
    private static int integer(JSONObject source,String key) {
        Object value=source.opt(key);
        if (!(value instanceof Integer||value instanceof Long)) throw invalid();
        long result=((Number)value).longValue();
        if (result<Integer.MIN_VALUE||result>Integer.MAX_VALUE) throw invalid();
        return (int)result;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("equalizer_invalid"); }
}
