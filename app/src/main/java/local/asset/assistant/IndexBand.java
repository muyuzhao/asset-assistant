package local.asset.assistant;
import android.content.Context;
import android.graphics.*;
import android.view.View;

final class IndexBand extends View {
    private final double value;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    IndexBand(Context context,double value){super(context);this.value=value;setContentDescription("AHR999 区间：低估≤0.45，积累0.45到1.20，风险≥1.20");}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float density=getResources().getDisplayMetrics().density,w=getWidth(),y=getHeight()/2f,h=6*density,gap=3*density,third=(w-gap*2)/3;
        int[] colors={0xff75aa91,0xffc6d8bd,0xffe8c3a0};for(int i=0;i<3;i++){paint.setColor(colors[i]);float x=i*(third+gap);c.drawRoundRect(x,y-h/2,x+third,y+h/2,h/2,h/2,paint);}
        double fraction=value<=0.45?value/0.45/3:value<1.2?(1+(value-0.45)/0.75)/3:(2+Math.min(1,(value-1.2)/1.2))/3;
        float x=(float)Math.max(5*density,Math.min(w-5*density,w*fraction));paint.setColor(0xffffffff);c.drawCircle(x,y,6*density,paint);paint.setColor(0xff245a49);c.drawCircle(x,y,3.5f*density,paint);
    }
}
