package local.station.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** LED settings only. A stored selection is never reported as native application. */
final class DisplayModel {
    static final class MusicMode {
        final String id,title,description,previewKind;
        MusicMode(JSONObject source){
            if(source==null)throw invalid();
            id=text(source,"id",24,false);if(!knownMusicStyle(id))throw invalid();
            title=text(source,"title",48,false);description=text(source,"description",180,false);
            previewKind=text(source,"previewKind",24,false);if(!knownMusicStyle(previewKind))throw invalid();
        }
    }
    static final class Brightness {
        final boolean auto;
        final double value;
        Brightness(JSONObject source) {
            if(source==null)throw invalid();
            auto=bool(source,"auto");value=number(source,"value");
            if(value<0||value>1)throw invalid();
        }
        boolean matches(Brightness other){return other!=null&&auto==other.auto&&Math.abs(value-other.value)<=.010000001;}
    }
    static final class Snapshot {
        final boolean ok,writeAvailable,nativeAvailable,clockSizeExplicit,musicStyleExplicit;
        final String state,clockSize,requestedSize,musicStyle,requestedMusicStyle,revision,code;
        final Brightness brightness,requestedBrightness;
        final List<MusicMode> musicModes;
        final JSONObject musicRuntime;
        final long expiresAtMs;
        Snapshot(JSONObject source) {
            if(source==null||!"station_led".equals(source.opt("scope")))throw invalid();
            ok=bool(source,"ok");writeAvailable=bool(source,"writeAvailable");nativeAvailable=bool(source,"nativeAvailable");
            state=text(source,"state",24,false);
            if(!"pending".equals(state)&&!"applied".equals(state)&&!"defaults".equals(state)&&!"unavailable".equals(state))throw invalid();
            JSONObject current=source.optJSONObject("current"),requested=source.optJSONObject("requested");
            if(current==null||requested==null)throw invalid();
            clockSize=text(current,"clockSize",12,false);clockSizeExplicit=bool(current,"clockSizeExplicit");
            if(!knownSize(clockSize)&&!"unknown".equals(clockSize))throw invalid();
            brightness=current.has("brightness")?new Brightness(current.optJSONObject("brightness")):null;
            requestedSize=requested.has("clockSize")?text(requested,"clockSize",12,false):null;
            if(requestedSize!=null&&!knownSize(requestedSize))throw invalid();
            requestedBrightness=requested.has("brightness")?new Brightness(requested.optJSONObject("brightness")):null;
            musicStyle=current.has("musicStyle")?text(current,"musicStyle",24,false):"unknown";
            musicStyleExplicit=current.has("musicStyleExplicit")?bool(current,"musicStyleExplicit"):false;
            if(!knownMusicStyle(musicStyle)&&!"unknown".equals(musicStyle)||musicStyleExplicit&&"unknown".equals(musicStyle))throw invalid();
            requestedMusicStyle=requested.has("musicStyle")?text(requested,"musicStyle",24,false):null;
            if(requestedMusicStyle!=null&&!knownMusicStyle(requestedMusicStyle))throw invalid();
            List<MusicMode> modes=new ArrayList<>();Set<String> ids=new HashSet<>();
            if(source.has("musicModes")){
                JSONArray catalogue=source.optJSONArray("musicModes");if(catalogue==null||catalogue.length()>6)throw invalid();
                for(int i=0;i<catalogue.length();i++){
                    MusicMode mode=new MusicMode(catalogue.optJSONObject(i));if(!ids.add(mode.id))throw invalid();modes.add(mode);
                }
            }
            musicModes=Collections.unmodifiableList(modes);
            if(source.has("musicRuntime")){
                JSONObject runtime=source.optJSONObject("musicRuntime");if(runtime==null||runtime.toString().length()>4096)throw invalid();
                try{musicRuntime=new JSONObject(runtime.toString());}catch(Exception invalid){throw invalid();}
            }else musicRuntime=null;
            expiresAtMs=integer(source,"expiresAtMs");if(expiresAtMs<0)throw invalid();
            revision=text(source,"revision",128,true);if(!revision.matches("[A-Za-z0-9._:-]{0,128}"))throw invalid();
            Object rawCode=source.opt("code");code=rawCode instanceof String?(String)rawCode:"";
            if(!nativeAvailable&&(!"unknown".equals(clockSize)||clockSizeExplicit||brightness!=null||!"unknown".equals(musicStyle)||musicStyleExplicit))throw invalid();
            if(nativeAvailable&&"unknown".equals(clockSize))throw invalid();
            if("applied".equals(state)&&(!nativeAvailable||!hasRequest()||!matchesNative()))throw invalid();
            if("defaults".equals(state)&&(!nativeAvailable||hasRequest()))throw invalid();
            if("pending".equals(state)&&(!nativeAvailable||!hasRequest()))throw invalid();
        }
        boolean hasRequest(){return requestedSize!=null||requestedBrightness!=null||requestedMusicStyle!=null;}
        boolean matchesNative(){return (requestedSize==null||clockSizeExplicit&&requestedSize.equals(clockSize))
                &&(requestedBrightness==null||requestedBrightness.matches(brightness))
                &&(requestedMusicStyle==null||musicStyleExplicit&&requestedMusicStyle.equals(musicStyle));}
        boolean canEdit(){return ok&&writeAvailable;}
        String chosenSize(){return requestedSize!=null?requestedSize:clockSize;}
        Brightness chosenBrightness(){return requestedBrightness!=null?requestedBrightness:brightness;}
        String chosenMusicStyle(){return requestedMusicStyle!=null?requestedMusicStyle:musicStyle;}
        MusicMode musicMode(String id){for(MusicMode mode:musicModes)if(mode.id.equals(id))return mode;throw invalid();}
        String musicSelectionLabel(){
            if(ok&&nativeAvailable&&musicStyleExplicit&&requestedMusicStyle!=null&&requestedMusicStyle.equals(musicStyle))return "Настройка подтверждена";
            if(requestedMusicStyle!=null&&(!ok||!"applied".equals(state)))return "pending".equals(state)?"Выбрано · ожидает Станцию":"Выбрано · не подтверждено";
            return musicStyleExplicit?"Настройка подтверждена":"Настройка по умолчанию";
        }
        boolean acknowledgesMusicStyle(String chosen){return !ok||chosen.equals(requestedMusicStyle);}
        String musicPlaybackCaption(){
            if(musicRuntime==null||!"native".equals(musicRuntime.opt("backend")))return "";
            if(!Boolean.TRUE.equals(musicRuntime.opt("nativeConfirmed")))return "Штатный плеер · состояние ещё не подтверждено";
            Object state=musicRuntime.opt("state");
            if("playing".equals(state))return "Штатный плеер · воспроизведение подтверждено";
            if("paused".equals(state))return "Штатный плеер · пауза подтверждена";
            if("stopped".equals(state))return "Штатный плеер · воспроизведение остановлено";
            return "Штатный плеер · состояние ещё не подтверждено";
        }
        String sizeSelectionLabel(){
            if(requestedSize!=null&&(!ok||!"applied".equals(state)))return "pending".equals(state)?"Выбрано · ожидает Станцию":"Выбрано · не подтверждено";
            return "Текущий размер";
        }
        String brightnessSelectionSuffix(){
            if(requestedBrightness==null||ok&&"applied".equals(state))return "";
            return "pending".equals(state)?" · ожидает Станцию":" · не подтверждено";
        }
        boolean acknowledgesSize(String chosen){return !ok||chosen.equals(requestedSize);}
        boolean acknowledgesBrightness(boolean auto,double value){return !ok||requestedBrightness!=null
                &&auto==requestedBrightness.auto&&Math.abs(value-requestedBrightness.value)<.0001;}
        String badge(){
            if("pending".equals(state))return "Ожидает Станцию";
            if("applied".equals(state))return "Применено";
            if("defaults".equals(state))return "По умолчанию";
            if(nativeAvailable&&!writeAvailable)return "Только просмотр";
            return "Нет подтверждения";
        }
    }
    static String get(){return command(object("get"));}
    static String size(Snapshot snapshot,String size){
        if(snapshot==null||!snapshot.canEdit()||!knownSize(size))throw invalid();
        return command(put(object("set_clock_size"),"size",size));
    }
    static String brightness(Snapshot snapshot,boolean auto,double value){
        if(snapshot==null||!snapshot.canEdit()||Double.isNaN(value)||Double.isInfinite(value)||value<0||value>1)throw invalid();
        return command(put(put(object("set_brightness"),"auto",auto),"value",value));
    }
    static String musicStyle(Snapshot snapshot,String style){
        if(snapshot==null||!snapshot.canEdit()||!knownMusicStyle(style))throw invalid();
        snapshot.musicMode(style);
        return command(put(object("set_music_style"),"style",style));
    }
    static String sizeName(String size){return "small".equals(size)?"Мелкие":"middle".equals(size)?"Средние":"large".equals(size)?"Крупные":"Неизвестно";}
    static String error(String code){
        if("pc_bridge_unavailable".equals(code))return "Служба настроек Станции недоступна";
        if("native_display_unavailable".equals(code))return "Не удалось прочитать настройку дисплея. Нажми «Обновить»";
        if("invalid_display_request".equals(code))return "Станция не приняла настройку. Нажми «Обновить»";
        if("display_busy".equals(code))return "Станция занята. Подожди и нажми «Обновить»";
        if("display_interrupted".equals(code))return "Проверка прервалась. Нажми «Обновить»";
        return "Настройка не подтверждена. Нажми «Обновить»";
    }
    private static boolean knownSize(String value){return "small".equals(value)||"middle".equals(value)||"large".equals(value);}
    private static boolean knownMusicStyle(String value){return "clock".equals(value)||"wave".equals(value)||"pads".equals(value)||"bricks".equals(value)||"fluid".equals(value)||"random".equals(value);}
    private static JSONObject object(String op){return put(new JSONObject(),"op",op);}
    private static JSONObject put(JSONObject value,String key,Object item){try{value.put(key,item);return value;}catch(Exception error){throw invalid();}}
    private static String command(JSONObject value){return "DISPLAY "+value.toString();}
    private static boolean bool(JSONObject source,String key){Object value=source.opt(key);if(!(value instanceof Boolean))throw invalid();return (Boolean)value;}
    private static double number(JSONObject source,String key){Object value=source.opt(key);if(!(value instanceof Number))throw invalid();double result=((Number)value).doubleValue();if(Double.isNaN(result)||Double.isInfinite(result))throw invalid();return result;}
    private static long integer(JSONObject source,String key){Object value=source.opt(key);if(!(value instanceof Integer||value instanceof Long))throw invalid();return ((Number)value).longValue();}
    private static String text(JSONObject source,String key,int max,boolean empty){Object raw=source.opt(key);if(!(raw instanceof String))throw invalid();String value=(String)raw;if((!empty&&value.isEmpty())||value.length()>max)throw invalid();for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i))||Character.isSurrogate(value.charAt(i)))throw invalid();return value;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("display_invalid");}
}
