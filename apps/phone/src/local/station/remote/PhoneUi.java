package local.station.remote;

import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Window;
import android.widget.Button;

/** Shared appearance for native editors without changing their operations. */
final class PhoneUi {
    private PhoneUi() { }
    static void styleDialog(AlertDialog dialog) {
        int purple=0xff7560ea;
        Window window=dialog.getWindow();
        if(window!=null){GradientDrawable surface=new GradientDrawable();surface.setColor(0xfffaf9ff);surface.setCornerRadius(26*dialog.getContext().getResources().getDisplayMetrics().density);window.setBackgroundDrawable(surface);}
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){
            Button button=dialog.getButton(which);if(button!=null){button.setTextColor(purple);button.setAllCaps(false);}
        }
    }
}
