package local.station.remote;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Consistent stroke icons independent of system emoji fonts. */
final class RemoteIconView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();private final RectF rect=new RectF();private String symbol;private final int color;
    RemoteIconView(Context context,String symbol,int color){super(context);this.symbol=symbol;this.color=color;}
    void setSymbol(String value){if(!symbol.equals(value)){symbol=value;invalidate();}}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float size=Math.min(getWidth(),getHeight())*(isClickable()?.47f:.84f);c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24f,size/24f);paint.setColor(color);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);path.reset();
        switch(symbol){
        case "up":line(c,7,14,12,9,17,14);break;case "down":line(c,7,10,12,15,17,10);break;case "left":line(c,14,7,9,12,14,17);break;case "right":line(c,10,7,15,12,10,17);break;
        case "back":line(c,9,5,3,11,9,17);line(c,3,11,15,11,19,14,19,19);break;case "home":line(c,3,10,12,3,21,10,21,20,15,20,15,14,9,14,9,20,3,20,3,10);break;
        case "menu":for(int i=0;i<3;i++)c.drawLine(4,6+i*6,20,6+i*6,paint);break;
        case "plus":c.drawLine(5,12,19,12,paint);c.drawLine(12,5,12,19,paint);break;case "minus":c.drawLine(5,12,19,12,paint);break;
        case "bluetooth":line(c,7,7,17,17,12,21,12,3,17,7,7,17);break;
        case "link":line(c,10,5,13,2,21,2,21,10,18,13);line(c,14,19,11,22,3,22,3,14,6,11);c.drawLine(8,16,16,8,paint);break;
        case "refresh":rect.set(4,4,20,20);c.drawArc(rect,45,285,false,paint);line(c,16,4,20,7,22,2);break;
        case "apps":for(int x=0;x<2;x++)for(int y=0;y<2;y++){rect.set(3+x*11,3+y*11,10+x*11,10+y*11);c.drawRoundRect(rect,2,2,paint);}break;
        case "settings":c.drawCircle(12,12,7,paint);c.drawCircle(12,12,2.5f,paint);for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);c.drawLine(12,2,12,5,paint);c.restore();}break;
        case "mute":line(c,3,9,7,9,12,5,12,19,7,15,3,15,3,9);c.drawLine(17,9,22,15,paint);c.drawLine(22,9,17,15,paint);break;
        case "previous":c.drawLine(5,5,5,19,paint);triangle(c,19,5,9,12,19,19);break;case "next":c.drawLine(19,5,19,19,paint);triangle(c,5,5,15,12,5,19);break;
        case "playpause":triangle(c,4,5,13,12,4,19);c.drawLine(17,5,17,19,paint);c.drawLine(21,5,21,19,paint);break;
        case "pause":c.drawLine(8,5,8,19,paint);c.drawLine(16,5,16,19,paint);break;
        case "play":case "youtube":triangle(c,7,4,20,12,7,20);break;
        case "music":line(c,9,17,9,5,20,3,20,15);c.drawOval(3,15,9,21,paint);c.drawOval(14,13,20,19,paint);break;
        case "remote":rect.set(6,1,18,23);c.drawRoundRect(rect,5,5,paint);c.drawCircle(12,9,3,paint);c.drawLine(10,17,14,17,paint);break;
        case "clock":c.drawCircle(12,12,9,paint);line(c,12,6,12,12,16,14);break;
        case "screen":rect.set(2,4,22,18);c.drawRoundRect(rect,3,3,paint);c.drawLine(8,22,16,22,paint);c.drawLine(12,18,12,22,paint);break;
        case "ring":rect.set(2,5,22,19);c.drawOval(rect,paint);rect.set(6,8,18,16);c.drawOval(rect,paint);break;
        case "info":c.drawCircle(12,12,9,paint);c.drawPoint(12,7,paint);c.drawLine(12,11,12,17,paint);break;
        case "brand":paint.setStyle(Paint.Style.FILL);path.moveTo(12,1);path.cubicTo(5,1,1,12,2,18);path.cubicTo(4,24,20,24,22,18);path.cubicTo(23,12,19,1,12,1);c.drawPath(path,paint);paint.setColor(0xffffffff);paint.setStrokeWidth(1.8f);paint.setStyle(Paint.Style.STROKE);line(c,7,14,12,8,17,14);break;
        default:c.drawCircle(12,12,8,paint);}
        c.restore();}
    private void triangle(Canvas c,float...p){paint.setStyle(Paint.Style.FILL);path.reset();path.moveTo(p[0],p[1]);path.lineTo(p[2],p[3]);path.lineTo(p[4],p[5]);path.close();c.drawPath(path,paint);paint.setStyle(Paint.Style.STROKE);}
    private void line(Canvas c,float...p){path.reset();path.moveTo(p[0],p[1]);for(int i=2;i<p.length;i+=2)path.lineTo(p[i],p[i+1]);c.drawPath(path,paint);}
}
