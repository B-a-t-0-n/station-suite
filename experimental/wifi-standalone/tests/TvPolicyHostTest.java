package local.station.wifi;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import local.station.relay.SelectiveSocksGateway;
import org.json.JSONObject;

/** Pure host fixtures. No Android service, sockets, resolver or installed package queries. */
public final class TvPolicyHostTest {
    private static int assertions;
    private static final String NONCE="0123456789abcdef0123456789abcdef";
    public static void main(String[] args)throws Exception{
        configuration(new File(args[0]));uidSharing();routes();
        System.out.println(assertions+" assertions passed; offline TV config/UID/routes only; no device/network");
    }
    private static JSONObject config()throws Exception{return new JSONObject().put("nonce",NONCE).put("proxyPort",17698).put("durationMs",90000)
            .put("tvPackages",new org.json.JSONArray(Arrays.asList("tv.first.app")));}
    private static void configuration(File directory)throws Exception{
        JSONObject valid=config();TvPrivateConfig parsed=TvPrivateConfig.parse(valid.toString());
        check(parsed.proxyPort==17698&&parsed.durationMs==90000&&parsed.nonce.equals(NONCE),"bounded config values");
        check(parsed.tvPackages.equals(set("tv.first.app")),"explicit TV packages");
        check(parsed.username()==null&&parsed.password()==null,"no auth means absent complete credential pair");
        JSONObject auth=config().put("proxyUsername","private-user").put("proxyPassword","private-pass").put("upstreamPackage","proxy.owner.app");
        TvPrivateConfig credentials=TvPrivateConfig.parse(auth.toString());
        check(credentials.username().equals("private-user")&&credentials.password().equals("private-pass"),"private credential pair preserved");
        check(!credentials.toString().contains("private-user")&&!credentials.toString().contains("private-pass"),"toString never exposes credentials");
        for(JSONObject bad:new JSONObject[]{config().put("durationMs",0),config().put("durationMs",300001),config().put("durationMs",1000.5),
                config().put("proxyPort",17694),config().put("proxyPort",17696),config().put("proxyPort",65536),config().put("proxyPort","17698"),
                config().put("nonce","stale"),config().put("proxyHost","outside.example"),config().put("proxyUsername","alone"),
                config().put("proxyUsername","").put("proxyPassword","p"),config().put("proxyUsername","u").put("proxyPassword",repeat("x",256)),
                config().put("tvPackages",new org.json.JSONArray()),config().put("tvPackages",new org.json.JSONArray(Arrays.asList("tv.app","tv.app"))),
                config().put("tvPackages",new org.json.JSONArray(Arrays.asList("bad package"))),config().put("upstreamPackage","")})
            rejects(()->TvPrivateConfig.parse(bad.toString()),"invalid/unsafe config rejected");
        rejects(()->TvPrivateConfig.parse(repeat(" ",16385)),"overlong config rejected");
        Files.createDirectories(directory.toPath());
        Files.write(new File(directory,TvPrivateConfig.ONCE).toPath(),auth.toString().getBytes(StandardCharsets.UTF_8));
        TvPrivateConfig.configure(directory);
        check(!new File(directory,TvPrivateConfig.ONCE).exists()&&new File(directory,TvPrivateConfig.READY).isFile(),"one-use input consumed and validated config saved");
        TvPrivateConfig taken=TvPrivateConfig.take(directory);check(taken.nonce.equals(NONCE)&&!new File(directory,TvPrivateConfig.READY).exists(),"service consumes validated config once");
        rejects(()->TvPrivateConfig.take(directory),"second start has no reusable credentials");
        Files.write(new File(directory,TvPrivateConfig.READY).toPath(),valid.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory,TvPrivateConfig.ONCE).toPath(),"{}".getBytes(StandardCharsets.UTF_8));
        rejects(()->TvPrivateConfig.configure(directory),"bad replacement fails");
        check(!new File(directory,TvPrivateConfig.ONCE).exists()&&!new File(directory,TvPrivateConfig.READY).exists(),"bad replacement cannot retain stale ready config");
    }
    private static final class Packages implements TvUidPolicy.Packages {
        final Map<String,Integer> uids=new HashMap<String,Integer>();final Map<Integer,Set<String>> names=new HashMap<Integer,Set<String>>();
        boolean fail;
        void install(String name,int uid){uids.put(name,uid);Set<String> group=names.get(uid);if(group==null){group=new HashSet<String>();names.put(uid,group);}group.add(name);}
        public int uidForPackage(String name)throws Exception{if(fail||!uids.containsKey(name))throw new Exception("unavailable");return uids.get(name);}
        public Set<String> packagesForUid(int uid)throws Exception{if(fail)throw new Exception("unavailable");return names.get(uid);}
    }
    private static void uidSharing()throws Exception{
        Packages packages=new Packages();packages.install("tv.first.app",10050);packages.install("tv.second.app",10051);
        TvUidPolicy p=new TvUidPolicy(set("tv.first.app"),packages,set(0,1000,2000,10005));
        check(p.allowedUids().equals(set(10050))&&p.stillAllowed(10050),"installed selected UID admitted");
        check(!p.stillAllowed(10051),"unselected UID denied");
        packages.install("unselected.shared.app",10050);
        check(!p.stillAllowed(10050),"new unselected shared package revokes UID");
        rejects(()->new TvUidPolicy(set("tv.first.app"),packages,set(0)),"partial shared UID cannot start");
        TvUidPolicy shared=new TvUidPolicy(set("tv.first.app","unselected.shared.app"),packages,set(0));
        check(shared.allowedUids().size()==1&&shared.stillAllowed(10050),"fully selected shared UID supported");
        packages.uids.put("tv.first.app",10052);check(!shared.stillAllowed(10050),"package UID reassignment revokes old UID");
        packages.uids.put("tv.first.app",10050);packages.fail=true;check(!shared.stillAllowed(10050),"mapping read failure denied");packages.fail=false;
        rejects(()->new TvUidPolicy(set("missing.app"),packages,set(0)),"absent package cannot start");
        rejects(()->new TvUidPolicy(set("tv.second.app"),packages,set(10051)),"explicit denied UID cannot start");
        packages.install("system.shared.app",1000);rejects(()->new TvUidPolicy(set("system.shared.app"),packages,set()),"system UID cannot be selected");
        rejects(()->new TvUidPolicy(set(),packages,set()),"empty selected list rejected");
        boolean immutable=false;try{p.allowedUids().add(10051);}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"UID snapshot immutable");
    }
    private static void routes(){
        List<TvRoutePlan.Route> routes=TvRoutePlan.publicIpv4();check(routes.size()>1&&routes.size()<256,"bounded CIDR plan");
        long previous=-1;
        for(TvRoutePlan.Route route:routes){
            check(route.prefix>=1&&route.prefix<=32,"no default route");check(route.start>previous,"routes ordered and disjoint");previous=route.end;
            check(SelectiveSocksGateway.isPublicIpv4(bytes(route.start))&&SelectiveSocksGateway.isPublicIpv4(bytes(route.end)),"route endpoints public");
            check(route.contains(bytes(route.start))&&route.contains(bytes(route.end)),"inclusive route bounds");
        }
        for(String address:new String[]{"192.168.77.1","192.168.77.100","192.168.0.1","192.168.0.100","127.0.0.1","10.111.77.1","10.111.0.1","10.111.77.2","198.18.0.254","0.0.0.0","255.255.255.255"})
            check(!covered(routes,parse(address)),"management/local/diagnostic address never captured by public routes");
        // All scope boundaries plus deterministic random addresses compare with the
        // exact gateway predicate; both sides must reject the same destination.
        Random random=new Random(918377L);
        for(int i=0;i<120000;i++){
            long value=random.nextInt()&0xffffffffL;byte[] address=bytes(value);
            check(covered(routes,address)==SelectiveSocksGateway.isPublicIpv4(address),"route/predicate equivalence");
        }
        for(TvRoutePlan.Route route:routes)for(long point:new long[]{route.start-1,route.start,route.end,route.end+1})
            if(point>=0&&point<=0xffffffffL)check(covered(routes,bytes(point))==SelectiveSocksGateway.isPublicIpv4(bytes(point)),"all generated route boundaries match predicate");
    }
    private static boolean covered(List<TvRoutePlan.Route> routes,byte[] address){for(TvRoutePlan.Route route:routes)if(route.contains(address))return true;return false;}
    private static byte[] bytes(long value){return new byte[]{(byte)(value>>>24),(byte)(value>>>16),(byte)(value>>>8),(byte)value};}
    private static byte[] parse(String address){String[] parts=address.split("\\.");byte[] result=new byte[4];for(int i=0;i<4;i++)result[i]=(byte)Integer.parseInt(parts[i]);return result;}
    @SafeVarargs private static <T> Set<T> set(T... values){return new HashSet<T>(Arrays.asList(values));}
    private static String repeat(String value,int count){StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(value);return result.toString();}
    private interface Attempt {void run()throws Exception;}
    private static void rejects(Attempt attempt,String message){boolean rejected=false;try{attempt.run();}catch(Exception expected){rejected=true;}check(rejected,message);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
