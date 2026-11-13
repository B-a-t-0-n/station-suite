package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public final class LocalMusicServerHostTest {
    private static int assertions;
    private static final String HOST="cs1-77v4.vkuseraudio.net";
    private static final byte[] AUDIO="ID3synthetic-music-fixture-0123456789".getBytes(StandardCharsets.US_ASCII);
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("case_"+assertions);}
    private static String track(int id,String host){return "{\"id\":"+id+",\"owner_id\":42,\"title\":\"Title "+id+"\",\"artist\":\"Artist\",\"duration\":12,\"url\":\""+(host.isEmpty()?"":"https://"+host+"/song-"+id+".mp3?signature=fixture-signature")+"\"}";}
    private static String catalog(String...items){return "{\"response\":{\"count\":"+items.length+",\"items\":["+String.join(",",items)+"]}}";}
    private static void configure(File root)throws Exception{
        JSONObject config=new JSONObject().put("version",1).put("vk",new JSONObject().put("access_token","fixture-private-token"))
                .put("audioHosts",new JSONArray().put(HOST).put("dl3s4.muzofond.fm"));
        Files.write(new File(root,"music-config.json").toPath(),config.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static final class Fake implements LocalMusicServer.Fetch {
        String library=catalog(track(1,HOST),track(2,"unenrolled.vkuseraudio.net"));
        boolean searchFailure,redirectEvil,htmlAudio,oversizeCatalog,wrongLength;
        int calls,audioCalls,resolveCalls;
        Map<String,String> lastForm;
        public LocalMusicServer.Remote open(URI uri,String method,Map<String,String> headers,byte[] body,long deadline)throws Exception{
            calls++;check(uri.getScheme().equals("https"));check(!uri.toString().contains("fixture-private-token"));
            String response;String path=uri.getPath();
            if(uri.getHost().equals("api.vk.com")){
                check(method.equals("POST"));check(uri.getQuery()==null);check(body!=null);
                lastForm=new HashMap<>();for(String pair:new String(body,StandardCharsets.UTF_8).split("&")){String[] parts=pair.split("=",2);lastForm.put(URLDecoder.decode(parts[0],"UTF-8"),URLDecoder.decode(parts[1],"UTF-8"));}
                check(lastForm.get("access_token").equals("fixture-private-token"));
                if(oversizeCatalog){byte[] bytes=new byte[2*1024*1024+1];Arrays.fill(bytes,(byte)'a');return remote(200,"application/json",bytes,null);}
                if(path.equals("/method/audio.get"))response=library;
                else if(path.equals("/method/users.get"))response="{\"response\":[{\"id\":42}]}";
                else if(path.equals("/method/audio.getPlaylists"))response="{\"response\":{\"items\":[{\"id\":3,\"owner_id\":42,\"title\":\"My playlist\",\"count\":2}]}}";
                else if(path.equals("/method/audio.getById")){resolveCalls++;response="{\"response\":["+track(3,HOST)+"]}";}
                else if(searchFailure)response="{\"error\":{\"error_code\":5,\"error_msg\":\"fixture-private-token must never escape\"}}";
                else response=catalog(track(1,HOST));
                return remote(200,"application/json",response.getBytes(StandardCharsets.UTF_8),null);
            }
            check(body==null);check(method.equals("GET"));
            if(uri.getHost().equals("muzofond.fm")){
                response="<script type=\"application/ld+json\">{\"@type\":\"MusicAlbum\",\"name\":\"Fallback artist - Album\",\"track\":[{\"name\":\"Fallback track\"}]}</script><li class=\"item\" data-id=\"7\" data-duration=\"14\" data-url=\"https://dl3s4.muzofond.fm/song.mp3\"></li>";
                return remote(200,"text/html",response.getBytes(StandardCharsets.UTF_8),null);
            }
            check(uri.getHost().equals(HOST)||uri.getHost().equals("dl3s4.muzofond.fm"));audioCalls++;
            if(redirectEvil)return remote(302,"text/html",new byte[0],"https://evil.invalid/track.mp3");
            if(htmlAudio)return remote(200,"text/html","<html>error</html>".getBytes(StandardCharsets.US_ASCII),null);
            LocalMusicServer.Remote result=remote(200,"audio/mpeg",AUDIO,null);
            if(wrongLength)result.headers.put("Content-Length","999");return result;
        }
        private LocalMusicServer.Remote remote(int status,String type,byte[] body,String location){
            Map<String,String> headers=new HashMap<>();headers.put("Content-Type",type);headers.put("Content-Length",Integer.toString(body.length));if(location!=null)headers.put("Location",location);
            return new LocalMusicServer.Remote(status,headers,new ByteArrayInputStream(body),null);
        }
    }
    private static final class Reply {
        int status;Map<String,String> headers=new HashMap<>();byte[] body;
        JSONObject json()throws Exception{return new JSONObject(new String(body,StandardCharsets.UTF_8));}
    }
    private static Reply request(LocalMusicServer server,String method,String path,String...extra)throws Exception{
        try(Socket socket=new Socket("127.0.0.1",server.port())){
            socket.setSoTimeout(5000);StringBuilder text=new StringBuilder(method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.port()+"\r\n");
            for(String header:extra)text.append(header).append("\r\n");text.append("\r\n");socket.getOutputStream().write(text.toString().getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream all=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=socket.getInputStream().read(buffer))>=0)all.write(buffer,0,count);
            byte[] data=all.toByteArray();int end=-1;for(int i=0;i+3<data.length;i++)if(data[i]==13&&data[i+1]==10&&data[i+2]==13&&data[i+3]==10){end=i;break;}
            if(end<0)throw new AssertionError("missing_http_response");String[] lines=new String(data,0,end,StandardCharsets.US_ASCII).split("\r\n");Reply result=new Reply();result.status=Integer.parseInt(lines[0].split(" ")[1]);
            for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');result.headers.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).trim());}
            result.body=Arrays.copyOfRange(data,end+4,data.length);return result;
        }
    }
    private static String audioPath(JSONObject catalog)throws Exception{return new URI(catalog.getJSONArray("tracks").getJSONObject(0).getString("url")).getPath();}
    private static void erase(File file)throws Exception{
        if(file.isDirectory()){File[] files=file.listFiles();if(files!=null)for(File child:files)erase(child);}Files.deleteIfExists(file.toPath());
    }
    public static void main(String[]args)throws Exception{
        assertions+=LocalMusicTunnelHostTest.run();
        File root=Files.createTempDirectory("station-music-fixtures-").toFile();Fake fetch=new Fake();
        try(LocalMusicServer server=new LocalMusicServer(root,fetch,0)){
            server.start();check(server.port()>0);server.start();
            check(request(server,"GET","/api/library").status==503);check(fetch.calls==0);
            configure(root);
            Reply library=request(server,"GET","/api/library");check(library.status==200);JSONObject parsed=library.json();
            check(parsed.getString("source").equals("vk-library"));JSONArray tracks=parsed.getJSONArray("tracks");check(tracks.length()==2);
            check(tracks.getJSONObject(0).getBoolean("available"));check(!tracks.getJSONObject(1).getBoolean("available"));
            check(tracks.getJSONObject(1).getString("unavailable_reason").equals("music_cdn_not_enrolled"));
            check(!parsed.toString().contains("signature"));check(!parsed.toString().contains("vkuseraudio"));check(!parsed.toString().contains("fixture-private-token"));
            String audio=audioPath(parsed);check(audio.matches("/audio/track-[0-9a-f]{32}\\.mp3"));
            Reply head=request(server,"HEAD",audio);check(head.status==200);check(head.body.length==0);check(head.headers.get("content-length").equals(Integer.toString(AUDIO.length)));check(fetch.audioCalls==1);
            Reply full=request(server,"GET",audio);check(full.status==200);check(Arrays.equals(full.body,AUDIO));check(fetch.audioCalls==1);
            Reply range=request(server,"GET",audio,"Range: bytes=3-7");check(range.status==206);check(new String(range.body,StandardCharsets.US_ASCII).equals("synth"));check(range.headers.get("content-range").equals("bytes 3-7/"+AUDIO.length));
            Reply suffix=request(server,"GET",audio,"Range: bytes=-4");check(suffix.status==206);check(new String(suffix.body,StandardCharsets.US_ASCII).equals("6789"));
            check(request(server,"GET",audio,"Range: bytes=999-").status==416);check(request(server,"GET",audio,"Range: bytes=0-1,3-4").status==416);
            check(request(server,"HEAD",audio,"Range: bytes=0-2").body.length==0);
            Reply denied=request(server,"GET",new URI(tracks.getJSONObject(1).getString("url")).getPath());check(denied.status==403);check(fetch.audioCalls==1);
            check(request(server,"GET","/api/search?q=test").json().getString("source").equals("vk"));check(fetch.lastForm.get("q").equals("test"));
            check(request(server,"GET","/api/playlists").json().getJSONArray("playlists").length()==1);check(fetch.lastForm.get("owner_id").equals("42"));
            check(request(server,"GET","/api/playlist?owner_id=-42&id=3").json().getJSONArray("tracks").length()==2);check(fetch.lastForm.get("playlist_id").equals("3"));
            check(request(server,"GET","/api/playlist?owner_id=x&id=3").status==400);check(request(server,"GET","/api/library?count=201").status==400);
            check(request(server,"GET","/api/search?q=x&q=y").status==400);check(request(server,"GET","/api/search?q=%0A").status==400);
            check(request(server,"GET","/api/library?url=https%3A%2F%2Fevil.invalid").status==400);check(request(server,"POST","/api/library").status==405);
            check(request(server,"HEAD","/api/library").status==405);check(request(server,"GET","/api/library","Origin: https://evil.invalid").status==403);
            check(request(server,"GET","/api/library","Host: evil.invalid").status==400);check(request(server,"GET","/unknown").status==404);
            check(request(server,"GET","/audio/../../music-config.json").status==404);check(request(server,"GET","/status").status==200);
            check(!server.statusJson().contains("fixture-private-token"));
            fetch.searchFailure=true;Reply fallback=request(server,"GET","/api/search?q=test");check(fallback.status==200);check(fallback.json().getString("source").equals("muzofond"));
            check(fallback.json().getJSONArray("tracks").getJSONObject(0).getString("title").equals("Fallback track"));
            fetch.library="{\"error\":{\"error_code\":5,\"error_msg\":\"fixture-private-token must never escape\"}}";
            Reply apiError=request(server,"GET","/api/library");check(apiError.status==502);check(apiError.json().getString("error").equals("music_api_error_5"));check(!apiError.json().toString().contains("fixture-private-token"));
            fetch.library=catalog(track(3,""));String resolve=audioPath(request(server,"GET","/api/library").json());check(request(server,"GET",resolve).status==200);check(fetch.resolveCalls==1);
            fetch.library=catalog(track(4,HOST));String redirect=audioPath(request(server,"GET","/api/library").json());fetch.redirectEvil=true;
            check(request(server,"GET",redirect).status==502);fetch.redirectEvil=false;check(!new File(root,"music-cache/"+redirect.substring(7)+".part").exists());
            fetch.htmlAudio=true;check(request(server,"GET",redirect).status==502);fetch.htmlAudio=false;
            fetch.wrongLength=true;check(request(server,"GET",redirect).status==502);fetch.wrongLength=false;
            fetch.oversizeCatalog=true;check(request(server,"GET","/api/library").status==502);fetch.oversizeCatalog=false;
            InetSocketAddress proxy=(InetSocketAddress)LocalMusicServer.TunnelFetch.PROXY.address();check(LocalMusicServer.TunnelFetch.PROXY.type()==Proxy.Type.HTTP);check(proxy.getPort()==18443);check(proxy.getAddress().isLoopbackAddress());
            int originalPort=server.port();server.close();
            try(LocalMusicServer rebound=new LocalMusicServer(root,new Fake(),originalPort)){
                rebound.start();check(rebound.port()==originalPort);check(request(rebound,"GET","/status").status==200);
            }
        }finally{erase(root);}
        File bounded=Files.createTempDirectory("station-music-cache-fixtures-").toFile();File cache=new File(bounded,"music-cache");check(cache.mkdir());
        for(int i=0;i<5;i++){File file=new File(cache,String.format(Locale.ROOT,"track-%032x.mp3",i));try(RandomAccessFile sparse=new RandomAccessFile(file,"rw")){sparse.setLength(LocalMusicServer.MAX_AUDIO_BYTES);}file.setLastModified(1000+i);}
        Files.write(new File(cache,"track-00000000000000000000000000000005.mp3.part").toPath(),new byte[]{1});
        Files.write(new File(cache,"unowned-file.txt").toPath(),new byte[]{1});
        try(LocalMusicServer server=new LocalMusicServer(bounded,new Fake(),0)){
            server.start();check(new JSONObject(server.statusJson()).getLong("cacheBytes")<=LocalMusicServer.MAX_CACHE_BYTES);
            check(!new File(cache,"track-00000000000000000000000000000000.mp3").exists());check(!new File(cache,"track-00000000000000000000000000000005.mp3.part").exists());check(new File(cache,"unowned-file.txt").exists());
        }finally{erase(bounded);}
        System.out.println(assertions+" local music server assertions passed; synthetic upstreams and loopback only");
    }
}
