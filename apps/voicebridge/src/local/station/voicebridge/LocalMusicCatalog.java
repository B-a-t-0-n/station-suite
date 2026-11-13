package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded source response parsing only. URLs remain candidates until LocalMusicUrls authorizes them. */
final class LocalMusicCatalog {
    static final int MAX_BODY_CHARS = 2 * 1024 * 1024, MAX_ITEMS = 200;
    private static final Pattern ITEM = Pattern.compile("<li\\s+[^>]*class=\"item\"[^>]*data-id=\"([0-9]{1,19})\"[^>]*data-duration=\"([0-9]{1,6})\"[^>]*>", Pattern.DOTALL);
    private static final Pattern AUDIO = Pattern.compile("data-url=\"([^\"]{1,8192})\"");
    private static final Pattern JSON_LD = Pattern.compile("<script\\s+[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(?:amp|quot|apos|lt|gt|#[0-9]{1,7}|#x[0-9a-fA-F]{1,6});");

    static final class Track {
        final LocalMusicUrls.Source source;
        final String key, ownerId, id, title, artist, candidateUrl;
        final int durationSeconds;
        Track(LocalMusicUrls.Source source, String key, String ownerId, String id, String title,
                String artist, int duration, String candidateUrl) {
            this.source=source;this.key=key;this.ownerId=ownerId;this.id=id;this.title=title;
            this.artist=artist;durationSeconds=duration;this.candidateUrl=candidateUrl;
        }
        @Override public String toString() { return "MusicTrack{" + source + ",metadata:redacted}"; }
    }
    static final class Playlist {
        final String ownerId, id, title; final int count;
        Playlist(String owner, String id, String title, int count) {
            ownerId=owner;this.id=id;this.title=title;this.count=count;
        }
        @Override public String toString() { return "MusicPlaylist{metadata:redacted}"; }
    }

    static List<Track> vkTracks(String body) {
        try {
            Object response = response(body);
            JSONArray items = items(response);
            List<Track> result = new ArrayList<>();
            for (int i=0;i<items.length();i++) {
                JSONObject item=object(items.get(i));
                String id=LocalMusicRequests.positiveId(identifier(item.get("id")));
                String owner=LocalMusicRequests.ownerId(identifier(item.get("owner_id")));
                String artist=optionalText(item, "artist", 512);
                if (artist.isEmpty()) artist=optionalText(item,"performer",512);
                String url=optionalText(item,"url",LocalMusicUrls.MAX_URL_CHARS);
                if (!url.isEmpty()) LocalMusicUrls.candidateAudioHost(LocalMusicUrls.Source.VK,url);
                result.add(new Track(LocalMusicUrls.Source.VK,owner+"_"+id,owner,id,
                        optionalText(item,"title",512),artist,integer(item,"duration",0,86400),url));
            }
            return Collections.unmodifiableList(result);
        } catch (MusicFailure failure) { throw failure; }
        catch (Exception invalid) { throw failure("music_catalog_invalid"); }
    }

    static String vkCurrentUser(String body) {
        try {
            JSONArray response=items(response(body));
            if (response.length()!=1) throw failure("music_catalog_invalid");
            return LocalMusicRequests.positiveId(identifier(object(response.get(0)).get("id")));
        } catch (MusicFailure failure) { throw failure; }
        catch (Exception invalid) { throw failure("music_catalog_invalid"); }
    }

    static List<Playlist> vkPlaylists(String body, String currentUser) {
        try {
            LocalMusicRequests.positiveId(currentUser);
            JSONArray items=items(response(body)); List<Playlist> result=new ArrayList<>();
            for (int i=0;i<items.length();i++) {
                JSONObject item=object(items.get(i));
                String id=LocalMusicRequests.positiveId(identifier(item.get("id")));
                String owner=item.has("owner_id") ? LocalMusicRequests.ownerId(identifier(item.get("owner_id"))) : currentUser;
                String title=optionalText(item,"title",512);
                if(title.isEmpty())title=optionalText(item,"name",512);
                if(title.isEmpty())throw failure("music_catalog_invalid");
                result.add(new Playlist(owner,id,title,integer(item,"count",0,100000)));
            }
            return Collections.unmodifiableList(result);
        } catch(MusicFailure failure){throw failure;}
        catch(Exception invalid){throw failure("music_catalog_invalid");}
    }

