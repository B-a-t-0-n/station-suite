package local.station.relay;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Real loopback peers only: no Android runtime, DNS or external connect. */
public final class SelectiveSocksGatewayHostTest {
    private static int assertions;
    private static void check(boolean valid, String message) {
        ++assertions; if (!valid) throw new AssertionError(message);
    }
    private static InetAddress loopback() throws IOException { return InetAddress.getByAddress(new byte[]{127,0,0,1}); }
    private static int read(InputStream input) throws IOException { int value=input.read(); if(value<0)throw new EOFException(); return value; }
    private static byte[] exact(InputStream input,int count)throws IOException {
        byte[] result=new byte[count]; int position=0;
        while(position<count){int amount=input.read(result,position,count-position);if(amount<0)throw new EOFException();position+=amount;}
        return result;
    }
    private static byte[] address(int a,int b,int c,int d){return new byte[]{(byte)a,(byte)b,(byte)c,(byte)d};}
    private static SelectiveSocksGateway.Limits limits(int local,int wan,int idle) {
        return new SelectiveSocksGateway.Limits(local,wan,4,600,200,idle,10000,8L<<20);
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket server=new ServerSocket();
        final AtomicInteger accepts=new AtomicInteger(), requests=new AtomicInteger();
        final AtomicLong bytes=new AtomicLong();
        final List<String> destinations=Collections.synchronizedList(new ArrayList<String>());
        final List<Socket> sockets=Collections.synchronizedList(new ArrayList<Socket>());
        final String mode;
        volatile boolean stopped;
        Peer(String mode)throws IOException {
            this.mode=mode;server.bind(new InetSocketAddress(loopback(),0));
            Thread accept=new Thread(()->{
                while(!stopped)try{
                    Socket socket=server.accept();accepts.incrementAndGet();sockets.add(socket);
                    Thread worker=new Thread(()->handle(socket),"fake-socks-peer");worker.setDaemon(true);worker.start();
                }catch(IOException expected){if(!stopped)throw new AssertionError(expected);}
            },"fake-socks-accept");accept.setDaemon(true);accept.start();
        }
        int port(){return server.getLocalPort();}
        void handle(Socket socket) {
            try {
                socket.setSoTimeout(2500);InputStream input=socket.getInputStream();
                byte[] greeting=exact(input,3);
                if(mode.equals("silent")){Thread.sleep(3000);return;}
                if(mode.equals("wrong-method")){socket.getOutputStream().write(new byte[]{5,2});return;}
                int expected=mode.equals("auth")?2:0;
                if(!Arrays.equals(greeting,new byte[]{5,1,(byte)expected}))throw new IOException("greeting");
                socket.getOutputStream().write(new byte[]{5,(byte)expected});
                if(expected==2){
                    if(read(input)!=1)throw new IOException("auth version");
                    String user=new String(exact(input,read(input)),StandardCharsets.UTF_8);
                    String pass=new String(exact(input,read(input)),StandardCharsets.UTF_8);
                    if(!user.equals("upstream-user")||!pass.equals("upstream-pass"))throw new IOException("auth values");
                    socket.getOutputStream().write(new byte[]{1,0});
                }
                byte[] request=exact(input,10);
                if(request[0]!=5||request[1]!=1||request[2]!=0||request[3]!=1)throw new IOException("request");
                destinations.add((request[4]&255)+"."+(request[5]&255)+"."+(request[6]&255)+"."+(request[7]&255)+":"+((request[8]&255)*256+(request[9]&255)));
                requests.incrementAndGet();
                if(mode.equals("reject")){socket.getOutputStream().write(new byte[]{5,5,0,1,127,0,0,1,0,0});return;}
                if(mode.equals("bad-rsv")){socket.getOutputStream().write(new byte[]{5,0,1,1,127,0,0,1,0,0});return;}
                socket.getOutputStream().write(new byte[]{5,0,0,1,127,0,0,1,0,0});
                if(mode.equals("no-read")){Thread.sleep(3000);return;}
                if(mode.equals("half-close"))socket.shutdownOutput();
                byte[] buffer=new byte[16384];int count;
                while((count=input.read(buffer))>=0){
                    bytes.addAndGet(count);
                    if(!mode.equals("half-close"))socket.getOutputStream().write(buffer,0,count);
                }
            }catch(IOException expected){ }
             catch(InterruptedException stop){Thread.currentThread().interrupt();}
            finally{try{socket.close();}catch(IOException ignored){}sockets.remove(socket);}
        }
        public void close()throws IOException {
            stopped=true;server.close();synchronized(sockets){for(Socket socket:sockets)socket.close();}
        }
    }

