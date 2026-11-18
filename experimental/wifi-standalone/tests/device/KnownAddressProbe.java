package local.station.finalprobe;
import android.content.Context;
import android.net.*;
import java.net.*;
import org.json.JSONObject;
/** One public literal, explicit Wi-Fi binding, no TLS/data sent. */
public final class KnownAddressProbe {
 public static void main(String[] args)throws Exception {
  if(android.os.Process.myUid()!=2000||args.length!=4)throw new SecurityException();
  if(!args[1].matches("[0-9.]{7,15}")||!args[2].matches("192\\.168\\.0\\.[0-9]{1,3}"))throw new IllegalArgumentException();
  InetAddress target=InetAddress.getByName(args[1]);
  if(target.isAnyLocalAddress()||target.isLoopbackAddress()||target.isSiteLocalAddress()||target.isLinkLocalAddress()||target.isMulticastAddress())throw new IllegalArgumentException();
  android.os.Looper.prepareMainLooper();
  Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);
  Context system=(Context)at.getMethod("getSystemContext").invoke(thread);
  ConnectivityManager cm=system.createPackageContext("com.android.shell",0).getSystemService(ConnectivityManager.class);
  Network chosen=null;
  for(Network n:cm.getAllNetworks())if(Long.toString(n.getNetworkHandle()).equals(args[0]))chosen=n;
  JSONObject out=new JSONObject();Socket socket=new Socket();long begin=android.os.SystemClock.elapsedRealtime();
  try {
   if(chosen==null||!cm.getNetworkCapabilities(chosen).hasTransport(NetworkCapabilities.TRANSPORT_WIFI))throw new IllegalStateException();
   chosen.bindSocket(socket);socket.bind(new InetSocketAddress(InetAddress.getByName(args[2]),Integer.parseInt(args[3])));
   out.put("bound",true).put("sourceVerified",socket.getLocalAddress().getHostAddress().equals(args[2])&&socket.getLocalPort()==Integer.parseInt(args[3]));
   socket.connect(new InetSocketAddress(target,443),1600);out.put("connected",true);
  }catch(Exception e){out.put("connected",false).put("exceptionType",e.getClass().getSimpleName());}
  finally{socket.close();}
  out.put("elapsedMs",android.os.SystemClock.elapsedRealtime()-begin);
  System.out.println("KNOWN_ADDRESS_PROBE="+out.toString());System.exit(0);
 }
}
