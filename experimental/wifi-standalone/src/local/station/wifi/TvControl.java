package local.station.wifi;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Explicit bounded test entry. Never stops another VPN or touches Wi-Fi/boot preferences. */
final class TvControl {
    private TvControl(){}
    static String command(Context context,String command)throws Exception{
        if("tv_stop".equals(command)){context.stopService(new Intent(context,TvRelayService.class));return "tv_stopping";}
        if(!"tv_configure".equals(command)&&!"tv_start".equals(command))return "invalid_tv_action";
        String guard=guard(context);if(guard!=null)return guard;
        if("tv_configure".equals(command)){TvPrivateConfig.configure(context.getFilesDir());return "tv_configured";}
        Files.deleteIfExists(new File(context.getFilesDir(),"tv-status.json").toPath());
        if(VpnService.prepare(context)!=null)return "consent_required";
        if(!new File(context.getFilesDir(),TvPrivateConfig.READY).isFile())return "tv_configuration_required";
        context.startForegroundService(new Intent(context,TvRelayService.class).setAction("tv_start"));return "tv_starting";
    }
    static String guard(Context context){
        return guard(context,false);
    }
    static String guard(Context context,boolean ignoreOwnTvService){
        try{
            ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);if(cm==null)return "network_state_unavailable";
            Network[] networks=cm.getAllNetworks();if(networks==null)return "network_state_unavailable";
            for(Network network:networks){NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                if(caps==null)return "network_state_unavailable";
                if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "vpn_must_be_off";
            }
            ActivityManager am=context.getSystemService(ActivityManager.class);if(am==null)return "service_state_unavailable";
            List<ActivityManager.RunningServiceInfo> running=am.getRunningServices(64);if(running==null)return "service_state_unavailable";
            for(ActivityManager.RunningServiceInfo service:running){
                String name=service.service.getClassName();
                if(name.equals(WifiRelayService.class.getName())||name.equals(SynOwnerProbeService.class.getName())
                        ||!ignoreOwnTvService&&name.equals(TvRelayService.class.getName()))return "relay_service_must_be_off";
            }
            return null;
        }catch(Exception unavailable){return "service_state_unavailable";}
    }
}