    /** Mirrors the existing fallback's album/recording metadata and playable li entries. */
    static List<Track> muzofond(String html, String query, int limit) {
        try {
            if(html==null||html.length()>MAX_BODY_CHARS||query==null||query.length()>512||limit<1||limit>MAX_ITEMS)
                throw failure("music_catalog_invalid");
            String artist="",album=""; JSONArray titles=new JSONArray(); JSONObject recording=null;
            Matcher metadata=JSON_LD.matcher(html);
            while(metadata.find()) {
                if(metadata.group(1).length()>262144)throw failure("music_catalog_invalid");
                JSONObject item;
                try{item=object(json(metadata.group(1)));}catch(Exception notObject){continue;}
                if("MusicAlbum".equals(item.optString("@type"))) {
                    String name=optionalText(item,"name",512);int split=name.indexOf(" - ");
                    artist=split<0?name.trim():name.substring(0,split).trim();
                    album=split<0?"":name.substring(split+3).trim();
                    if(item.has("track")){titles=item.getJSONArray("track");if(titles.length()>MAX_ITEMS)throw failure("music_catalog_invalid");}
                } else if("MusicRecording".equals(item.optString("@type"))) recording=item;
            }
            Matcher entries=ITEM.matcher(html); List<int[]> spans=new ArrayList<>();List<String[]> attributes=new ArrayList<>();
            while(entries.find()) {
                if(spans.size()>=MAX_ITEMS)throw failure("music_catalog_invalid");
                spans.add(new int[]{entries.start(),entries.end()});attributes.add(new String[]{entries.group(1),entries.group(2)});
            }
            List<Track> result=new ArrayList<>();
            for(int i=0;i<spans.size()&&result.size()<limit;i++) {
                int end=i+1<spans.size()?spans.get(i+1)[0]:html.length();
                Matcher audio=AUDIO.matcher(html.substring(spans.get(i)[0],end));
                if(!audio.find())continue;
                String url=entities(audio.group(1));
                LocalMusicUrls.candidateAudioHost(LocalMusicUrls.Source.MUZOFOND,url);
                String title=i<titles.length()?optionalText(object(titles.get(i)),"name",512):"";
                if(title.isEmpty())title=album.isEmpty()?query:album;
                if(artist.isEmpty()&&i<titles.length())artist=byArtist(object(titles.get(i)));
                String id=LocalMusicRequests.positiveId(attributes.get(i)[0]);
                int duration=decimalDuration(attributes.get(i)[1]);
                result.add(new Track(LocalMusicUrls.Source.MUZOFOND,id,"",id,entities(title),artist.isEmpty()?"Muzofond":entities(artist),duration,url));
            }
            if(result.isEmpty()&&recording!=null) {
                Matcher audio=AUDIO.matcher(html);
                if(audio.find()) {
                    String url=entities(audio.group(1));LocalMusicUrls.candidateAudioHost(LocalMusicUrls.Source.MUZOFOND,url);
                    String id="recording-"+digest(url);
                    String title=optionalText(recording,"name",512);
                    if(title.isEmpty())title=query;
                    String performer=byArtist(recording);
                    int duration=isoDuration(optionalText(recording,"duration",64));
                    result.add(new Track(LocalMusicUrls.Source.MUZOFOND,id,"",id,entities(title),
                            performer.isEmpty()?"Muzofond":entities(performer),duration,url));
                }
            }
            return Collections.unmodifiableList(result);
        } catch(MusicFailure failure){throw failure;}
        catch(Exception invalid){throw failure("music_catalog_invalid");}
    }

