package local.station.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;

/** Static explanatory illustration, not telemetry or a device control. */
final class LightRingView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    LightRingView(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float width=Math.min(getWidth()*.7f,getHeight()*1.7f),height=width*.43f,cx=getWidth()/2f,cy=getHeight()/2f;
        rect.set(cx-width/2,cy-height/2,cx+width/2,cy+height/2);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff302c3c);canvas.drawOval(rect,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width*.042f);paint.setColor(0x217560ea);canvas.drawOval(rect,paint);
        paint.setShader(new SweepGradient(cx,cy,new int[]{0xffa68aff,0xff7560ea,0xff8ab6ff,0xffa68aff},null));paint.setStrokeWidth(width*.018f);canvas.drawOval(rect,paint);paint.setShader(null);paint.setStyle(Paint.Style.FILL);
    }
}
