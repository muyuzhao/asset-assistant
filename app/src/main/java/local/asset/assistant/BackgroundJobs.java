package local.asset.assistant;
import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import java.time.*;
import org.json.*;

final class BackgroundJobs {
    static void configure(Context context, BookStore book) throws Exception {
        var scheduler = context.getSystemService(JobScheduler.class);
        JSONObject settings = book.settings();
        if(settings.optBoolean("backgroundSync")) scheduler.schedule(new JobInfo.Builder(7001,new ComponentName(context,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(6*60*60*1000L).setPersisted(true).build());
        else scheduler.cancel(7001);
        if(settings.optBoolean("reminders")) scheduler.schedule(new JobInfo.Builder(7002,new ComponentName(context,ReminderJob.class)).setPeriodic(60*60*1000L).setPersisted(true).build());
        else scheduler.cancel(7002);
        if(settings.optBoolean("marketAuto",true)||OpportunityStrategy.running(OpportunityStrategy.state(settings)))scheduler.schedule(new JobInfo.Builder(7003,new ComponentName(context,MarketJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(60*60*1000L).setPersisted(true).build());
        else scheduler.cancel(7003);
    }
    static void opportunity(Context context,BookStore book) throws Exception {
        OpportunityStrategy.refresh(book,System.currentTimeMillis());
        synchronized(book){
            JSONObject settings=book.settings(),s=OpportunityStrategy.state(settings);
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            if(!OpportunityStrategy.running(s)){manager.cancel(7004);return;}
            if(!OpportunityStrategy.dataReady(s,book.source("market"),System.currentTimeMillis()))return;
            if(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED||!manager.areNotificationsEnabled())return;
            manager.createNotificationChannel(new NotificationChannel("opportunity","BTC 机会策略提醒",NotificationManager.IMPORTANCE_DEFAULT));
            if(manager.getNotificationChannel("opportunity").getImportance()==NotificationManager.IMPORTANCE_NONE)return;
            JSONArray signals=s.optJSONArray("signals");if(signals==null)return;
            String today=MarketData.target(System.currentTimeMillis()).toString();JSONArray delivered=OpportunityStrategy.notices(s,System.currentTimeMillis());
            StringBuilder message=new StringBuilder();int pendingToday=0;
            for(int i=0;i<signals.length();i++){JSONObject x=signals.getJSONObject(i);if(x.optString("status").equals("pending")&&x.optString("date").equals(today))pendingToday++;}
            if(pendingToday==0){manager.cancel(7004);return;}
            for(int i=0;i<delivered.length();i++){JSONObject x=delivered.getJSONObject(i);
                if(message.length()>0)message.append("\n");message.append(OpportunityStrategy.signalTitle(x));
            }
            if(delivered.length()==0)return;
            message.append("\n").append(today).append(" UTC 收盘 · 仅提醒，请在 App 核对本轮资金与持仓");
            Intent intent=new Intent(context,MainActivity.class).putExtra("page",2).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open=PendingIntent.getActivity(context,7004,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            manager.notify(7004,new Notification.Builder(context,"opportunity").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("积蓄 · BTC 机会策略").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setAutoCancel(true).build());
            for(int i=0;i<delivered.length();i++)delivered.getJSONObject(i).put("notified",true);
            settings.put(OpportunityStrategy.KEY,s);book.saveSettings(settings);
        }
    }
    static void remind(Context context, BookStore book) throws Exception {
        JSONObject settings=book.settings();
        if(!settings.optBoolean("reminders") || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return;
        ZonedDateTime now=ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        if(now.getHour()<settings.optInt("reminderHour",10))return;
        String today=now.toLocalDate().toString();
        var prefs=context.getSharedPreferences("notifications",0);
        if(today.equals(prefs.getString("lastDay","")))return;
        String message="";
        if(now.getDayOfMonth()==10)message="每月标普投入计划；请按自己的预算核对实际执行结果。";
        if(now.getDayOfWeek()==DayOfWeek.TUESDAY)message+=(message.isEmpty()?"":"\n")+"每周 BTC 定投计划；请核对币安实际执行结果。";
        if(message.isEmpty())return;
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("plans","固定投入提醒",NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open=PendingIntent.getActivity(context,0,new Intent(context,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(7002,new Notification.Builder(context,"plans").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("积蓄 · 今日投入计划").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(open).setAutoCancel(true).build());
        prefs.edit().putString("lastDay",today).apply();
    }
}
