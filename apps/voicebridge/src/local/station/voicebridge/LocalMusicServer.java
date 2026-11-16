package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local music catalog/cache. Upstream TLS uses the fixed PC tunnel unless direct is explicitly configured.
 * The private configuration is never returned, logged, or placed in an upstream URL. No Android UI.
 */
public final class LocalMusicServer implements Closeable {
    public static final int PORT = 17686;
    static final long MAX_CACHE_BYTES = 128L * 1024 * 1024;
    static final long MAX_AUDIO_BYTES = 32L * 1024 * 1024;
    private static final int MAX_CONFIG_BYTES=65536, MAX_CATALOG_BYTES=2*1024*1024, MAX_TRACKS=512;
    private static final String AUDIO_NAME="track-[0-9a-f]{32}\\.mp3";
    private final File configFile, cacheDir;
    private final Fetch fetch;
    private final int requestedPort;
    private final Object cacheLock=new Object();
    private final Map<String,Integer> inUse=new HashMap<>();
    private final Semaphore download=new Semaphore(1);
    private final LinkedHashMap<String,LocalMusicCatalog.Track> catalog=new LinkedHashMap<>(64,.75f,true);
    private final Map<Socket,ScheduledFuture<?>> clients=new ConcurrentHashMap<>();
    private final AtomicInteger threadNumber=new AtomicInteger();
    private ServerSocket listener;
    private Thread acceptThread;
    private ThreadPoolExecutor workers;
    private ScheduledThreadPoolExecutor clientTimeouts;
    private volatile boolean running;
    private volatile int boundPort;

