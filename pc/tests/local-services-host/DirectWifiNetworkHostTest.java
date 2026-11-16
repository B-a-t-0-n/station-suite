package local.station.voicebridge;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONObject;

public final class DirectWifiNetworkHostTest {
    private static int assertions;
    private static void check(boolean condition){assertions++;if(!condition)throw new AssertionError("wifi_binding_"+assertions);}
    public static void main(String[]args)throws Exception{
        JSONObject voice=new JSONObject(),music=new JSONObject();
        ConnectivityManager manager=new ConnectivityManager();
        DirectWifiNetwork selection=new DirectWifiNetwork(manager,name->name.equals("config.json")?voice:music);
        selection.refresh();check(manager.registrations==0&&!selection.bound());
        for(Object invalid:new Object[]{JSONObject.NULL,"0",false,0.0,18443}){
            voice.put("lab_proxy_port",invalid);selection.refresh();check(manager.registrations==0);
        }
        voice.put("lab_proxy_port",0);selection.refresh();check(manager.registrations==1);
        check(manager.request.transport==NetworkCapabilities.TRANSPORT_WIFI&&manager.request.capability==NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Network wifi=new Network(1),ethernet=new Network(2);
        ConnectivityManager.NetworkCallback old=manager.callback;
        old.onAvailable(wifi);check(selection.bound()&&manager.bound.equals(wifi));
        selection.refresh();check(manager.registrations==1);
        old.onLost(ethernet);check(selection.bound());
        old.onLost(wifi);check(!selection.bound()&&manager.bound==null);
        old.onAvailable(wifi);check(selection.bound());
        voice.put("lab_proxy_port",18443);selection.refresh();check(!selection.bound()&&manager.bound==null&&manager.unregistrations==1);
        int bindings=manager.bindings;old.onAvailable(wifi);check(manager.bindings==bindings);
        music.put("transport","direct");selection.refresh();check(manager.registrations==2);
        manager.callback.onAvailable(wifi);check(selection.bound());
        // Another process owner changes its binding; teardown must not clear that newer choice.
        manager.bound=ethernet;selection.close();check(manager.bound.equals(ethernet));
        old.onAvailable(wifi);check(manager.bound.equals(ethernet));
        manager.failRegister=true;selection.refresh();check(manager.registrations==2);
        manager.failRegister=false;selection.refresh();check(manager.registrations==3);
        manager.callback.onAvailable(wifi);check(selection.bound());
        selection.close();check(manager.bound==null&&!selection.bound());
        DirectWifiNetwork absent=new DirectWifiNetwork(null,name->new JSONObject());absent.refresh();absent.close();check(!absent.bound());
        System.out.println(assertions+" direct Wi-Fi ownership assertions passed; no network/device");
    }
}
