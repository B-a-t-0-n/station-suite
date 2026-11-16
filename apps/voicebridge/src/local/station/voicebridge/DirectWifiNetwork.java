package local.station.voicebridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import org.json.JSONObject;

/** Explicit direct transport selects Wi-Fi for this process; other apps/routes are untouched. */
final class DirectWifiNetwork {
    interface Config { JSONObject read(String name) throws Exception; }
    private final ConnectivityManager manager;
    private final Config config;
    private ConnectivityManager.NetworkCallback callback;
    private Network owned;
    DirectWifiNetwork(Context context) {
        this(context.getSystemService(ConnectivityManager.class), name -> PrivateConfig.read(context, name));
    }
    DirectWifiNetwork(ConnectivityManager manager, Config config) { this.manager=manager; this.config=config; }
    synchronized void refresh() {
        if(manager==null)return;
        if(!directRequested()){close();return;}
        if(callback!=null)return;
        callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){
                synchronized(DirectWifiNetwork.this){
                    if(callback!=this)return;
                    try{if(manager.bindProcessToNetwork(network))owned=network;}catch(RuntimeException unavailable){}
                }
            }
            @Override public void onLost(Network network){
                synchronized(DirectWifiNetwork.this){if(callback==this&&network.equals(owned))unbindOwned();}
            }
        };
        try{
            NetworkRequest request=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            manager.registerNetworkCallback(request,callback);
        }catch(RuntimeException unavailable){callback=null;}
    }
    synchronized boolean bound(){
        try{return owned!=null&&owned.equals(manager.getBoundNetworkForProcess());}
        catch(RuntimeException unavailable){return false;}
    }
    synchronized void close(){
        ConnectivityManager.NetworkCallback previous=callback;callback=null;
        if(previous!=null)try{manager.unregisterNetworkCallback(previous);}catch(RuntimeException unavailable){}
        unbindOwned();
    }
    private void unbindOwned(){
        try{if(owned!=null&&owned.equals(manager.getBoundNetworkForProcess()))manager.bindProcessToNetwork(null);}
        catch(RuntimeException unavailable){}
        owned=null;
    }
    private boolean directRequested(){
        boolean direct=false;
        try{
            Object port=config.read("config.json").opt("lab_proxy_port");
            direct=(port instanceof Integer||port instanceof Long)&&((Number)port).longValue()==0;
        }catch(Exception unavailable){}
        try{direct|="direct".equals(config.read("music-config.json").opt("transport"));}
        catch(Exception unavailable){}
        return direct;
    }
}
