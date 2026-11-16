package local.station.wifi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent!=null&&Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                &&context.getSharedPreferences("wifi_mode",0).getBoolean("enabled",false)&&VpnService.prepare(context)==null)
            context.startForegroundService(new Intent(context,WifiRelayService.class).setAction("start").putExtra("durationMs",0L));
    }
}
