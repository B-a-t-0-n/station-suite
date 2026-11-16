"""Exercise Wi-Fi selection ownership and config transitions without device or sockets."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import unittest
from test_probe_service_lifecycle import ROOT, JAVAC, JAVA, JSON_JAR, JSON_SHA

STUBS = {
    'android/content/Context.java': '''package android.content;public class Context{
        public <T>T getSystemService(Class<T> type){return null;}}
    ''',
    'android/net/Network.java': '''package android.net;public final class Network{
        private final int id;public Network(int id){this.id=id;}
        public boolean equals(Object other){return other instanceof Network&&((Network)other).id==id;}
        public int hashCode(){return id;}}
    ''',
    'android/net/NetworkCapabilities.java': '''package android.net;public final class NetworkCapabilities{
        public static final int TRANSPORT_WIFI=1,NET_CAPABILITY_INTERNET=12;}
    ''',
    'android/net/NetworkRequest.java': '''package android.net;public final class NetworkRequest{
        public int transport=-1,capability=-1;
        public static class Builder{private final NetworkRequest request=new NetworkRequest();
        public Builder addTransportType(int value){request.transport=value;return this;}
        public Builder addCapability(int value){request.capability=value;return this;}
        public NetworkRequest build(){return request;}}}
    ''',
    'android/net/ConnectivityManager.java': '''package android.net;public class ConnectivityManager{
        public NetworkCallback callback;public NetworkRequest request;public Network bound;
        public int registrations,unregistrations,bindings;public boolean failRegister;
        public static class NetworkCallback{public void onAvailable(Network network){} public void onLost(Network network){}}
        public void registerNetworkCallback(NetworkRequest request,NetworkCallback callback){
            if(failRegister)throw new SecurityException();this.request=request;this.callback=callback;registrations++;}
        public void unregisterNetworkCallback(NetworkCallback callback){unregistrations++;if(this.callback==callback)this.callback=null;}
        public boolean bindProcessToNetwork(Network network){bindings++;bound=network;return true;}
        public Network getBoundNetworkForProcess(){return bound;}}
    ''',
    'local/station/voicebridge/PrivateConfig.java': '''package local.station.voicebridge;
        final class PrivateConfig{static org.json.JSONObject read(android.content.Context context,String name){return new org.json.JSONObject();}}
    ''',
}

class DirectWifiTests(unittest.TestCase):
    def test_explicit_direct_only_network_ownership_and_stale_callbacks(self):
        self.assertEqual(hashlib.sha256(JSON_JAR.read_bytes()).hexdigest(), JSON_SHA)
        with tempfile.TemporaryDirectory(prefix='direct-wifi-host-') as temporary:
            target=Path(temporary); sources=[]
            for name, source in STUBS.items():
                path=target/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source,encoding='utf-8');sources.append(path)
            classes=target/'classes';classes.mkdir()
            sources.extend([ROOT/'src/local/station/voicebridge/DirectWifiNetwork.java',
                            Path(__file__).resolve().parent/'local-services-host/DirectWifiNetworkHostTest.java'])
            for command in [[JAVAC,'-encoding','UTF-8','-source','8','-target','8','-cp',JSON_JAR,'-d',classes,*sources],
                            [JAVA,'-cp',str(classes)+os.pathsep+str(JSON_JAR),'local.station.voicebridge.DirectWifiNetworkHostTest']]:
                result=subprocess.run([str(value) for value in command],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            print(result.stdout.strip())

if __name__=='__main__':unittest.main()