    public LocalMusicServer(File filesDir) throws IOException { this(filesDir,new TunnelFetch(),PORT); }
    LocalMusicServer(File filesDir,Fetch fetch,int port) throws IOException {
        if(filesDir==null||fetch==null||port<0||port>65535)throw new IllegalArgumentException("music_configuration_invalid");
        File root=filesDir.getCanonicalFile();
        configFile=new File(root,"music-config.json");cacheDir=new File(root,"music-cache");
        this.fetch=fetch;requestedPort=port;
    }
    public synchronized void start() throws IOException {
        if(running)return;
        if(!cacheDir.isDirectory()&&!cacheDir.mkdirs())throw new IOException("music_cache_unavailable");
        synchronized(cacheLock){try{cleanParts();trimCache(0);}catch(Exception failure){throw new IOException("music_cache_unavailable");}}
        ServerSocket socket=new ServerSocket();socket.setReuseAddress(true);
        try{socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),requestedPort),8);}
        catch(IOException failure){socket.close();throw new IOException("music_listener_unavailable");}
        listener=socket;boundPort=socket.getLocalPort();running=true;
        clientTimeouts=new ScheduledThreadPoolExecutor(1,task->{Thread thread=new Thread(task,"station-music-timeout");thread.setDaemon(true);return thread;});
        clientTimeouts.setRemoveOnCancelPolicy(true);
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(4),task->{
            Thread thread=new Thread(task,"station-music-"+threadNumber.incrementAndGet());thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        acceptThread=new Thread(this::accept,"station-music-listener");acceptThread.setDaemon(true);acceptThread.start();
    }
    public synchronized void close() {
        running=false;
        try{if(listener!=null)listener.close();}catch(IOException ignored){}
        for(Map.Entry<Socket,ScheduledFuture<?>> client:clients.entrySet()){client.getValue().cancel(false);closeQuietly(client.getKey());}
        clients.clear();if(clientTimeouts!=null)clientTimeouts.shutdownNow();
        if(workers!=null)workers.shutdownNow();
        listener=null;
    }
    public String statusJson() {
        try{return new JSONObject().put("running",running).put("port",boundPort)
                .put("configured",configFile.isFile()).put("cacheBytes",cacheBytes()).toString();}
        catch(Exception ignored){return "{\"running\":false}";}
    }
    int port(){return boundPort;}
    private void accept() {
        ServerSocket socket=listener;
        while(running)try{
            final Socket client=socket.accept();client.setSoTimeout(4000);
            try{
                clients.put(client,clientTimeouts.schedule(()->closeQuietly(client),60,TimeUnit.SECONDS));
                workers.execute(()->handle(client));
            }catch(RejectedExecutionException full){try{sendError(client.getOutputStream(),503,"music_busy",false);}catch(Exception ignored){}finally{releaseClient(client);}}
        }catch(IOException stopped){if(!running)return;}
    }
    private void handle(Socket client) {
        boolean head=false;
        ResponseOutput output=null;
        try{
            InputStream input=client.getInputStream();output=new ResponseOutput(client.getOutputStream());
            Request request=readRequest(input);head="HEAD".equals(request.method);
            if(!"GET".equals(request.method)&&!head)throw problem(405,"music_method_rejected");
            String host=request.headers.get("host");
            if(!("127.0.0.1:"+boundPort).equals(host)&&!("localhost:"+boundPort).equals(host))throw problem(403,"music_origin_rejected");
            if(request.headers.containsKey("origin")||request.headers.containsKey("sec-fetch-site"))throw problem(403,"music_origin_rejected");
            if(request.target.equals("/status")){sendJson(output,200,statusJson(),head);return;}
            URI target=new URI(request.target);
            if(target.isAbsolute()||target.getRawFragment()!=null||target.getRawAuthority()!=null)throw problem(400,"music_request_invalid");
            String path=target.getRawPath();
            if(path!=null&&path.matches("/audio/"+AUDIO_NAME)){
                if(target.getRawQuery()!=null)throw problem(400,"music_request_invalid");
                serveAudio(output,path.substring(7),request.headers.get("range"),head);return;
            }
            if(head)throw problem(405,"music_method_rejected");
            Map<String,String> query=query(target.getRawQuery());
            Config config=loadConfig();JSONObject result;
            if("/api/library".equals(path)){
                allowQuery(query,"count","offset");
                List<LocalMusicCatalog.Track> tracks=vk(config,LocalMusicRequests.library(config.credentials,number(query,"count",30,1,200),number(query,"offset",0,0,100000)));
                result=tracks(config,"vk-library",tracks);
            }else if("/api/search".equals(path)){
                allowQuery(query,"q","count");String text=query.containsKey("q")?query.get("q"):"";
                if(text.length()>512)throw problem(400,"music_request_invalid");
                int limit=number(query,"count",6,1,30);
                List<LocalMusicCatalog.Track> found=search(config,text,limit);
                result=tracks(config,found.isEmpty()?"muzofond":source(found.get(0).source),found).put("query",text);
            }else if("/api/playlists".equals(path)){
                allowQuery(query,"count");String owner=LocalMusicCatalog.vkCurrentUser(vkBody(config,LocalMusicRequests.currentUser(config.credentials)));
                List<LocalMusicCatalog.Playlist> items=LocalMusicCatalog.vkPlaylists(vkBody(config,LocalMusicRequests.playlists(config.credentials,owner,number(query,"count",20,1,100))),owner);
                JSONArray playlists=new JSONArray();for(LocalMusicCatalog.Playlist item:items)playlists.put(new JSONObject().put("id",item.id).put("owner_id",item.ownerId).put("title",item.title).put("count",item.count));
                result=new JSONObject().put("source","vk").put("playlists",playlists);
            }else if("/api/playlist".equals(path)){
                allowQuery(query,"owner_id","id","count","offset");
                String owner=query.get("owner_id"),id=query.get("id");
                try{LocalMusicRequests.ownerId(owner);LocalMusicRequests.positiveId(id);}catch(IllegalArgumentException invalid){throw problem(400,"music_request_invalid");}
                result=tracks(config,"vk-playlist",vk(config,LocalMusicRequests.playlist(config.credentials,owner,id,number(query,"count",100,1,200),number(query,"offset",0,0,100000))));
            }else throw problem(404,"music_route_missing");
            sendJson(output,200,result.toString(),false);
        }catch(Exception failure){
            int status=failure instanceof Problem?((Problem)failure).status:502;
            String code=failure instanceof Problem?((Problem)failure).code:failure instanceof LocalMusicCatalog.MusicFailure?failure.getMessage():"music_source_unavailable";
            try{if(output!=null&&!output.committed)sendError(output,status,code,head);}catch(IOException ignored){}
        }finally{releaseClient(client);}
    }
    private void releaseClient(Socket client){ScheduledFuture<?> timeout=clients.remove(client);if(timeout!=null)timeout.cancel(false);closeQuietly(client);}

    private List<LocalMusicCatalog.Track> search(Config config,String query,int count) throws Exception {
        try{
            List<LocalMusicCatalog.Track> result;
            if(query.trim().isEmpty()){
                try{result=vk(config,LocalMusicRequests.popular(config.credentials,count,false));}
                catch(Exception unavailable){result=vk(config,LocalMusicRequests.popular(config.credentials,count,true));}
            }else result=vk(config,LocalMusicRequests.search(config.credentials,query,count));
            if(!result.isEmpty())return result;
        }catch(Exception unavailable){/* Preserve the existing search-only public fallback. */}
        for(boolean alternate:new boolean[]{false,true}){
            URI page=LocalMusicRequests.muzofond(query,alternate);
            List<LocalMusicCatalog.Track> result=LocalMusicCatalog.muzofond(text(config,page,null,0),query,count);
            if(!result.isEmpty())return result;
            if(query.trim().isEmpty())break;
        }
        return Collections.emptyList();
    }
    private List<LocalMusicCatalog.Track> vk(Config config,LocalMusicRequests.Request request) throws Exception {
        return LocalMusicCatalog.vkTracks(vkBody(config,request));
    }
    private String vkBody(Config config,LocalMusicRequests.Request request) throws Exception {return text(config,request.endpoint,request,0);}
    private String text(Config config,URI uri,LocalMusicRequests.Request request,int redirects) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        Map<String,String> headers=request==null?Collections.singletonMap("User-Agent","Mozilla/5.0 StationMusic/1.0"):request.headers;
        URI current=uri;
        for(int hop=0;hop<4;hop++)try(Remote response=fetch.transport(config.proxy).open(current,request==null?"GET":"POST",headers,request==null?null:request.body(),deadline)){
            if(isRedirect(response.status)){
                if(request!=null||hop==3)throw problem(502,"music_redirect_rejected");
                current=redirect(config,current,response.header("Location"),null);continue;
            }
            if(response.status!=200)throw problem(502,"music_source_unavailable");
            return new String(readBounded(response.body,MAX_CATALOG_BYTES,deadline),StandardCharsets.UTF_8);
        }
        throw problem(502,"music_source_unavailable");
    }
    private JSONObject tracks(Config config,String source,List<LocalMusicCatalog.Track> items) throws Exception {
        JSONArray tracks=new JSONArray();
        for(LocalMusicCatalog.Track item:items){
            String id=opaque(item);synchronized(catalog){catalog.put(id,item);while(catalog.size()>MAX_TRACKS)catalog.remove(catalog.keySet().iterator().next());}
            boolean available=true;String reason="";
            if(!item.candidateUrl.isEmpty())try{config.urls.audio(item.source,item.candidateUrl);}catch(IllegalArgumentException denied){available=false;reason="music_cdn_not_enrolled";}
            JSONObject track=new JSONObject().put("id",id).put("title",item.title).put("artist",item.artist)
                    .put("duration",item.durationSeconds).put("url","http://127.0.0.1:"+boundPort+"/audio/"+id+".mp3")
                    .put("source",source(item.source)).put("available",available);
            if(!reason.isEmpty())track.put("unavailable_reason",reason);
            tracks.put(track);
        }
        return new JSONObject().put("source",source).put("tracks",tracks);
    }
    private void serveAudio(OutputStream output,String filename,String range,boolean head) throws Exception {
        File file=new File(cacheDir,filename);boolean acquired=false;
        try{
            synchronized(cacheLock){
                if(file.isFile()&&(file.length()==0||file.length()>MAX_AUDIO_BYTES)&&!file.delete())throw problem(503,"music_cache_unavailable");
                if(file.isFile()){inUse.put(filename,inUse.getOrDefault(filename,0)+1);acquired=true;}
            }
            if(!acquired){
                if(!download.tryAcquire())throw problem(503,"music_busy");
                try{
                    synchronized(cacheLock){if(!file.isFile())trimCache(MAX_AUDIO_BYTES);}
                    if(!file.isFile()){
                        LocalMusicCatalog.Track item;synchronized(catalog){item=catalog.get(filename.substring(0,filename.length()-4));}
                        if(item==null)throw problem(404,"music_track_expired");
                        download(loadConfig(),item,file);
                    }
                    synchronized(cacheLock){inUse.put(filename,inUse.getOrDefault(filename,0)+1);acquired=true;}
                }finally{download.release();}
            }
            long length=file.length();long[] extent=parseRange(range,length);
            if(extent==null){sendHeaders(output,416,"Range Not Satisfiable",0,"audio/mpeg",Collections.singletonMap("Content-Range","bytes */"+length));return;}
            Map<String,String> headers=new LinkedHashMap<>();headers.put("Accept-Ranges","bytes");
            if(range!=null)headers.put("Content-Range","bytes "+extent[0]+"-"+extent[1]+"/"+length);
            long count=extent[1]-extent[0]+1;
            sendHeaders(output,range==null?200:206,range==null?"OK":"Partial Content",count,"audio/mpeg",headers);
            if(!head)try(RandomAccessFile input=new RandomAccessFile(file,"r")){
                input.seek(extent[0]);byte[] buffer=new byte[16384];long remaining=count;
                while(remaining>0){int read=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(read<0)throw new EOFException();output.write(buffer,0,read);remaining-=read;}
            }
            file.setLastModified(System.currentTimeMillis());
        }finally{if(acquired)synchronized(cacheLock){int remaining=inUse.getOrDefault(filename,1)-1;if(remaining<=0)inUse.remove(filename);else inUse.put(filename,remaining);}}
    }
    private void download(Config config,LocalMusicCatalog.Track track,File target) throws Exception {
        LocalMusicCatalog.Track resolved=track;
        if(resolved.candidateUrl.isEmpty()&&resolved.source==LocalMusicUrls.Source.VK){
            List<LocalMusicCatalog.Track> candidates=vk(config,LocalMusicRequests.resolve(config.credentials,resolved.key));
            if(candidates.size()!=1||!candidates.get(0).key.equals(resolved.key))throw problem(502,"music_track_unavailable");resolved=candidates.get(0);
        }
        URI current;try{current=config.urls.audio(resolved.source,resolved.candidateUrl);}catch(IllegalArgumentException denied){throw problem(403,"music_cdn_not_enrolled");}
        File part=new File(cacheDir,target.getName()+".part");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        try{
            for(int hop=0;hop<4;hop++)try(Remote response=fetch.transport(config.proxy).open(current,"GET",Collections.singletonMap("User-Agent",config.credentials.userAgent),null,deadline)){
                if(isRedirect(response.status)){if(hop==3)throw problem(502,"music_redirect_rejected");current=redirect(config,current,response.header("Location"),resolved.source);continue;}
                if(response.status!=200)throw problem(502,"music_track_unavailable");
                String type=response.header("Content-Type");
                if(type==null||!(type.toLowerCase(Locale.ROOT).startsWith("audio/")||type.toLowerCase(Locale.ROOT).startsWith("application/octet-stream")))throw problem(502,"music_audio_invalid");
                String declared=response.header("Content-Length");long expected=-1;
                if(declared!=null)try{expected=Long.parseLong(declared);if(expected<1||expected>MAX_AUDIO_BYTES)throw problem(502,"music_audio_too_large");}catch(NumberFormatException invalid){throw problem(502,"music_audio_invalid");}
                long size=0;byte[] buffer=new byte[16384];
                try(OutputStream output=new FileOutputStream(part)){
                    while(true){checkDeadline(deadline);int read=response.body.read(buffer);if(read<0)break;if(read==0)continue;
                        if(size==0&&read>0&&(buffer[0]=='<'||buffer[0]=='{'||buffer[0]=='['))throw problem(502,"music_audio_invalid");
                        size+=read;if(size>MAX_AUDIO_BYTES)throw problem(502,"music_audio_too_large");output.write(buffer,0,read);
                    }
                }
                if(size<3||expected!=-1&&size!=expected)throw problem(502,"music_audio_invalid");
                synchronized(cacheLock){if(!part.renameTo(target))throw problem(503,"music_cache_unavailable");trimCache(0);}
                return;
            }
            throw problem(502,"music_track_unavailable");
        }finally{if(part.isFile())part.delete();}
    }
    private URI redirect(Config config,URI base,String location,LocalMusicUrls.Source source) throws Exception {
        if(location==null||location.length()>LocalMusicUrls.MAX_URL_CHARS)throw problem(502,"music_redirect_rejected");
        try{return source==null?config.urls.page(base.resolve(location).toString()):config.urls.audio(source,base.resolve(location).toString());}
        catch(IllegalArgumentException denied){throw problem(502,"music_redirect_rejected");}
    }
    private Config loadConfig() throws Exception {
        try{
            if(!configFile.isFile())throw problem(503,"music_not_configured");
            if(configFile.length()>MAX_CONFIG_BYTES)throw problem(503,"music_configuration_invalid");
            byte[] bytes;try(InputStream input=new FileInputStream(configFile)){bytes=readBounded(input,MAX_CONFIG_BYTES,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));}
            JSONTokener parser=new JSONTokener(new String(bytes,StandardCharsets.UTF_8));Object parsed=parser.nextValue();
            if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)throw problem(503,"music_configuration_invalid");
            JSONObject config=(JSONObject)parsed;if(config.getInt("version")!=1)throw problem(503,"music_configuration_invalid");
            JSONObject vk=config.getJSONObject("vk");JSONArray hosts=config.getJSONArray("audioHosts");
            if(hosts.length()>64)throw problem(503,"music_configuration_invalid");
            Set<String> enrolled=new HashSet<>();for(int i=0;i<hosts.length();i++)enrolled.add(hosts.getString(i));
            LocalMusicRequests.Credentials credentials=new LocalMusicRequests.Credentials(vk.getString("access_token"),vk.optString("user_agent",LocalMusicRequests.DEFAULT_USER_AGENT),vk.optString("api_version","5.95"));
            return new Config(credentials,new LocalMusicUrls(enrolled),transportProxy(config));
        }catch(Problem fixed){if(fixed.status==503)throw fixed;throw problem(503,"music_configuration_invalid");}catch(Exception invalid){throw problem(503,"music_configuration_invalid");}
    }
    private static Proxy transportProxy(JSONObject config) throws Exception {
        if(!config.has("transport"))return TunnelFetch.PROXY;
        Object mode=config.opt("transport");
        if("tunnel".equals(mode))return TunnelFetch.PROXY;
        if("direct".equals(mode))return Proxy.NO_PROXY;
        throw problem(503,"music_configuration_invalid");
    }
    private long cacheBytes(){synchronized(cacheLock){long count=0;File[] files=cacheDir.listFiles();if(files!=null)for(File file:files)if(file.isFile()&&(file.getName().matches(AUDIO_NAME)||file.getName().matches(AUDIO_NAME+"\\.part")))count+=file.length();return count;}}
    private void cleanParts(){File[] files=cacheDir.listFiles();if(files!=null)for(File file:files)if(file.isFile()&&file.getName().matches(AUDIO_NAME+"\\.part"))file.delete();}
    private void trimCache(long reserve) throws Exception {
        File[] all=cacheDir.listFiles();if(all==null)throw problem(503,"music_cache_unavailable");
        List<File> files=new ArrayList<>();long size=0;
        for(File file:all)if(file.isFile()&&file.getName().matches(AUDIO_NAME)){files.add(file);size+=file.length();}
        files.sort(Comparator.comparingLong(File::lastModified));
        for(File file:files){if(size+reserve<=MAX_CACHE_BYTES)break;long length=file.length();if(!inUse.containsKey(file.getName())&&file.delete())size-=length;}
        if(size+reserve>MAX_CACHE_BYTES)throw problem(503,"music_cache_busy");
    }
    private static long[] parseRange(String text,long length){
        if(length<=0)return null;if(text==null)return new long[]{0,length-1};
        if(!text.matches("bytes=(?:[0-9]{1,19}-[0-9]{0,19}|-[0-9]{1,19})"))return null;
        try{String[] bounds=text.substring(6).split("-",-1);long start,end;
            if(bounds[0].isEmpty()){long suffix=Long.parseLong(bounds[1]);if(suffix<=0)return null;start=Math.max(0,length-suffix);end=length-1;}
            else{start=Long.parseLong(bounds[0]);end=bounds[1].isEmpty()?length-1:Math.min(length-1,Long.parseLong(bounds[1]));}
            return start<0||start>=length||end<start?null:new long[]{start,end};
        }catch(NumberFormatException invalid){return null;}
    }
    private static byte[] readBounded(InputStream input,int limit,long deadline)throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream(Math.min(limit,16384));byte[] buffer=new byte[8192];int total=0;
        while(true){checkDeadline(deadline);int read=input.read(buffer);if(read<0)return output.toByteArray();if(read==0)continue;total+=read;if(total>limit)throw problem(502,"music_response_too_large");output.write(buffer,0,read);}
    }
    private static void checkDeadline(long deadline)throws Exception{if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw problem(504,"music_source_timeout");}
    private static String opaque(LocalMusicCatalog.Track track)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest((track.source.name()+":"+track.key).getBytes(StandardCharsets.UTF_8));StringBuilder id=new StringBuilder("track-");
        for(int i=0;i<16;i++)id.append(String.format(Locale.ROOT,"%02x",hash[i]&255));return id.toString();
    }
    private static String source(LocalMusicUrls.Source source){return source==LocalMusicUrls.Source.VK?"vk":"muzofond";}
    private static boolean isRedirect(int code){return code==301||code==302||code==303||code==307||code==308;}
    private static int number(Map<String,String> fields,String name,int fallback,int min,int max)throws Exception{
        if(!fields.containsKey(name))return fallback;try{String text=fields.get(name);if(!text.matches("[0-9]{1,6}"))throw new NumberFormatException();int value=Integer.parseInt(text);if(value<min||value>max)throw new NumberFormatException();return value;}catch(NumberFormatException invalid){throw problem(400,"music_request_invalid");}
    }
    private static void allowQuery(Map<String,String> fields,String...names)throws Exception{Set<String> allowed=new HashSet<>(Arrays.asList(names));for(String name:fields.keySet())if(!allowed.contains(name))throw problem(400,"music_request_invalid");}
    private static Map<String,String> query(String query)throws Exception{
        Map<String,String> values=new HashMap<>();if(query==null||query.isEmpty())return values;
        for(String part:query.split("&",-1)){int split=part.indexOf('=');if(split<1)throw problem(400,"music_request_invalid");
            String name=URLDecoder.decode(part.substring(0,split),"UTF-8"),value=URLDecoder.decode(part.substring(split+1),"UTF-8");
            if(values.put(name,value)!=null)throw problem(400,"music_request_invalid");
            for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))throw problem(400,"music_request_invalid");
        }return values;
    }
    private static Request readRequest(InputStream input)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int state=0;
        while(state<4){int next=input.read();if(next<0)throw problem(400,"music_request_invalid");if(next==0||next>127)throw problem(400,"music_request_invalid");bytes.write(next);if(bytes.size()>8192)throw problem(431,"music_request_too_large");
            state=state==0&&next==13?1:state==1&&next==10?2:state==2&&next==13?3:state==3&&next==10?4:next==13?1:0;
        }
        String[] lines=new String(bytes.toByteArray(),StandardCharsets.US_ASCII).split("\\r\\n");String[] start=lines[0].split(" ",-1);
        if(start.length!=3||!start[1].startsWith("/")||!(start[2].equals("HTTP/1.1")||start[2].equals("HTTP/1.0")))throw problem(400,"music_request_invalid");
        Map<String,String> headers=new HashMap<>();for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon<1)throw problem(400,"music_request_invalid");String name=lines[i].substring(0,colon).toLowerCase(Locale.ROOT);String value=lines[i].substring(colon+1).trim();if(!name.matches("[a-z0-9-]+")||headers.put(name,value)!=null)throw problem(400,"music_request_invalid");}
        if(headers.containsKey("transfer-encoding")||headers.containsKey("content-length")&&!"0".equals(headers.get("content-length")))throw problem(400,"music_request_invalid");
        return new Request(start[0],start[1],headers);
    }
    private static void sendJson(OutputStream output,int status,String body,boolean head)throws IOException{
        byte[] data=body.getBytes(StandardCharsets.UTF_8);sendHeaders(output,status,status==200?"OK":"Error",data.length,"application/json; charset=utf-8",Collections.emptyMap());if(!head)output.write(data);
    }
    private static void sendError(OutputStream output,int status,String code,boolean head)throws IOException{sendJson(output,status,"{\"error\":\""+code+"\"}",head);}
    private static void sendHeaders(OutputStream output,int status,String reason,long length,String type,Map<String,String> extra)throws IOException{
        if(output instanceof ResponseOutput)((ResponseOutput)output).committed=true;
        StringBuilder headers=new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\nContent-Type: ").append(type).append("\r\nContent-Length: ").append(length).append("\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n");
        for(Map.Entry<String,String> entry:extra.entrySet())headers.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");headers.append("\r\n");output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
    }
    private static Problem problem(int status,String code){return new Problem(status,code);}
    private static void closeQuietly(Closeable closeable){try{closeable.close();}catch(IOException ignored){}}
    private static final class Problem extends Exception{final int status;final String code;Problem(int status,String code){super(code);this.status=status;this.code=code;}}
    private static final class Config{final LocalMusicRequests.Credentials credentials;final LocalMusicUrls urls;final Proxy proxy;Config(LocalMusicRequests.Credentials credentials,LocalMusicUrls urls,Proxy proxy){this.credentials=credentials;this.urls=urls;this.proxy=proxy;}}
    private static final class Request{final String method,target;final Map<String,String> headers;Request(String method,String target,Map<String,String> headers){this.method=method;this.target=target;this.headers=headers;}}
    private static final class ResponseOutput extends FilterOutputStream{boolean committed;ResponseOutput(OutputStream output){super(output);}public void write(byte[] bytes,int offset,int count)throws IOException{out.write(bytes,offset,count);}}

    interface Fetch{
        Remote open(URI uri,String method,Map<String,String> headers,byte[] body,long deadline)throws Exception;
        /** Synthetic host sources have no network transport; the real HTTPS adapter selects it explicitly. */
        default Fetch transport(Proxy proxy){return this;}
    }
    static class Remote implements Closeable{
        final int status;final InputStream body;final Map<String,String> headers;private final Runnable cleanup;
        Remote(int status,Map<String,String> headers,InputStream body,Runnable cleanup){this.status=status;this.headers=headers;this.body=body;this.cleanup=cleanup;}
        String header(String name){for(Map.Entry<String,String> entry:headers.entrySet())if(name.equalsIgnoreCase(entry.getKey()))return entry.getValue();return null;}
        public void close(){try{if(body!=null)body.close();}catch(IOException ignored){}finally{if(cleanup!=null)cleanup.run();}}
    }
    static final class TunnelFetch implements Fetch{
        static final Proxy PROXY=new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",18443));
        private final Proxy selectedProxy;
        TunnelFetch(){this(PROXY);}
        private TunnelFetch(Proxy proxy){selectedProxy=proxy;}
        @Override public Fetch transport(Proxy proxy){return new TunnelFetch(proxy);}
        public Remote open(URI uri,String method,Map<String,String> headers,byte[] body,long deadline)throws Exception{
            checkDeadline(deadline);if(!"https".equalsIgnoreCase(uri.getScheme()))throw problem(502,"music_endpoint_rejected");
            HttpsURLConnection connection=(HttpsURLConnection)uri.toURL().openConnection(selectedProxy);
            try{
                int remaining=(int)Math.max(1,Math.min(6000,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
                connection.setConnectTimeout(remaining);connection.setReadTimeout(remaining);connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);connection.setRequestMethod(method);connection.setRequestProperty("Accept-Encoding","identity");
                for(Map.Entry<String,String> header:headers.entrySet())connection.setRequestProperty(header.getKey(),header.getValue());
                // Music catalogs/CDNs must release their CONNECT slot after each
                // response; voice LLM/TTS clients keep their own warm pools.
                connection.setRequestProperty("Connection","close");
                if(body!=null){connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);try(OutputStream output=connection.getOutputStream()){output.write(body);}}
                int status=connection.getResponseCode();Map<String,String> responseHeaders=new HashMap<>();
                for(Map.Entry<String,List<String>> header:connection.getHeaderFields().entrySet())if(header.getKey()!=null&&!header.getValue().isEmpty())responseHeaders.put(header.getKey(),header.getValue().get(0));
                InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();if(input==null)input=new ByteArrayInputStream(new byte[0]);
                return new Remote(status,responseHeaders,input,connection::disconnect);
            }catch(Exception failure){connection.disconnect();throw problem(502,"music_tunnel_unavailable");}
        }
    }
}