    private static final class Running implements AutoCloseable {
        final SelectiveSocksGateway.Capability cap=SelectiveSocksGateway.Capability.random();
        final SelectiveSocksGateway gateway;
        final Thread thread;
        volatile Throwable failure;
        Running(Peer local,Peer wan,SelectiveSocksGateway.Limits limits,boolean upstreamAuth)throws Exception {
            SelectiveSocksGateway.SocksHop hop=upstreamAuth
                ?SelectiveSocksGateway.SocksHop.authenticatedLoopback(wan.port(),"upstream-user","upstream-pass")
                :SelectiveSocksGateway.SocksHop.loopback(wan.port());
            gateway=new SelectiveSocksGateway("10.111.77.1",0,local.port(),hop,cap,true,limits);
            thread=new Thread(()->{try{gateway.run();}catch(Throwable error){failure=error;}},"gateway-host-run");
            thread.setDaemon(true);thread.start();long until=System.nanoTime()+2000000000L;
            while(!gateway.isReady()&&failure==null&&System.nanoTime()<until)Thread.sleep(2);
            check(gateway.isReady(),"gateway starts loopback listener");
        }
        Socket socket()throws IOException {Socket socket=new Socket();socket.connect(new InetSocketAddress(loopback(),gateway.boundListenPort()),500);socket.setSoTimeout(2000);return socket;}
        Socket authenticated()throws IOException {
            Socket socket=socket();socket.getOutputStream().write(new byte[]{5,2,0,2});
            check(Arrays.equals(exact(socket.getInputStream(),2),new byte[]{5,2}),"never selects offered no-auth");
            credentials(socket,cap.username(),cap.password());
            check(Arrays.equals(exact(socket.getInputStream(),2),new byte[]{1,0}),"capability accepted");return socket;
        }
        public void close()throws Exception {
            gateway.close();thread.join(1500);check(!thread.isAlive(),"gateway close terminates listener");check(failure==null,"listener exits cleanly");
        }
    }
    private static void credentials(Socket socket,String user,String pass)throws IOException {
        byte[] username=user.getBytes(StandardCharsets.UTF_8),password=pass.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(1);socket.getOutputStream().write(username.length);socket.getOutputStream().write(username);
        socket.getOutputStream().write(password.length);socket.getOutputStream().write(password);socket.getOutputStream().flush();
    }
    private static int connect(Socket socket,byte[] address,int port)throws IOException {
        socket.getOutputStream().write(new byte[]{5,1,0,1});socket.getOutputStream().write(address);
        socket.getOutputStream().write(port>>>8);socket.getOutputStream().write(port);socket.getOutputStream().flush();
        return exact(socket.getInputStream(),10)[1]&255;
    }
    private static void awaitCount(AtomicLong counter,long expected)throws Exception {
        long end=System.nanoTime()+2000000000L;while(counter.get()<expected&&System.nanoTime()<end)Thread.sleep(3);
        check(counter.get()==expected,"all upload bytes arrive after half-close");
    }

    private static void authAndPolicy()throws Exception {
        try(Peer local=new Peer("echo");Peer wan=new Peer("echo");Running run=new Running(local,wan,limits(2,2,1000),false)) {
            try(Socket client=run.socket()){
                client.getOutputStream().write(new byte[]{5,1,0});
                check(Arrays.equals(exact(client.getInputStream(),2),new byte[]{5,(byte)255}),"no-auth engine denied");
                check(client.getInputStream().read()==-1,"no-auth socket closes");
            }
            try(Socket client=run.socket()){
                client.getOutputStream().write(new byte[]{5,1,2});exact(client.getInputStream(),2);
                credentials(client,run.cap.username(),"wrong-password");
                check(Arrays.equals(exact(client.getInputStream(),2),new byte[]{1,1}),"wrong capability denied");
            }
            for(byte[] denied:new byte[][]{address(127,0,0,1),address(10,0,0,1),address(169,254,1,2),address(192,168,0,1),address(100,64,0,1),address(224,1,2,3),address(198,18,0,1),address(203,0,113,1),address(10,111,77,1)}){
                try(Socket client=run.authenticated()){check(connect(client,denied,8080)==2,"non-public/virtual-other-port denied");}
            }
            try(Socket client=run.authenticated()){
                client.getOutputStream().write(new byte[]{5,1,0,3});
                check((exact(client.getInputStream(),10)[1]&255)==8,"domain request denied without DNS");
            }
            try(Socket client=run.authenticated()){
                client.getOutputStream().write(new byte[]{5,3,0,1});
                check((exact(client.getInputStream(),10)[1]&255)==7,"UDP associate denied");
            }
            check(local.accepts.get()==0&&wan.accepts.get()==0,"denials never touch either hop");
        }
    }

