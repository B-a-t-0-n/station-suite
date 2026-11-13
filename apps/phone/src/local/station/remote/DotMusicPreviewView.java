package local.station.remote;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

/** Animated 25 by 16 schematic. It never sends preview commands to the Station. */
final class DotMusicPreviewView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect visible=new Rect();
    private final String kind;
    private final long started=SystemClock.elapsedRealtime();
    private boolean running=true;
    private final ViewTreeObserver.OnScrollChangedListener scroll=()->{if(running&&getLocalVisibleRect(visible))invalidate();};
    DotMusicPreviewView(Context context,String kind){
        super(context);this.kind=kind;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void running(boolean value){running=value;if(value)invalidate();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();getViewTreeObserver().addOnScrollChangedListener(scroll);}
    @Override protected void onDetachedFromWindow(){if(getViewTreeObserver().isAlive())getViewTreeObserver().removeOnScrollChangedListener(scroll);super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE&&running)invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float pitch=Math.min(getWidth()/29f,getHeight()/20f);
        float left=(getWidth()-25*pitch)/2,top=(getHeight()-16*pitch)/2;
        paint.setColor(0xff1e1b29);canvas.drawRoundRect(left-pitch*1.6f,top-pitch*1.6f,left+26.6f*pitch,top+17.6f*pitch,pitch*2,pitch*2,paint);
        long elapsed=SystemClock.elapsedRealtime()-started;
        for(int y=0;y<16;y++)for(int x=0;x<25;x++){
            float level=DotMusicFrames.level(kind,x,y,elapsed);
            int r=Math.round(43+202*level),g=Math.round(39+199*level),b=Math.round(57+198*level);
            paint.setColor(android.graphics.Color.rgb(r,g,b));
            canvas.drawCircle(left+(x+.5f)*pitch,top+(y+.5f)*pitch,pitch*.29f,paint);
        }
        if(running&&ValueAnimator.areAnimatorsEnabled()&&!"clock".equals(kind)&&getWindowVisibility()==VISIBLE&&isShown()&&getLocalVisibleRect(visible))postInvalidateDelayed(33);
    }
}
