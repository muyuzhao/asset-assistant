package local.asset.assistant;
import android.content.Context;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class SyncCoordinator {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static volatile boolean cancelled;
    private static Thread running;
    private static Runnable owner;
    static synchronized void cancel(Runnable callback) {
        if (!busy || owner != callback) return;
        cancelled = true;
        if (running != null) running.interrupt();
    }
    static volatile String status = "";
    static volatile Consumer<String> observer;
    static boolean busy() { return busy; }
    private static void report(String value) { status = value; Consumer<String> callback = observer; if (callback != null) callback.accept(value); }
    static synchronized boolean start(Context context, BookStore book, int channel, boolean backfill, Runnable done) {
        if (busy) return false;
        String key = "attempt:" + channel + ":" + backfill;
        long previous = context.getSharedPreferences("sync-control",0).getLong(key,0);
        if (System.currentTimeMillis()-previous < 60000) { report("距离上次尝试不足一分钟，请稍后刷新"); return false; }
        busy = true; cancelled = false; owner = done; context.getSharedPreferences("sync-control",0).edit().putLong(key,System.currentTimeMillis()).apply();
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            int failures=0;
            try {
                synchronized(SyncCoordinator.class) { running = Thread.currentThread(); if(cancelled) throw new InterruptedException(); }
                BinanceReader reader = null;
                int[] channels = channel == -1 ? new int[]{5,3,0,2,4,1} : new int[]{channel};
                for (int c : channels) {
                    if(cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    try {
                        if(c==5){report("正在核对三家交易所的已收盘日线");if(!MarketReader.refresh(book,System.currentTimeMillis()).getJSONObject("current").getString("status").equals("ok"))failures++;BackgroundJobs.opportunity(app,book);}
                        else {if(reader==null)reader=new BinanceReader(app,book,SyncCoordinator::report);if (c == 3) reader.wallet(); else reader.sync(c,backfill);}
                    }
                    catch(InterruptedException e) { throw e; }
                    catch(Exception e) { failures++; BinanceReader.failure(book,c==3?"wallet":c==5?"market":""+c,e); }
                }
                report(channel==5?(failures==0?"AHR999 参考值已通过多源核对":"AHR999 本次未通过多源核对，请查看市场参考卡片"):failures == 0 ? "资产与成交已更新" : "部分数据暂未更新，已保留上次结果");
            } catch(InterruptedException e) { report("后台同步已暂停，已完成区间保留"); }
            catch(Exception e) { report("同步未完成：" + BinanceApi.message(e)); }
            finally {
                boolean notify;
                synchronized(SyncCoordinator.class) { notify=!cancelled; running=null; owner=null; busy=false; Thread.interrupted(); }
                report(status); if(notify && done!=null)done.run();
            }
        });
        return true;
    }
}