    private static void dispatchAndLargeStream()throws Exception {
        try(Peer local=new Peer("echo");Peer wan=new Peer("auth");Running run=new Running(local,wan,limits(2,2,1000),true)){
            try(Socket client=run.authenticated()){
                check(connect(client,address(10,111,77,1),443)==0,"local service accepted");
                byte[] payload="unchanged TLS first flight".getBytes(StandardCharsets.US_ASCII);
                client.getOutputStream().write(payload);check(Arrays.equals(exact(client.getInputStream(),payload.length),payload),"local SNI bytes unchanged");
                client.shutdownOutput();check(client.getInputStream().read()==-1,"local EOF relayed");
            }
            check(local.destinations.equals(Arrays.asList("10.111.77.1:443")),"local exact virtual destination preserved");
            check(wan.accepts.get()==0,"local service never reaches Happ");
            try(Socket client=run.authenticated()){
                check(connect(client,address(1,1,1,1),443)==0,"public IPv4 sent through authenticated hop");
                final int length=2*1024*1024+137;byte[] payload=new byte[length];
                for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*31);
                CountDownLatch writerDone=new CountDownLatch(1);Throwable[] writerFailure=new Throwable[1];
                Thread writer=new Thread(()->{try{client.getOutputStream().write(payload);client.shutdownOutput();}catch(Throwable failure){writerFailure[0]=failure;}finally{writerDone.countDown();}},"large-host-upload");writer.setDaemon(true);writer.start();
                check(Arrays.equals(exact(client.getInputStream(),length),payload),"video-sized stream exceeds old 1MiB budget intact");
                check(client.getInputStream().read()==-1,"large transfer EOF reaches client");
                check(writerDone.await(2,TimeUnit.SECONDS)&&writerFailure[0]==null,"large upload completed");
            }
            check(wan.destinations.equals(Arrays.asList("1.1.1.1:443")),"public numeric destination preserved without resolving");
            check(run.gateway.summary().contains("\"wanOpened\":1"),"sanitized counters report branch");
            check(!run.gateway.summary().contains(run.cap.password()),"summary excludes capability");
        }
    }

    private static void failureAndDeadline()throws Exception {
        for(String mode:new String[]{"wrong-method","reject","bad-rsv","silent"}){
            try(Peer local=new Peer("echo");Peer wan=new Peer(mode);Running run=new Running(local,wan,limits(1,1,200),false);Socket client=run.authenticated()){
                int result;
                try{result=connect(client,address(8,8,8,8),443);}catch(EOFException timeout){result=-1;}
                check(result!=0,"upstream "+mode+" fails closed");
                check(wan.accepts.get()==1&&local.accepts.get()==0,"no alternate hop after "+mode);
            }
        }
        try(Peer local=new Peer("echo");Peer wan=new Peer("echo");Running run=new Running(local,wan,limits(1,1,100),false)){
            try(Socket idle=run.authenticated()){
                check(connect(idle,address(9,9,9,9),443)==0,"idle control connected");
                check(idle.getInputStream().read()==-1,"idle watchdog closes established socket");
            }
            try(Socket stalled=run.socket()){
                stalled.getOutputStream().write(5);check(stalled.getInputStream().read()==-1,"incomplete handshake deadline closes socket");
            }
        }
    }

    private static void capacityAndHalfClose()throws Exception {
        try(Peer local=new Peer("echo");Peer wan=new Peer("echo");Running run=new Running(local,wan,limits(1,1,2000),false);Socket held=run.authenticated()){
            check(connect(held,address(1,0,0,1),443)==0,"WAN slot held");
            try(Socket denied=run.authenticated()){check(connect(denied,address(8,8,4,4),443)==2,"second WAN flow denied at own capacity");}
            try(Socket service=run.authenticated()){check(connect(service,address(10,111,77,1),80)==0,"local capacity remains independent of full WAN");service.shutdownOutput();check(service.getInputStream().read()==-1,"local EOF during WAN load");}
            check(wan.accepts.get()==1,"overcapacity does not contact Happ");
        }
        try(Peer local=new Peer("echo");Peer wan=new Peer("half-close");Running run=new Running(local,wan,limits(1,1,1000),false);Socket client=run.authenticated()){
            check(connect(client,address(1,1,1,1),443)==0,"half-close control connected");
            check(client.getInputStream().read()==-1,"server output half-close reaches client");
            byte[] remaining=new byte[32768];client.getOutputStream().write(remaining);client.shutdownOutput();
            awaitCount(wan.bytes,remaining.length);
        }
    }

    private static void configuration()throws Exception {
        SelectiveSocksGateway.Capability first=SelectiveSocksGateway.Capability.random(),second=SelectiveSocksGateway.Capability.random();
        check(first.username().length()==32&&first.password().length()==64,"capability has 128/256 bits of hex entropy");
        check(!first.password().equals(second.password()),"new capability per instance");
        check(!first.toString().contains(first.password()),"capability toString redacted");
        try{new SelectiveSocksGateway("10.111.77.1",0,1,SelectiveSocksGateway.SocksHop.loopback(2),first,false,limits(1,1,100));throw new AssertionError("missing native auth accepted");}catch(IllegalArgumentException expected){check(true,"native auth support mandatory");}
        check(SelectiveSocksGateway.Limits.defaults().maxBytesPerDirection>Integer.MAX_VALUE,"production video budget exceeds 32bit counter");
        check(SelectiveSocksGateway.isPublicIpv4(address(8,8,8,8)),"public IPv4 classified");
        check(!SelectiveSocksGateway.isPublicIpv4(address(255,255,255,255)),"broadcast denied");
    }
    private static void injectedClockAndBlockedWrite()throws Exception {
        ExecutorService handlers=Executors.newCachedThreadPool(),pumps=Executors.newCachedThreadPool();
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        AtomicLong nanos=new AtomicLong(1000000000L);
        SelectiveSocksGateway.Capability capability=SelectiveSocksGateway.Capability.random();
        try(Peer local=new Peer("echo");Peer wan=new Peer("echo")){
            SelectiveSocksGateway gateway=new SelectiveSocksGateway("10.111.77.1",0,local.port(),
                SelectiveSocksGateway.SocksHop.loopback(wan.port()),capability,true,limits(1,1,1000),
                ()->nanos.get(),handlers,pumps,timer);
            Thread runner=new Thread(()->{try{gateway.run();}catch(IOException failure){throw new AssertionError(failure);}},"injected-gateway");
            runner.setDaemon(true);runner.start();long end=System.nanoTime()+1000000000L;
            while(!gateway.isReady()&&System.nanoTime()<end)Thread.sleep(2);
            check(gateway.isReady(),"injected executors start");
            try(Socket client=new Socket()){
                client.connect(new InetSocketAddress(loopback(),gateway.boundListenPort()),500);client.setSoTimeout(1500);
                client.getOutputStream().write(new byte[]{5,1,2});exact(client.getInputStream(),2);
                nanos.addAndGet(1000000000L);
                check(client.getInputStream().read()==-1,"injected monotonic clock expires partial authentication");
            }finally{gateway.close();runner.join(1000);}
            check(!handlers.isShutdown()&&!pumps.isShutdown()&&!timer.isShutdown(),"injected executors stay caller-owned");
        }finally{handlers.shutdownNow();pumps.shutdownNow();timer.shutdownNow();}
        try(Peer local=new Peer("echo");Peer wan=new Peer("no-read");Running run=new Running(local,wan,limits(1,1,100),false);Socket client=run.authenticated()){
            check(connect(client,address(8,8,8,8),443)==0,"blocked-write control connected");
            CountDownLatch done=new CountDownLatch(1);
            Thread writer=new Thread(()->{
                try{byte[] buffer=new byte[65536];for(int i=0;i<512;i++)client.getOutputStream().write(buffer);}
                catch(IOException expected){ }
                finally{done.countDown();}
            },"blocked-host-upload");writer.setDaemon(true);writer.start();
            check(done.await(2,TimeUnit.SECONDS),"watchdog terminates blocked upload when upstream stops consuming");
        }
    }
    public static void main(String[] args)throws Exception {
        configuration();authAndPolicy();dispatchAndLargeStream();failureAndDeadline();capacityAndHalfClose();injectedClockAndBlockedWrite();
        System.out.println(assertions+" assertions passed; authenticated gateway with real loopback SOCKS peers only; no device or external network");
    }
}
