package local.station.wifi;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One-use private configuration. Neither toString nor status exposes the credentials. */
public final class TvPrivateConfig {
    static final String ONCE="tv-config-once.json",READY="tv-config.json";
    public final int proxyPort;public final long durationMs;
    public final String nonce,upstreamPackage;
    public final Set<String> tvPackages;
    private final String user,password;
    private final JSONObject serialized;
    private TvPrivateConfig(JSONObject value)throws Exception{
        Set<String> keys=new HashSet<String>(Arrays.asList("nonce","proxyPort","proxyUsername","proxyPassword","tvPackages","upstreamPackage","durationMs"));
        Iterator<String> names=value.keys();while(names.hasNext())if(!keys.contains(names.next()))throw new IllegalArgumentException("unknown_tv_config_field");
        nonce=value.getString("nonce");if(!nonce.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("nonce_required");
        Object rawPort=value.get("proxyPort"),rawDuration=value.get("durationMs");
        if(!(rawPort instanceof Number)||!(rawDuration instanceof Number))throw new IllegalArgumentException("numeric_bounds_required");
        long port=((Number)rawPort).longValue();durationMs=((Number)rawDuration).longValue();
        if(((Number)rawPort).doubleValue()!=port||port<1||port>65535||port==17694||port==17696
                ||((Number)rawDuration).doubleValue()!=durationMs||durationMs<1000||durationMs>300000)
            throw new IllegalArgumentException("bounded_tv_config_required");
        proxyPort=(int)port;
        boolean hasUser=value.has("proxyUsername"),hasPassword=value.has("proxyPassword");
        if(hasUser!=hasPassword)throw new IllegalArgumentException("complete_proxy_credentials_required");
        user=hasUser?credential(value.getString("proxyUsername")):null;password=hasPassword?credential(value.getString("proxyPassword")):null;
        upstreamPackage=value.has("upstreamPackage")?packageName(value.getString("upstreamPackage")):null;
        JSONArray packages=value.getJSONArray("tvPackages");if(packages.length()<1||packages.length()>16)throw new IllegalArgumentException("explicit_tv_packages_required");
        LinkedHashSet<String> selected=new LinkedHashSet<String>();
        for(int i=0;i<packages.length();i++)if(!selected.add(packageName(packages.getString(i))))throw new IllegalArgumentException("duplicate_tv_package");
        tvPackages=Collections.unmodifiableSet(selected);serialized=new JSONObject(value.toString());
    }
    public static TvPrivateConfig parse(String json)throws Exception{
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("bounded_tv_config_required");
        return new TvPrivateConfig(new JSONObject(json));
    }
    public static void configure(File directory)throws Exception{
        File input=new File(directory,ONCE),ready=new File(directory,READY),temporary=new File(directory,"tv-config.tmp");byte[] bytes=null;
        Files.deleteIfExists(ready.toPath());
        try{
            bytes=boundedRead(input);TvPrivateConfig value=parse(new String(bytes,StandardCharsets.UTF_8));
            Files.write(temporary.toPath(),value.serialized.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(),ready.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }finally{
            Files.deleteIfExists(input.toPath());Files.deleteIfExists(temporary.toPath());if(bytes!=null)Arrays.fill(bytes,(byte)0);
        }
    }
    public static TvPrivateConfig take(File directory)throws Exception{
        File input=new File(directory,READY);byte[] bytes=null;
        try{bytes=boundedRead(input);return parse(new String(bytes,StandardCharsets.UTF_8));}
        finally{Files.deleteIfExists(input.toPath());if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    private static byte[] boundedRead(File input)throws Exception{
        if(!input.isFile()||input.length()<2||input.length()>16384)throw new IllegalArgumentException("private_tv_config_missing");
        byte[] bytes=Files.readAllBytes(input.toPath());if(bytes.length>16384)throw new IllegalArgumentException("bounded_tv_config_required");return bytes;
    }
    private static String credential(String text){
        int bytes=text.getBytes(StandardCharsets.UTF_8).length;
        if(bytes<1||bytes>255||text.indexOf('\0')>=0)throw new IllegalArgumentException("proxy_credential_bounds");return text;
    }
    private static String packageName(String text){
        if(text==null||text.length()>200||!text.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))throw new IllegalArgumentException("package_name_required");return text;
    }
    String username(){return user;}String password(){return password;}
    @Override public String toString(){return "TvPrivateConfig[private,redacted]";}
}
