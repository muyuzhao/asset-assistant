package local.asset.assistant;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small native line icons; no image assets or external UI dependency. */
final class UiIcon extends Drawable {
    private final String kind;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    UiIcon(String kind,int color){this.kind=kind;paint.setColor(color);paint.setStrokeWidth(1.7f);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    @Override public void draw(Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);Path p=new Path();
        switch(kind){
            case "总览"->{c.drawRoundRect(3,3,10,10,2,2,paint);c.drawRoundRect(14,3,21,10,2,2,paint);c.drawRoundRect(3,14,10,21,2,2,paint);c.drawRoundRect(14,14,21,21,2,2,paint);}
            case "成交"->{c.drawRoundRect(5,3,19,21,2,2,paint);c.drawLine(9,8,15,8,paint);c.drawLine(9,12,15,12,paint);c.drawLine(9,16,13,16,paint);}
            case "策略"->{c.drawLine(4,20,4,4,paint);c.drawLine(4,20,21,20,paint);p.moveTo(7,15);p.lineTo(11,10);p.lineTo(15,13);p.lineTo(21,5);c.drawPath(p,paint);}
            case "设置"->{c.drawLine(4,6,20,6,paint);c.drawLine(4,12,20,12,paint);c.drawLine(4,18,20,18,paint);c.drawCircle(9,6,2,paint);c.drawCircle(16,12,2,paint);c.drawCircle(10,18,2,paint);}
            case "refresh"->{c.drawArc(5,5,19,19,45,285,false,paint);p.moveTo(19,4);p.lineTo(19,10);p.lineTo(13,10);c.drawPath(p,paint);}
            case "chevron"->{p.moveTo(9,6);p.lineTo(15,12);p.lineTo(9,18);c.drawPath(p,paint);}
            case "help"->{c.drawCircle(12,12,8.5f,paint);c.drawArc(9,7,15,13,195,250,false,paint);c.drawLine(12,13,12,14,paint);c.drawPoint(12,17,paint);}
        }c.restore();
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
