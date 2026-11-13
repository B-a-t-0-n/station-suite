package local.station.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** A quiet illustration of Station Max, never a claim about live hardware state. */
final class StationDeviceView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    StationDeviceView(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();float scale=Math.min(getWidth()/120f,getHeight()/180f);
        canvas.translate((getWidth()-120*scale)/2,(getHeight()-180*scale)/2);canvas.scale(scale,scale);
        paint.setShader(null);paint.setColor(0x127560ea);rect.set(12,163,108,175);canvas.drawOval(rect,paint);
        paint.setShader(new LinearGradient(19,0,103,0,new int[]{0xff35343e,0xff55515d,0xff25242c},null,Shader.TileMode.CLAMP));
        rect.set(22,25,98,160);canvas.drawRoundRect(rect,14,14,paint);
        paint.setShader(null);paint.setColor(0x28bfb9d1);paint.setStrokeWidth(.65f);
        for(int x=29;x<95;x+=4)canvas.drawLine(x,49,x,150,paint);
        paint.setColor(0xff292831);rect.set(22,18,98,46);canvas.drawOval(rect,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);paint.setColor(0xffbba1ff);rect.set(28,22,92,39);canvas.drawOval(rect,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xff36343e);rect.set(35,24,85,37);canvas.drawOval(rect,paint);
        paint.setColor(0xffeeebff);
        String[] digits={"111101101101111","010110010010111","111001111100111","111001111001111"};
        float left=34,top=69;
        for(int i=0;i<digits.length;i++){
            if(i==2){canvas.drawCircle(left-2,top+4,1.1f,paint);canvas.drawCircle(left-2,top+10,1.1f,paint);}
            String digit=digits[i];for(int y=0;y<5;y++)for(int x=0;x<3;x++)if(digit.charAt(y*3+x)=='1')canvas.drawCircle(left+x*2.9f,top+y*2.9f,.85f,paint);
            left+=i==1?15:10;
        }
        paint.setColor(0xff2b2931);rect.set(27,150,93,164);canvas.drawRoundRect(rect,7,7,paint);canvas.restore();
    }
}
