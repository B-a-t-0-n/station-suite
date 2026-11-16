package local.station.wifi;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import org.json.JSONObject;

/** Fixed local-only diagnostic run under Quasar UID; never a WAN/API client. */
public final class LocalFlowProbe {
    public static void main(String[] args)throws Exception {
        if(args.length!=1||!args[0].matches("[a-f0-9]{64}"))throw new IllegalArgumentException("public_certificate_sha256_required");
        JSONObject result=new JSONObject();
        try{
            String ip=InetAddress.getByName("scbh.yandex.net").getHostAddress();
            result.put("dnsPinned",DnsPolicy.SERVICE_IP.equals(ip));
            // Never open the real service if this UID bypasses the split DNS.
            if(!DnsPolicy.SERVICE_IP.equals(ip))throw new IllegalStateException("dns_not_local");
            HttpURLConnection http=(HttpURLConnection)new URL("http://scbh.yandex.net/generate_204").openConnection(Proxy.NO_PROXY);
            http.setRequestMethod("HEAD");http.setConnectTimeout(3000);http.setReadTimeout(3000);
            try{result.put("http204",http.getResponseCode()==204);}finally{http.disconnect();}
            if(!DnsPolicy.SERVICE_IP.equals(InetAddress.getByName("quasar.yandex.net").getHostAddress()))throw new IllegalStateException("config_dns_not_local");
            SSLContext tls=SSLContext.getInstance("TLS");
            tls.init(null,new TrustManager[]{new X509TrustManager(){
                public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
                public void checkClientTrusted(X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException();}
                public void checkServerTrusted(X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{
                    try{
                        if(chain.length<1)throw new Exception();
                        byte[] digest=MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                        StringBuilder hash=new StringBuilder();for(byte b:digest)hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                        if(!args[0].equals(hash.toString()))throw new Exception();
                        chain[0].checkValidity();
                    }catch(Exception invalid){throw new java.security.cert.CertificateException("local_certificate_mismatch");}
                }
            }},null);
            HttpsURLConnection config=(HttpsURLConnection)new URL("https://quasar.yandex.net/check_updates").openConnection(Proxy.NO_PROXY);
            config.setSSLSocketFactory(tls.getSocketFactory());
            config.setConnectTimeout(3000);config.setReadTimeout(3000);config.setInstanceFollowRedirects(false);
            try{
                result.put("configHttp200",config.getResponseCode()==200);
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                try(java.io.InputStream input=config.getInputStream()){
                    byte[] buffer=new byte[512];int n;while((n=input.read(buffer))>=0){if(bytes.size()+n>4096)throw new IllegalStateException();bytes.write(buffer,0,n);}
                }
                JSONObject body=new JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
                result.put("hasUpdateFalse",Boolean.FALSE.equals(body.opt("hasUpdate")));
            }finally{config.disconnect();}
        }catch(Exception failure){result.put("failure",failure.getClass().getSimpleName());}
        System.out.println(result.toString());
    }
}
