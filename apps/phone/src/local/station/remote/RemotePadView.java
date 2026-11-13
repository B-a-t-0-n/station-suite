package local.station.remote;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
/** Decorative pad; overlaid controls have independent accessible touch targets. */
final class RemotePadView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    RemotePadView(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y);paint.setShader(new RadialGradient(x,y+r*.04f,r,new int[]{0x147560ea,0x147560ea,0x007560ea},new float[]{0,.84f,1},Shader.TileMode.CLAMP));c.drawCircle(x,y+r*.025f,r,paint);paint.setShader(null);paint.setColor(0xffffffff);paint.setStyle(Paint.Style.FILL);c.drawCircle(x,y,r*.94f,paint);paint.setColor(0xffecebf3);paint.setStrokeWidth(getResources().getDisplayMetrics().density);paint.setStyle(Paint.Style.STROKE);c.drawCircle(x,y,r*.94f,paint);c.drawCircle(x,y,r*.43f,paint);paint.setStyle(Paint.Style.FILL);}
}
