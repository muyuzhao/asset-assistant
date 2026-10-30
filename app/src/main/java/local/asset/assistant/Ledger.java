package local.asset.assistant;

import java.math.BigDecimal;
import java.util.*;

/** Decimal, event-based local book. Values are book quantities, never live balances. */
public final class Ledger {
    public static final String[] BUCKETS = {"未分配", "标普预算", "BTC 长期", "机会资金"};
    public static final String[] ASSETS = {"USDT", "BTC", "VOO", "USDC", "CNY", "USD"};
    public record Posting(String bucket, String asset, String amount) {
        public Posting {
            if (!Arrays.asList(BUCKETS).contains(bucket) || !Arrays.asList(ASSETS).contains(asset)) throw new IllegalArgumentException("未知用途或资产");
            amount = decimal(amount).toPlainString();
        }
    }
    public record Event(String id, long time, String title, String note, List<Posting> postings) {
        public Event {
            if (id == null || id.isBlank() || id.length() > 200 || time <= 0 || title == null || title.isBlank() || note == null || note.length() > 2000 || postings == null || postings.isEmpty() || postings.size() > 10) throw new IllegalArgumentException("无效记录");
            postings = List.copyOf(postings);
        }
    }
    public static BigDecimal decimal(String value) {
        if (value == null || !value.matches("-?[0-9]{1,18}(\\.[0-9]{1,18})?")) throw new IllegalArgumentException("请输入最多 18 位小数的普通数字");
        return new BigDecimal(value);
    }
    public static String positive(String value) {
        BigDecimal v = decimal(value);
        if (v.signum() <= 0) throw new IllegalArgumentException("数量必须大于零");
        return v.toPlainString();
    }
    public static BigDecimal balance(Collection<Event> events, String bucket, String asset) {
        BigDecimal value = BigDecimal.ZERO;
        for (Event e : events) for (Posting p : e.postings()) if ((bucket == null || bucket.equals(p.bucket())) && asset.equals(p.asset())) value = value.add(decimal(p.amount()));
        return value;
    }
    public static LinkedHashMap<String, Event> merge(Collection<Event> current, Collection<Event> incoming) {
        LinkedHashMap<String, Event> result = new LinkedHashMap<>();
        for (Event e : current) result.put(e.id(), e);
        for (Event e : incoming) {
            Event old = result.get(e.id());
            if (old != null && !old.equals(e)) throw new IllegalArgumentException("同一记录编号内容冲突，未导入");
            result.put(e.id(), e);
        }
        return result;
    }
    public static Event transfer(String bucket, String target, String asset, String amount, Collection<Event> events) {
        positive(amount);
        if (bucket.equals(target)) throw new IllegalArgumentException("请选择不同用途");
        if (balance(events, bucket, asset).compareTo(decimal(amount)) < 0) throw new IllegalArgumentException("该用途账面余额不足，请先核对期初或入金");
        return new Event(UUID.randomUUID().toString(), System.currentTimeMillis(), "用途分配", bucket + " → " + target,
            List.of(new Posting(bucket, asset, decimal(amount).negate().toPlainString()), new Posting(target, asset, amount)));
    }
}