    static final class MusicFailure extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        final String code;
        MusicFailure(String code){super(code);this.code=code;}
    }
    private static MusicFailure failure(String code){return new MusicFailure(code);}
    private static Object response(String body)throws Exception {
        JSONObject root=object(json(body));
        if(root.has("error")) {
            JSONObject error=object(root.get("error"));
            int code=integer(error,"error_code",0,1000000);
            throw failure("music_api_error_"+code);
        }
        if(!root.has("response"))throw failure("music_catalog_invalid");return root.get("response");
    }
    private static Object json(String body)throws Exception {
        if(body==null||body.isEmpty()||body.length()>MAX_BODY_CHARS)throw failure("music_catalog_invalid");
        JSONTokener tokens=new JSONTokener(body);Object result=tokens.nextValue();
        if(tokens.nextClean()!=0)throw failure("music_catalog_invalid");return result;
    }
    private static JSONArray items(Object response)throws Exception {
        JSONArray result=response instanceof JSONArray?(JSONArray)response:object(response).getJSONArray("items");
        if(result.length()>MAX_ITEMS)throw failure("music_catalog_invalid");return result;
    }
    private static JSONObject object(Object value){if(!(value instanceof JSONObject))throw failure("music_catalog_invalid");return(JSONObject)value;}
    private static String identifier(Object value) {
        if(value instanceof String)return(String)value;
        if(value instanceof Integer||value instanceof Long)return value.toString();
        throw failure("music_catalog_invalid");
    }
    private static int integer(JSONObject item,String key,int minimum,int maximum)throws Exception {
        if(!item.has(key)||item.isNull(key))return 0;
        Object value=item.get(key);
        if(!(value instanceof Number))throw failure("music_catalog_invalid");
        double number=((Number)value).doubleValue();
        if(!Double.isFinite(number)||number<minimum||number>maximum||number!=(int)number)throw failure("music_catalog_invalid");return(int)number;
    }
    private static String optionalText(JSONObject object,String key,int maximum)throws Exception {
        if(!object.has(key)||object.isNull(key))return"";
        Object value=object.get(key);
        if(!(value instanceof String)||((String)value).length()>maximum)throw failure("music_catalog_invalid");
        String text=(String)value;
        for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i)))throw failure("music_catalog_invalid");
        return text;
    }
    private static String byArtist(JSONObject value)throws Exception {
        JSONArray artists=value.optJSONArray("byArtist");
        return artists==null||artists.length()==0?"":optionalText(object(artists.get(0)),"name",512);
    }
    private static int decimalDuration(String value){int duration=Integer.parseInt(value);if(duration>86400)throw failure("music_catalog_invalid");return duration;}
    private static int isoDuration(String value) {
        if(value.isEmpty())return 0;
        Matcher match=Pattern.compile("PT(?:(\\d{1,3})H)?(?:(\\d{1,3})M)?(?:(\\d{1,3})S)?").matcher(value);
        if(!match.matches())throw failure("music_catalog_invalid");
        int duration=0;
        for(int i=1;i<=3;i++)if(match.group(i)!=null)duration+=Integer.parseInt(match.group(i))*(i==1?3600:i==2?60:1);
        if(duration>86400)throw failure("music_catalog_invalid");return duration;
    }
    private static String digest(String text)throws Exception {
        byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(int i=0;i<16;i++)result.append(String.format(java.util.Locale.ROOT,"%02x",bytes[i]&255));return result.toString();
    }
    private static String entities(String value) {
        Matcher matcher=ENTITY.matcher(value);StringBuffer result=new StringBuffer();
        while(matcher.find()) {
            String token=matcher.group(),replacement;
            if("&amp;".equals(token))replacement="&";
            else if("&quot;".equals(token))replacement="\"";
            else if("&apos;".equals(token))replacement="'";
            else if("&lt;".equals(token))replacement="<";
            else if("&gt;".equals(token))replacement=">";
            else {
                int radix=token.startsWith("&#x")?16:10,start=radix==16?3:2;
                int point=Integer.parseInt(token.substring(start,token.length()-1),radix);
                if(!Character.isValidCodePoint(point)||point>=0xd800&&point<=0xdfff||Character.isISOControl(point))throw failure("music_catalog_invalid");
                replacement=new String(Character.toChars(point));
            }
            matcher.appendReplacement(result,Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);return result.toString();
    }
}
