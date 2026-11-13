package local.station.voicebridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.json.JSONObject;

public final class LocalConfigPushHostTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static long now() { return System.currentTimeMillis() / 1000; }
    private static String request(int port, long expiry) {
        return "GET /station-config-sync?&service=quasar-realtime&client=q&session=host-test&sign=station-local-config&ts=" + expiry + "&user=host-test HTTP/1.1\r\n"
                + "Host: station-config.quasar.yandex.net\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"; // gitleaks:allow -- RFC 6455 example WebSocket nonce; not an API credential.
    }
    private static void reject(String request, long current) throws Exception {
        try { LocalConfigPushServer.handshake(request, 17684, current); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("accepted invalid upgrade");
    }
    private static byte[] masked(int opcode, byte[] payload) {
        byte[] frame = new byte[6 + payload.length];
        frame[0]=(byte)(128|opcode); frame[1]=(byte)(128|payload.length);
        byte[] mask={11,22,33,44}; System.arraycopy(mask,0,frame,2,4);
        for(int i=0;i<payload.length;i++)frame[6+i]=(byte)(payload[i]^mask[i%4]);
        return frame;
    }
    private static byte[] exact(InputStream input,int length)throws Exception {
        byte[] result=new byte[length];int offset=0;
        while(offset<length){int count=input.read(result,offset,length-offset);if(count<0)throw new AssertionError("unexpected EOF");offset+=count;}
        return result;
    }
    private static byte[] frame(Socket socket,int opcode)throws Exception {
        byte[] head=exact(socket.getInputStream(),2);
        check((head[0]&255)==(128|opcode),"server opcode");
        check((head[1]&128)==0,"server frames are unmasked");
        int size=head[1]&127;
        if(size==126){byte[] length=exact(socket.getInputStream(),2);size=((length[0]&255)<<8)|(length[1]&255);}
        check(size<=4096,"server frame bounds");return exact(socket.getInputStream(),size);
    }
    private static void operation(Socket socket,String expected)throws Exception {
        JSONObject object=new JSONObject(new String(frame(socket,1),StandardCharsets.UTF_8));
        check(expected.equals(object.getString("operation")),"expected "+expected);
        check("quasar-realtime".equals(object.getString("service")),"native service");
        if(expected.equals("ping"))check(object.getInt("server-interval-sec")==30,"application ping interval");
        else check(object.get("message") instanceof String && object.getString("message").equals("{}"),"message is JSON string");
    }
    private static Socket connect(int port,long expiry)throws Exception {
        return connect(new Socket("127.0.0.1",port),port,expiry);
    }
    private static Socket connect(Socket socket,int port,long expiry)throws Exception {
        socket.setSoTimeout(2000);
        byte[] request=request(port,expiry).getBytes(StandardCharsets.US_ASCII);
        // Exercise bytewise upgrade parsing without relying on packet boundaries.
        socket.getOutputStream().write(request,0,17);socket.getOutputStream().flush();
        socket.getOutputStream().write(request,17,request.length-17);socket.getOutputStream().flush();
        ByteArrayOutputStream header=new ByteArrayOutputStream();int matched=0;
        while(matched<4){int value=socket.getInputStream().read();if(value<0)throw new AssertionError("upgrade refused");header.write(value);int expected=matched%2==0?13:10;matched=value==expected?matched+1:value==13?1:0;check(header.size()<8192,"upgrade bounded");}
        String response=new String(header.toByteArray(),StandardCharsets.US_ASCII);
        check(response.startsWith("HTTP/1.1 101 "),"native upgrade");
        check(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="),"standard WS accept");
        operation(socket,"ping");operation(socket,"update_config");return socket;
    }
    private static void incompleteHead(LocalConfigPushServer server,int port,byte[] prefix,
                                       boolean tls,boolean http,int progress,boolean eof)throws Exception {
        long before=server.status().getLong("rejected");
        try(Socket incomplete=new Socket("127.0.0.1",port)){
            incomplete.setSoTimeout(2500);incomplete.getOutputStream().write(prefix);incomplete.getOutputStream().flush();
            if(eof)incomplete.shutdownOutput();
            check(incomplete.getInputStream().read()==-1,"incomplete header rejected");
        }
        long until=System.nanoTime()+1000000000L;
        while(server.status().getLong("rejected")==before&&System.nanoTime()<until)Thread.sleep(5);
        JSONObject status=server.status(),detail=status.getJSONObject("lastRejectDetails");
        check(status.getString("lastRejectCode").equals(eof?"upgrade_eof":"read_timeout"),"incomplete rejection code");
        check(detail.getInt("headBytes")==prefix.length,"incomplete byte count");
        check(detail.getBoolean("startsTls")==tls,"TLS prefix classification");
        check(detail.getBoolean("startsHttp")==http,"HTTP prefix classification");
        check(detail.getInt("headerTerminatorProgress")==progress&&!detail.getBoolean("headerComplete"),"terminator progress");
        check(detail.getString("stage").equals("header")&&!detail.toString().contains("PRIVATE"),"incomplete diagnostics fixed fields only");
    }
    private static void tlsTransport(String path)throws Exception {
        char[] password="test-only-password".toCharArray();
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(FileInputStream input=new FileInputStream(path)){keys.load(input,password);}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password);
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        SSLContext serverTls=SSLContext.getInstance("TLS");serverTls.init(km.getKeyManagers(),null,null);
        SSLContext clientTls=SSLContext.getInstance("TLS");clientTls.init(null,tm.getTrustManagers(),null);
        LocalConfigPushServer server=new LocalConfigPushServer(serverTls,0);server.start();
        long ready=System.nanoTime()+3000000000L;int port=-1;
        while((port=server.boundPort())<0&&System.nanoTime()<ready)Thread.sleep(5);
        check(port>0&&server.status().getBoolean("tls"),"TLS listener explicitly configured");
        try {
            SSLSocket secure=(SSLSocket)clientTls.getSocketFactory().createSocket(new Socket("127.0.0.1",port),LocalConfigPushServer.HOST,443,true);
            javax.net.ssl.SSLParameters parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(parameters);
            secure.setSoTimeout(2500);secure.startHandshake();
            check(secure.getSession().getCipherSuite().indexOf("NULL")<0,"actual TLS handshake established");
            try(Socket connected=connect(secure,port,now()+60)){
                server.signalRefresh();operation(connected,"update_config");
                connected.getOutputStream().write(masked(9,"TLS-ping".getBytes(StandardCharsets.US_ASCII)));connected.getOutputStream().flush();
                check(Arrays.equals(frame(connected,10),"TLS-ping".getBytes(StandardCharsets.US_ASCII)),"TLS preserves WebSocket frames");
            }
            long before=server.status().getLong("rejected");
            try(Socket idle=new Socket("127.0.0.1",port)){
                idle.setSoTimeout(3500);long started=System.nanoTime();
                int received=0;while(idle.getInputStream().read()!=-1){received++;check(received<1024,"TLS failure alert bounded");}
                check(true,"TLS incomplete handshake closed after optional alert");
                check((System.nanoTime()-started)/1000000<3000,"TLS handshake has absolute deadline");
            }
            long until=System.nanoTime()+1000000000L;
            while(server.status().getLong("rejected")==before&&System.nanoTime()<until)Thread.sleep(5);
            check(server.status().getString("lastRejectCode").equals("tls_handshake_failed"),"fixed TLS failure code");
            check(server.status().getJSONObject("lastRejectDetails").getBoolean("tls"),"fixed TLS failure diagnostic");
            check(server.status().getJSONObject("lastRejectDetails").getString("stage").equals("tls"),"TLS failure stage");
        } finally { server.stop();Arrays.fill(password,'\0'); }
    }
    public static void main(String[] args)throws Exception {
        long current=now();String valid=request(17684,current+3600);
        check(LocalConfigPushServer.handshake(valid,17684,current).equals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="),"exact native query admitted");
        check(LocalConfigPushServer.handshake(valid.replace("Host: station-config.quasar.yandex.net","Host: station-config.quasar.yandex.net:443"),17684,current).length()>0,"production explicit443 host");
        check(LocalConfigPushServer.handshake(valid.replace("Host: station-config.quasar.yandex.net","Host: 127.0.0.1:12345"),12345,current).length()>0,"ephemeral host-test seam");
        reject(valid.replace("Host: station-config.quasar.yandex.net","Host: 127.0.0.1:17684"),current);
        reject(valid.replace("Host: station-config.quasar.yandex.net","Host: other.quasar.yandex.net"),current);
        reject(valid.replace("Host: station-config.quasar.yandex.net","Host: station-config.quasar.yandex.net:17684"),current);
        JSONObject diagnostic=LocalConfigPushServer.handshakeDiagnostics(valid,17684,current);
        for(String key:new String[]{"bounded","pathMatches","queryDecodable","signMatches","serviceMatches","clientMatches","timestampValid","timestampFuture","timestampWithinLease","hostMatches","upgradeMatches","versionMatches","connectionMatches"})
            check(diagnostic.getBoolean(key),"valid fixed diagnostic "+key);
        check(!diagnostic.getBoolean("hasBodyHeaders")&&diagnostic.getInt("unknownQueryKeys")==0,"valid diagnostic counts");
        diagnostic=LocalConfigPushServer.handshakeDiagnostics(valid.replace("&client=q","&client=PRIVATE_CLIENT&PRIVATE_KEY=PRIVATE_VALUE"),17684,current);
        check(!diagnostic.getBoolean("clientMatches")&&diagnostic.getInt("unknownQueryKeys")==1,"invalid query fixed diagnostics");
        check(!diagnostic.toString().contains("PRIVATE")&&!diagnostic.toString().contains("station-local-config")&&!diagnostic.toString().contains("host-test"),"diagnostics never expose values");
        String vendor=valid.replace("service=quasar-realtime&","service=quasar-realtime,messenger-prod&");
        check(LocalConfigPushServer.handshake(vendor,17684,current)!=null,"exact native static service list admitted");
        check(LocalConfigPushServer.handshakeDiagnostics(vendor,17684,current).getBoolean("serviceMatchesVendorDefault"),"known static list classified");
        for(String service:new String[]{"messenger-prod","quasar-realtime,evil","%22quasar-realtime%22","%5B%22quasar-realtime%22%5D",""}) {
            String changed=valid.replace("service=quasar-realtime&", "service="+service+"&");
            reject(changed,current);
            diagnostic=LocalConfigPushServer.handshakeDiagnostics(changed,17684,current);
            check(!diagnostic.getBoolean("serviceMatches"),"nonlocal service never admitted");
            check(diagnostic.getBoolean("serviceMatchesVendorDefault")==service.equals("quasar-realtime,messenger-prod"),"known vendor service list classified");
            check(diagnostic.getBoolean("serviceIsJSONQuotedExpected")==service.startsWith("%22"),"quoted service classified");
            check(diagnostic.getBoolean("serviceIsJSONArrayExpected")==service.startsWith("%5B"),"array service classified");
            check(diagnostic.getBoolean("serviceEmpty")==service.isEmpty(),"empty service classified");
            check(diagnostic.getInt("serviceLength")<=1024&&!diagnostic.toString().contains("messenger-prod"),"service shape only, no raw service");
        }
        diagnostic=LocalConfigPushServer.headDiagnostics(new byte[]{22,3,1,0,45},0);
        check(diagnostic.getBoolean("startsTls")&&!diagnostic.getBoolean("startsHttp"),"TLS ClientHello classified without payload");
        diagnostic=LocalConfigPushServer.failureDiagnostics(new javax.net.ssl.SSLHandshakeException("PRIVATE_TOKEN protocol_version unknown_ca certificate_expired"),"tls");
        check(diagnostic.getString("exceptionClass").equals("SSLHandshakeException")&&diagnostic.getString("stage").equals("tls"),"fixed failure identity");
        check(diagnostic.getBoolean("protocolVersion")&&diagnostic.getBoolean("unknownCa")&&diagnostic.getBoolean("certificateExpired"),"fixed TLS reasons classified");
        check(!diagnostic.getBoolean("badCertificate")&&!diagnostic.toString().contains("PRIVATE"),"TLS reasons never expose raw message");
        diagnostic=LocalConfigPushServer.failureDiagnostics(new javax.net.ssl.SSLException("PRIVATE_SECRET",new java.io.IOException("no shared cipher peer closed")),"PRIVATE_STAGE");
        check(diagnostic.getBoolean("noSharedCipher")&&diagnostic.getBoolean("peerClosed"),"bounded cause chain classified");
        check(diagnostic.getString("stage").equals("other")&&!diagnostic.toString().contains("PRIVATE"),"stage admission and nested message privacy");
        diagnostic=LocalConfigPushServer.failureDiagnostics(new SocketTimeoutException("PRIVATE"),"header");
        check(diagnostic.getBoolean("timeout"),"timeout classified by class without message");
        reject(request(17684,current),current);
        reject(request(17684,current+3606),current);
        reject(valid.replace("ts="+(current+3600),"ts=2147483648"),current);
        reject(valid.replace("sign=station-local-config","sign=wrong"),current);
        reject(valid.replace("&client=q","&client=q&ts="+(current+3600)),current);
        reject(valid.replace("Host: station-config.quasar.yandex.net","Host: other:17684"),current);
        reject(valid.replace("GET /station-config-sync?","GET /wrong?"),current);
        reject(valid.replace("\r\n\r\n","\r\nContent-Length: 1\r\n\r\n"),current);
        reject(valid.replace("\r\nUpgrade: websocket","\r\nHost: duplicate\r\nUpgrade: websocket"),current);
        reject(valid.replace("\r\n\r\n","\r\nX-Large: "+new String(new char[8192]).replace('\0','a')+"\r\n\r\n"),current);
        byte[] ping=masked(9,"alive".getBytes(StandardCharsets.US_ASCII));
        check(LocalConfigPushServer.clientFrame(Arrays.copyOf(ping,5),0)==null,"partial frame retained");
        check(Arrays.equals(LocalConfigPushServer.clientFrame(ping,0).payload,"alive".getBytes(StandardCharsets.US_ASCII)),"client mask decoded");
        try{LocalConfigPushServer.clientFrame(new byte[]{(byte)0x81,0},0);throw new AssertionError("accepted unmasked client");}catch(IllegalArgumentException expected){assertions++;}
        try{LocalConfigPushServer.clientFrame(new byte[]{(byte)0x81,(byte)0xfe,0x10,0x01},0);throw new AssertionError("accepted oversized client frame");}catch(IllegalArgumentException expected){assertions++;}
        try{LocalConfigPushServer.clientFrame(new byte[]{(byte)0x89,(byte)0xfe,0,126},0);throw new AssertionError("accepted oversized control frame");}catch(IllegalArgumentException expected){assertions++;}
        LocalConfigPushServer server=new LocalConfigPushServer(0);
        check(!server.status().getBoolean("listening"),"constructor remains passive");
        server.start();int port=-1;
        long ready=System.nanoTime()+3000000000L;
        while((port=server.boundPort())<0&&System.nanoTime()<ready)Thread.sleep(5);
        check(port>0,"host listener ready");
        incompleteHead(server,port,new byte[0],false,false,0,false);
        incompleteHead(server,port,new byte[]{22,3,1,0,45,1,0,0},true,false,0,false);
        incompleteHead(server,port,"GET /PRIVATE_TOKEN\r\n\r".getBytes(StandardCharsets.US_ASCII),false,true,3,true);
        try(Socket invalid=new Socket("127.0.0.1",port)){
            invalid.setSoTimeout(2000);invalid.getOutputStream().write(request(port,now()+60).replace("sign=station-local-config","sign=PRIVATE_SIGN").getBytes(StandardCharsets.US_ASCII));
            check(invalid.getInputStream().read()==-1,"invalid live lease rejected");
        }
        long rejectedAt=System.nanoTime()+1000000000L;
        while(server.status().getLong("rejected")==0&&System.nanoTime()<rejectedAt)Thread.sleep(5);
        check(server.status().getString("lastRejectCode").equals("local_lease"),"fixed live rejection code");
        check(!server.status().getJSONObject("lastRejectDetails").getBoolean("signMatches"),"live rejection boolean");
        check(!server.status().toString().contains("PRIVATE_SIGN"),"private request absent from status");
        try(Socket first=connect(port,now()+60);Socket second=connect(port,now()+60)){
            try(Socket third=new Socket("127.0.0.1",port)){
                third.setSoTimeout(1000);check(third.getInputStream().read()==-1,"bounded client admission");
            }
            server.signalRefresh();operation(first,"update_config");operation(second,"update_config");
            check(server.status().getLong("notifications")>=4,"refresh broadcasts to both clients");
            first.getOutputStream().write(ping,0,3);first.getOutputStream().flush();
            first.getOutputStream().write(ping,3,ping.length-3);first.getOutputStream().flush();
            check(Arrays.equals(frame(first,10),"alive".getBytes(StandardCharsets.US_ASCII)),"protocol ping answered separately");
            long revision=server.status().getLong("requestedRevision");
            server.status();server.status();
            check(server.status().getLong("requestedRevision")==revision,"status has no refresh side effect");
            first.setSoTimeout(200);
            try{first.getInputStream().read();throw new AssertionError("read-only status emitted a frame");}catch(SocketTimeoutException expected){assertions++;}
            first.setSoTimeout(2000);first.getOutputStream().write(masked(8,new byte[0]));first.getOutputStream().flush();
            check(frame(first,8).length==0,"close handshake");
            server.stop();check(second.getInputStream().read()==-1,"stop closes active clients");
        }finally{server.stop();}
        check(!server.status().getBoolean("listening"),"stop closes listener");
        tlsTransport(args[0]);
        System.out.println(assertions+" assertions passed; loopback fake clients only");
    }
}
