package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.util.*;

/** Real loopback service and HTTPS adapter, with synthetic connections instead of external IO. */
public final class LocalMusicTransportHostTest {
    private static final String CDN="cs1-77v4.vkuseraudio.net";
    private static final byte[] AUDIO="ID3synthetic-direct-music".getBytes(StandardCharsets.US_ASCII);
    private static final List<Proxy> selected=new ArrayList<>();
    private static int assertions;
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("transport_case_"+assertions);}
    public static void main(String[]args)throws Exception{
        URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){
            protected URLConnection openConnection(URL url)throws IOException{throw new IOException("implicit_proxy_selection");}
            protected URLConnection openConnection(URL url,Proxy proxy){selected.add(proxy);return new SyntheticHttps(url);}
        });
        File root=Files.createTempDirectory("station-music-transport-").toFile();
        try(LocalMusicServer server=new LocalMusicServer(root,new LocalMusicServer.TunnelFetch(),0)){
            server.start();
            configure(root,false,null);expectLibrary(server,LocalMusicServer.TunnelFetch.PROXY);
            configure(root,true,"tunnel");expectLibrary(server,LocalMusicServer.TunnelFetch.PROXY);
            configure(root,true,"direct");JSONObject direct=expectLibrary(server,Proxy.NO_PROXY);
            String path=new URI(direct.getJSONArray("tracks").getJSONObject(0).getString("url")).getPath();
            selected.clear();Reply audio=request(server,path);
            check(audio.status==200);check(Arrays.equals(audio.body,AUDIO));
            check(selected.size()==1&&selected.get(0)==Proxy.NO_PROXY);
            for(Object invalid:new Object[]{JSONObject.NULL,"",true,0,"DIRECT","auto","http://proxy.invalid"}){
                configure(root,true,invalid);selected.clear();Reply rejected=request(server,"/api/library");
                check(rejected.status==503);check(selected.isEmpty());
                check(new JSONObject(new String(rejected.body,StandardCharsets.UTF_8)).getString("error").equals("music_configuration_invalid"));
            }
        }finally{erase(root);}
        System.out.println(assertions+" music transport assertions passed; no external network");
    }
    private static JSONObject expectLibrary(LocalMusicServer server,Proxy expected)throws Exception{
        selected.clear();Reply reply=request(server,"/api/library");check(reply.status==200);
        check(selected.size()==1&&selected.get(0).equals(expected));
        return new JSONObject(new String(reply.body,StandardCharsets.UTF_8));
    }
    private static void configure(File root,boolean present,Object mode)throws Exception{
        JSONObject config=new JSONObject().put("version",1).put("vk",new JSONObject().put("access_token","synthetic-token"))
                .put("audioHosts",new JSONArray().put(CDN));
        if(present)config.put("transport",mode);
        Files.write(new File(root,"music-config.json").toPath(),config.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static final class Reply{int status;byte[] body;}
    private static Reply request(LocalMusicServer server,String path)throws Exception{
        try(Socket socket=new Socket("127.0.0.1",server.port())){
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.port()+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=socket.getInputStream().read(buffer))>=0)data.write(buffer,0,count);
            byte[] bytes=data.toByteArray();int split=-1;
            for(int i=0;i+3<bytes.length;i++)if(bytes[i]==13&&bytes[i+1]==10&&bytes[i+2]==13&&bytes[i+3]==10){split=i;break;}
            check(split>0);Reply reply=new Reply();
            reply.status=Integer.parseInt(new String(bytes,0,split,StandardCharsets.US_ASCII).split(" ")[1]);
            reply.body=Arrays.copyOfRange(bytes,split+4,bytes.length);return reply;
        }
    }
    private static void erase(File file)throws IOException{
        if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)erase(child);}
        Files.deleteIfExists(file.toPath());
    }
    private static final class SyntheticHttps extends HttpsURLConnection {
        private final byte[] response;
        private final ByteArrayOutputStream upload=new ByteArrayOutputStream();
        SyntheticHttps(URL url){
            super(url);check("https".equals(url.getProtocol()));
            check(url.getHost().equals("api.vk.com")||url.getHost().equals(CDN));
            response=url.getHost().equals(CDN)?AUDIO:("{\"response\":{\"items\":[{\"id\":1,\"owner_id\":42,\"artist\":\"Synthetic\",\"title\":\"Track\",\"duration\":5,\"url\":\"https://"+CDN+"/track.mp3\"}]}}").getBytes(StandardCharsets.UTF_8);
        }
        @Override public int getResponseCode(){
            check(!getInstanceFollowRedirects());check(!getUseCaches());
            check(getConnectTimeout()>0&&getConnectTimeout()<=6000);check(getReadTimeout()>0&&getReadTimeout()<=6000);
            check(getSSLSocketFactory()==HttpsURLConnection.getDefaultSSLSocketFactory());
            check(getHostnameVerifier()==HttpsURLConnection.getDefaultHostnameVerifier());return 200;
        }
        @Override public OutputStream getOutputStream(){return upload;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(response);}
        @Override public Map<String,List<String>> getHeaderFields(){
            Map<String,List<String>> headers=new HashMap<>();headers.put("Content-Length",Collections.singletonList(Integer.toString(response.length)));
            headers.put("Content-Type",Collections.singletonList(url.getHost().equals(CDN)?"audio/mpeg":"application/json"));return headers;
        }
        @Override public String getCipherSuite(){return "synthetic";}
        @Override public Certificate[] getLocalCertificates(){return null;}
        @Override public Certificate[] getServerCertificates(){return new Certificate[0];}
        @Override public void connect(){}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
    }
}
