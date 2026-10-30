package local.asset.assistant;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;
import java.time.*;

final class BinanceReader {
    static final int[] HISTORY_CHANNELS = {0,1,2,4};
    static final String[] CHANNELS = {"股票", "闪兑", "C2C", "账户资产", "BTC 定投"};
    private final Context context;
    private final BookStore book;
    private final BinanceApi api;
    private final Consumer<String> progress;
    private int convertRequests;
    BinanceReader(Context context, BookStore book, Consumer<String> progress) throws Exception {
        this.context = context; this.book = book; this.progress = progress; this.api = new BinanceApi(new Vault(context).read());
    }
    void wallet() throws Exception {
        api.time(); progress.accept("正在读取账户当前资产");
        JSONArray wallets = api.array("/sapi/v1/asset/wallet/balance", "quoteAsset=USDT&needBalanceDetail=true");
        Holdings.parse(wallets);
        JSONObject source = new JSONObject().put("wallets", wallets).put("quote", "USDT").put("savedAt", System.currentTimeMillis()).put("error", "");
        book.updateSource("wallet", source, List.of());
    }
    void sync(int channel, boolean backfill) throws Exception {
        api.time(); long end = api.now();
        long configured = book.settings().optLong("historyStart", end - 180 * SnapshotData.DAY);
        long start = Math.max(1, configured);
        if (channel == 2) start = Math.max(start, Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).minusMonths(6).toInstant().toEpochMilli() + SnapshotData.DAY);
        JSONObject old = book.source("" + channel);
        List<long[]> windows;
        if (backfill) {
            windows = SnapshotData.missing(old, start, end - 2 * SnapshotData.DAY - 1);
            windows.add(new long[]{Math.max(start, end - 2 * SnapshotData.DAY), end});
        } else {
            long since = old == null ? Math.max(start, end - 29 * SnapshotData.DAY) : Math.max(start, old.optLong("end", start) - 2 * SnapshotData.DAY);
            windows = SnapshotData.missing(null, since, end);
        }
        // Revisit unsettled orders even after they fall outside the normal overlap.
        if (channel != 0 && old != null && old.has("rows")) {
            JSONArray rows = old.getJSONArray("rows");
            Set<String> terminal = channel == 2 ? Set.of("COMPLETED","CANCELLED","CANCELED","CANCELLED_BY_SYSTEM") : Set.of("SUCCESS","FAIL","FAILED","EXPIRED");
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i); long time=channel==4?row.optLong("transactionDateTime",0):row.optLong("createTime",0);
                String status=channel==4?row.optString("transactionStatus"):row.optString("orderStatus");
                if(terminal.contains(status) || time<start || time>end) continue;
                boolean covered=false; for(long[] w:windows) if(w[0]<=time&&time<=w[1]) { covered=true; break; }
                if(!covered) windows.add(new long[]{Math.max(start,time-SnapshotData.DAY),Math.min(end,time+SnapshotData.DAY)});
            }
        }
        if (windows.size() > 64) throw new IllegalArgumentException("历史范围过大，请分段回补");
        for (int i=0; i<windows.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            progress.accept(CHANNELS[channel] + " · 区间 " + (i+1) + "/" + windows.size());
            long[] r = windows.get(i); fetchWindow(channel, r[0], r[1], 0);
        }
        if (channel == 0) enrichOrders();
        reprocess(book, channel);
    }
    private void fetchWindow(int channel, long start, long end, int depth) throws Exception {
        if (start > end) return;
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
        if (channel == 1) {
            if (++convertRequests > 40) throw new BinanceApi.ApiFailure("闪兑本次达到请求上限，已完成区间保留，下次继续");
            long last = context.getSharedPreferences("sync-control", 0).getLong("convertAt", 0);
            long wait = Math.max(0, 31000 - (System.currentTimeMillis() - last));
            if (wait > 0) { progress.accept("闪兑历史限频，等待 " + (wait/1000+1) + " 秒后继续"); Thread.sleep(wait); }
            context.getSharedPreferences("sync-control", 0).edit().putLong("convertAt", System.currentTimeMillis()).commit();
            JSONObject response = api.object("/sapi/v1/convert/tradeFlow", "startTime=" + start + "&endTime=" + end + "&limit=1000");
            if (response.getBoolean("moreData")) {
                if (start == end || depth >= 20) throw new BinanceApi.ApiFailure("闪兑同一时间点记录仍被截断，该区间未标记完整");
                long middle = start + (end-start)/2;
                fetchWindow(channel,start,middle,depth+1); fetchWindow(channel,middle+1,end,depth+1); return;
            }
            collect(unique, response.getJSONArray("list"),channel);
        } else {
            boolean complete = false;
            for (int page=1; page<=200; page++) {
                String path = channel == 0 ? "/sapi/v1/equity/trade/history" : channel==4 ? "/sapi/v1/lending/auto-invest/history/list" : "/sapi/v1/c2c/orderMatch/listUserOrderHistory";
                String query = channel == 2 ? "startTimestamp=" + start + "&endTimestamp=" + end + "&page=" + page + "&rows=100" : (channel==0?"symbol=VOO&":"")+"startTime="+start+"&endTime="+end+"&current="+page+"&size=100";
                JSONObject response = api.object(path, query); JSONArray rows = response.getJSONArray(channel == 0 ? "rows" : channel==4 ? "list" : "data");
                if(channel==4)for(int i=0;i<rows.length();i++){long time=rows.getJSONObject(i).getLong("transactionDateTime");if(time<start||time>end)throw new BinanceApi.ApiFailure("定投返回记录超出请求区间，未标记完整");}
                int before = unique.size(); collect(unique,rows,channel);
                if (unique.size() >= response.getLong("total")) { complete = true; break; }
                if (unique.size() == before) throw new BinanceApi.ApiFailure("历史分页未前进，未标记完整");
            }
            if (!complete) throw new BinanceApi.ApiFailure("分页超过容量，未标记完整");
        }
        JSONArray rows = new JSONArray(); for (JSONObject row : unique.values()) rows.put(row);
        JSONObject next = SnapshotData.merge(book.source("" + channel),channel,rows,start,end);
        apply(book, channel, next);
    }
    private void enrichOrders() throws Exception {
        JSONObject source = book.source("0"); if (source == null || !source.has("rows")) return;
        JSONObject orders = source.optJSONObject("orders"); if (orders == null) orders = new JSONObject();
        JSONArray rows = source.getJSONArray("rows"); Set<String> ids = new LinkedHashSet<>();
        for (int i=0; i<rows.length(); i++) ids.add(rows.getJSONObject(i).getString("orderId"));
        for (String id : ids) {
            JSONObject existing = orders.optJSONObject(id);
            if (existing != null && Set.of("FILLED","CANCELED","EXPIRED","REJECTED").contains(existing.optString("status"))) continue;
            progress.accept("正在核对股票整单费用");
            JSONObject order = api.object("/sapi/v1/equity/order/detail", "orderId=" + BinanceApi.encode(id));
            if (!id.equals(order.getString("orderId"))) throw new BinanceApi.ApiFailure("订单详情编号不匹配");
            orders.put(id,order); source.put("orders",orders); apply(book,0,source);
        }
    }
    private static void collect(Map<String, JSONObject> target, JSONArray rows, int channel) throws Exception {
        for (int i=0; i<rows.length(); i++) { JSONObject row = rows.getJSONObject(i); String id = SnapshotData.id(row,channel); row.put(SnapshotData.IDS[channel],id); target.put(id,row); }
    }
    static String pending(BookStore book, Transactions.Item item, JSONObject source) throws Exception {
        if (item.event() == null) return book.contains(item.id()) ? "来源状态与已入账内容不一致，请人工核对" : item.action().equals("skip") ? "" : item.description();
        for (Ledger.Event old : book.events()) if (old.id().equals(item.id())) {
            return old.equals(item.event()) || old.title().equals("已关联手工记录") ? "" : "来源记录发生变化，请人工核对已入账内容";
        }
        JSONObject accepted = source.optJSONObject("acceptSeparate"); if (accepted != null && accepted.optBoolean(item.id())) return "";
        for(Ledger.Event old:book.events()) {
            JSONObject a=Transactions.meta(old), b=Transactions.meta(item.event());
            if(Set.of("convert","recurring").contains(a.optString("kind"))&&Set.of("convert","recurring").contains(b.optString("kind"))
                &&a.optInt("source",-1)!=b.optInt("source",-1)&&Math.abs(old.time()-item.event().time())<=5*60*1000L
                && a.optString("from").equals(b.optString("from"))&&a.optString("to").equals(b.optString("to"))
                &&a.optString("fromAmount").equals(b.optString("fromAmount"))&&a.optString("toAmount").equals(b.optString("toAmount"))) return "可能与另一同步通道的成交重复，请核对后关联";
        }
        for (Ledger.Event old : book.events()) {
            if (old.id().startsWith("api:") || old.id().startsWith("class:") || old.id().startsWith("reverse:") || old.title().equals("用途分配")) continue;
            for (Ledger.Posting p : old.postings()) for (Ledger.Posting q : item.event().postings()) {
                if (!p.asset().equals(q.asset()) || Ledger.decimal(q.amount()).signum() <= 0) continue;
                if (old.title().equals("期初") && old.time() >= item.event().time()) return "存在更晚的期初数量，请确认是否已包含这笔历史";
                if (Math.abs(old.time()-item.event().time()) < SnapshotData.DAY && Ledger.decimal(p.amount()).compareTo(Ledger.decimal(q.amount())) == 0) return "可能与同日手工记录重复，请核对后入账或关联";
            }
        }
        return "";
    }
    static void reprocess(BookStore book, int channel) throws Exception { JSONObject source = book.source("" + channel); if (source != null && source.has("rows")) apply(book,channel,source); }
    static void apply(BookStore book, int channel, JSONObject source) throws Exception {
        synchronized(book) {
            List<Ledger.Event> added = new ArrayList<>(); int pending = 0, excluded = 0;
            for (Transactions.Item item : Transactions.items(channel,source)) {
                if (!pending(book,item,source).isEmpty()) { pending++; continue; }
                if (item.action().equals("skip")) { excluded++; continue; }
                if (item.event() != null && !book.contains(item.id())) added.add(item.event());
            }
            source.put("pendingCount",pending).put("excludedCount",excluded);
            book.updateSource("" + channel,source,added);
        }
    }
    static void failure(BookStore book, String key, Exception e) throws Exception {
        JSONObject source = book.source(key); if (source == null) source = new JSONObject();
        source.put("error",BinanceApi.message(e)).put("attemptAt",System.currentTimeMillis()); book.updateSource(key,source,List.of());
    }
}
