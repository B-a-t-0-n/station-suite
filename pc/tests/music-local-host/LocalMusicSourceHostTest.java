package local.station.voicebridge;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class LocalMusicSourceHostTest {
    private static int assertions;
    private static void check(boolean value) { assertions++; if(!value)throw new AssertionError("case_"+assertions); }
    private static void rejects(Runnable action) {
        assertions++;try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("accepted_"+assertions);
    }
    private static Map<String,String> form(LocalMusicRequests.Request request)throws Exception {
        Map<String,String> result=new HashMap<>();
        for(String pair:new String(request.body(),StandardCharsets.UTF_8).split("&")) {
            String[] parts=pair.split("=",2);result.put(URLDecoder.decode(parts[0],"UTF-8"),URLDecoder.decode(parts[1],"UTF-8"));
        }
        return result;
    }
    public static void main(String[] args)throws Exception {
        String vk="https://cs1-77v4.vkuseraudio.net/a/music.mp3?sig=synthetic%2Bvalue";
        String muzo="https://dl3s4.muzofond.fm/a/music.mp3?x=1&y=2";
        LocalMusicUrls urls=new LocalMusicUrls(new HashSet<>(Arrays.asList("cs1-77v4.vkuseraudio.net","dl3s4.muzofond.fm")));
        check(urls.audio(LocalMusicUrls.Source.VK,vk).toString().equals(vk));
        check(urls.audio(LocalMusicUrls.Source.MUZOFOND,muzo).toString().equals(muzo));
        check(LocalMusicUrls.candidateAudioHost(LocalMusicUrls.Source.VK,vk).equals("cs1-77v4.vkuseraudio.net"));
        rejects(()->new LocalMusicUrls(Collections.emptySet()).audio(LocalMusicUrls.Source.VK,vk));
        rejects(()->urls.audio(LocalMusicUrls.Source.VK,"https://another.vkuseraudio.net/a.mp3"));
        rejects(()->urls.audio(LocalMusicUrls.Source.MUZOFOND,vk));
        rejects(()->urls.audio(LocalMusicUrls.Source.VK,muzo));
        for(String address:Arrays.asList(
                "http://cs1-77v4.vkuseraudio.net/a.mp3","https://127.0.0.1/a.mp3","https://[::1]/a.mp3",
                "https://169.254.169.254/a.mp3","https://cs1-77v4.vkuseraudio.net.evil.invalid/a.mp3",
                "https://evil.invalid/cs1-77v4.vkuseraudio.net/a.mp3","https://cs1-77v4.vkuseraudio.net@evil.invalid/a.mp3",
                "https://name@cs1-77v4.vkuseraudio.net/a.mp3","https://cs1-77v4.vkuseraudio.net:8443/a.mp3",
                "https://cs1-77v4.vkuseraudio.net./a.mp3","https://cs1-77v4.vkuseraudio.net/a.mp3#fragment",
                "https://cs1-77v4.vkuseraudio.net/a.mp3\r\nHeader: value","https://cs1-77v4.vkuseraudio.net\\@evil.invalid/a.mp3",
                "https://cs1-77v4.vkuseraudio.net/","https://cs1-77v4.vkuseraudio.net/тест.mp3"))
            rejects(()->urls.audio(LocalMusicUrls.Source.VK,address));
        for(String host:Arrays.asList("*.vkuseraudio.net","api.vk.com","VKUSERAUDIO.NET","vkuseraudio.net","foo.vkuseraudio.net.evil.invalid","dl3s4.muzofond.fm."))
            rejects(()->new LocalMusicUrls(Collections.singleton(host)));
        check(urls.api("https://api.vk.com/method/audio.get").getHost().equals("api.vk.com"));
        rejects(()->urls.api("https://api.vk.com/method/audio.get?access_token=synthetic"));
        rejects(()->urls.api("https://api.vk.com/method/messages.send"));
        rejects(()->urls.api("https://evil.invalid/method/audio.get"));
        check(urls.page("https://muzofond.fm/search?q=test").getHost().equals("muzofond.fm"));
        rejects(()->urls.page("https://muzofond.fm/settings"));
        LocalMusicRequests.Credentials credentials=new LocalMusicRequests.Credentials("fixture-secret",LocalMusicRequests.DEFAULT_USER_AGENT,"5.95");
        check(!credentials.toString().contains("fixture-secret"));
        LocalMusicRequests.Request library=LocalMusicRequests.library(credentials,30,0);
        check(library.endpoint.getRawQuery()==null);
        check(!library.toString().contains("fixture-secret"));
        check(form(library).get("access_token").equals("fixture-secret"));
        check(form(library).get("count").equals("30"));
        check(library.headers.get("Content-Type").startsWith("application/x-www-form-urlencoded"));
        byte[] copy=library.body();copy[0]=0;check(library.body()[0]!=0);
        try{library.headers.put("x","y");throw new AssertionError("mutable_headers");}catch(UnsupportedOperationException expected){assertions++;}
        check(form(LocalMusicRequests.search(credentials,"  Кино & test  ",6)).get("q").equals("Кино & test"));
        check(form(LocalMusicRequests.playlist(credentials,"-123","44",100,5)).get("owner_id").equals("-123"));
        check(form(LocalMusicRequests.playlist(credentials,"-123","44",100,5)).get("playlist_id").equals("44"));
        check(form(LocalMusicRequests.resolve(credentials,"-123_44")).get("audios").equals("-123_44"));
        check(LocalMusicRequests.playlists(credentials,"123",20).endpoint.getPath().endsWith("audio.getPlaylists"));
        check(LocalMusicRequests.currentUser(credentials).endpoint.getPath().endsWith("users.get"));
        check(LocalMusicRequests.popular(credentials,6,false).endpoint.getPath().endsWith("audio.getPopular"));
        check(LocalMusicRequests.popular(credentials,6,true).endpoint.getPath().endsWith("audio.getRecommendations"));
        check(LocalMusicRequests.muzofond("Кино песня",false).toString().contains("%20"));
        check(LocalMusicRequests.muzofond("",false).getPath().equals("/popular"));
        for(int count:new int[]{-1,0,201,Integer.MAX_VALUE})rejects(()->LocalMusicRequests.library(credentials,count,0));
        rejects(()->LocalMusicRequests.library(credentials,6,-1));
        rejects(()->LocalMusicRequests.search(credentials,"",6));
        rejects(()->LocalMusicRequests.search(credentials,"x\ny",6));
        rejects(()->LocalMusicRequests.resolve(credentials,"../etc"));
        rejects(()->new LocalMusicRequests.Credentials("fixture-secret","bad\r\nheader","5.95"));
        rejects(()->new LocalMusicRequests.Credentials("fixture-secret","test","5.95&x=1"));
        List<LocalMusicCatalog.Track> tracks=LocalMusicCatalog.vkTracks("{\"response\":{\"count\":1,\"items\":[{\"id\":44,\"owner_id\":-123,\"artist\":\"Исполнитель\",\"title\":\"Название\",\"duration\":180,\"url\":\""+vk+"\"}]}}");
        check(tracks.size()==1&&tracks.get(0).key.equals("-123_44"));
        check(tracks.get(0).durationSeconds==180&&tracks.get(0).title.equals("Название"));
        check(tracks.get(0).candidateUrl.equals(vk)&&!tracks.get(0).toString().contains("sig="));
        check(LocalMusicCatalog.vkTracks("{\"response\":[{\"id\":\"45\",\"owner_id\":\"123\"}]}").get(0).candidateUrl.isEmpty());
        check(LocalMusicCatalog.vkTracks("{\"response\":{\"items\":[]}}").isEmpty());
        check(LocalMusicCatalog.vkCurrentUser("{\"response\":[{\"id\":123}]}").equals("123"));
        List<LocalMusicCatalog.Playlist> playlists=LocalMusicCatalog.vkPlaylists("{\"response\":{\"items\":[{\"id\":44,\"title\":\"Список\",\"count\":8}]}}","123");
        check(playlists.size()==1&&playlists.get(0).ownerId.equals("123")&&playlists.get(0).count==8);
        for(String body:Arrays.asList("{\"response\":{\"items\":[]}} trailing","{\"response\":{}}","{\"response\":[{\"id\":44,\"owner_id\":0}]}",
                "{\"response\":[{\"id\":44,\"owner_id\":123,\"url\":\"http://127.0.0.1/a\"}]}",
                "{\"response\":[{\"id\":44,\"owner_id\":123,\"duration\":1.5}]}",
                "{\"response\":[{\"id\":44,\"owner_id\":123,\"duration\":86401}]}"))rejects(()->LocalMusicCatalog.vkTracks(body));
        try{LocalMusicCatalog.vkTracks("{\"error\":{\"error_code\":5,\"error_msg\":\"fixture-secret\",\"request_params\":[\"fixture-secret\"]}}");throw new AssertionError("error_accepted");}
        catch(LocalMusicCatalog.MusicFailure error){check(error.code.equals("music_api_error_5")&&!error.getMessage().contains("fixture-secret"));}
        String html="<script type=\"application/ld+json\">{\"@type\":\"MusicAlbum\",\"name\":\"Группа - Альбом\",\"track\":[{\"name\":\"Песня &amp; текст\"}]}</script>"
                +"<li class=\"item\" data-id=\"123\" data-duration=\"180\" data-url=\"https://dl3s4.muzofond.fm/a.mp3?x=1&amp;y=2\"><span>play</span></li>";
        tracks=LocalMusicCatalog.muzofond(html,"query",6);
        check(tracks.size()==1&&tracks.get(0).key.equals("123")&&tracks.get(0).artist.equals("Группа"));
        check(tracks.get(0).title.equals("Песня & текст")&&tracks.get(0).candidateUrl.endsWith("?x=1&y=2"));
        String recording="<script type=\"application/ld+json\">{\"@type\":\"MusicRecording\",\"name\":\"Песня\",\"duration\":\"PT3M\",\"byArtist\":[{\"name\":\"Группа\"}]}</script><span data-url=\"https://dl3s4.muzofond.fm/a.mp3\"></span>";
        tracks=LocalMusicCatalog.muzofond(recording,"query",6);
        check(tracks.size()==1&&tracks.get(0).key.matches("recording-[a-f0-9]{32}")&&tracks.get(0).durationSeconds==180);
        check(LocalMusicCatalog.muzofond("<html>no tracks</html>","query",6).isEmpty());
        rejects(()->LocalMusicCatalog.muzofond(html.replace("dl3s4.muzofond.fm","127.0.0.1"),"query",6));
        rejects(()->LocalMusicCatalog.muzofond(html,"query",201));
        StringBuilder large=new StringBuilder();for(int i=0;i<LocalMusicCatalog.MAX_BODY_CHARS+1;i++)large.append('x');
        rejects(()->LocalMusicCatalog.muzofond(large.toString(),"query",6));
        rejects(()->LocalMusicCatalog.vkTracks(large.toString()));
        System.out.println("LocalMusicSourceHostTest: "+assertions+" assertions passed; parser/request/policy only, no network or device.");
    }
}
