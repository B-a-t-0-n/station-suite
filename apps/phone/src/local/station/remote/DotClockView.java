package local.station.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Schematic clock on the Station's 25 by 16 LED matrix, never a TV preview. */
final class DotClockView extends View {
    private static final String[] DIGITS={"111101101101111","010110010010111","111001111100111","111001111001111","101101111001001"};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String size="middle";
    private double brightness=1;
    DotClockView(Context context){super(context);setSize("middle");}
    void setSize(String value){size=value;setContentDescription("Пример часов 12:34. "+DisplayModel.sizeName(value)+" цифры. Схематичный предпросмотр LED-дисплея");invalidate();}
    void setBrightness(double value){brightness=Math.max(0,Math.min(1,value));invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float pitch=Math.min(getWidth()/29f,getHeight()/20f);
        float left=(getWidth()-25*pitch)/2,top=(getHeight()-16*pitch)/2;
        paint.setColor(0xff1e1b29);canvas.drawRoundRect(left-pitch*1.6f,top-pitch*1.6f,left+26.6f*pitch,top+17.6f*pitch,pitch*2,pitch*2,paint);
        int width="large".equals(size)?4:3,height="small".equals(size)?5:"large".equals(size)?9:7;
        int total=4*width+5,startX=(25-total)/2,startY=(16-height)/2;
        for(int y=0;y<16;y++)for(int x=0;x<25;x++){
            boolean lit=false;int relative=x-startX;
            int[] digits={1,2,3,4};int offset=0;
            for(int i=0;i<4;i++){
                if(i==2)offset+=2;
                if(relative>=offset&&relative<offset+width&&y>=startY&&y<startY+height){
                    int gx=(relative-offset)*3/width,gy=(y-startY)*5/height;
                    lit=DIGITS[digits[i]].charAt(gy*3+gx)=='1';
                }
                offset+=width+1;
            }
            int colon=startX+2*(width+1);
            if(x==colon&&(y==startY+height/3||y==startY+2*height/3))lit=true;
            int level=lit&&brightness>0?(int)(110+145*brightness):45;
            paint.setColor(android.graphics.Color.rgb(level,lit?level:40,lit?Math.min(255,level+5):56));
            canvas.drawCircle(left+(x+.5f)*pitch,top+(y+.5f)*pitch,pitch*.29f,paint);
        }
    }
}
