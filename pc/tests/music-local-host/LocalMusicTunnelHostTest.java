package local.station.voicebridge;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Exercise the actual adapter with a fake HTTPS connection: no DNS/socket/TLS. */
final class LocalMusicTunnelHostTest {
    private static int assertions;
    private static FakeConnection latest;
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("tunnel_case_"+assertions);}
    static int run()throws Exception{
        URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){
            protected URLConnection openConnection(URL url)throws IOException{throw new IOException("direct_connection_forbidden");}
            protected URLConnection openConnection(URL url,Proxy proxy){
                check(proxy.equals(LocalMusicServer.TunnelFetch.PROXY));latest=new FakeConnection(url);return latest;
            }
        });
        LocalMusicServer.TunnelFetch fetch=new LocalMusicServer.TunnelFetch();
        for(String method:new String[]{"POST","GET"}){
            byte[] body="POST".equals(method)?"synthetic-private-body".getBytes("UTF-8"):null;
            try(LocalMusicServer.Remote response=fetch.open(new URI("https://api.vk.com/method/audio.get"),method,
                    Collections.singletonMap("Connection","keep-alive"),body,System.nanoTime()+TimeUnit.SECONDS.toNanos(5))){
                check(latest.getRequestMethod().equals(method));check(latest.requestHeaders.get("Connection").equals("close"));
                check(!latest.getInstanceFollowRedirects());check(!latest.getUseCaches());
                check(latest.getConnectTimeout()>0&&latest.getConnectTimeout()<=6000);check(latest.getReadTimeout()>0&&latest.getReadTimeout()<=6000);
                check(response.status==200);check(response.body.read()==1);
                check(body==null?latest.upload.size()==0:Arrays.equals(latest.upload.toByteArray(),body));
            }
            check(latest.disconnected);
        }
        try{fetch.open(new URI("http://api.vk.com/method/audio.get"),"GET",Collections.emptyMap(),null,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));throw new AssertionError("plaintext_upstream_allowed");}
        catch(Exception expected){check(expected.getMessage().equals("music_endpoint_rejected"));}
        return assertions;
    }
    private static final class FakeConnection extends HttpsURLConnection {
        final Map<String,String> requestHeaders=new HashMap<>();final ByteArrayOutputStream upload=new ByteArrayOutputStream();boolean disconnected;
        FakeConnection(URL url){super(url);}
        @Override public void setRequestProperty(String key,String value){requestHeaders.put(key,value);}
        @Override public OutputStream getOutputStream(){return upload;}
        @Override public int getResponseCode(){return 200;}
        @Override public Map<String,List<String>> getHeaderFields(){return Collections.singletonMap("Content-Length",Collections.singletonList("1"));}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(new byte[]{1});}
        @Override public String getCipherSuite(){return "synthetic";}
        @Override public Certificate[] getLocalCertificates(){return null;}
        @Override public Certificate[] getServerCertificates(){return new Certificate[0];}
        @Override public void connect(){}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return true;}
    }
}
