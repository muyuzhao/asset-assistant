import local.asset.assistant.Ledger;
import java.util.*;
import java.math.BigDecimal;

/** Synthetic fixtures for arithmetic and validation only. */
public class LedgerTest {
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); }
    private static void rejects(Runnable fn, String name) { try { fn.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError(name); }
    public static void main(String[] args) {
        var opening = new Ledger.Event("open", 1, "期初", "", List.of(new Ledger.Posting("未分配", "USDT", "123.000000000000000001")));
        var events = new ArrayList<Ledger.Event>(); events.add(opening);
        var allocation = Ledger.transfer("未分配", "BTC 长期", "USDT", "7", events); events.add(allocation);
        check(Ledger.balance(events, null, "USDT").equals(new BigDecimal("123.000000000000000001")), "分配不会增加总投入且保留精度");
        check(Ledger.balance(events, "BTC 长期", "USDT").compareTo(new BigDecimal("7")) == 0, "用途分配到账");
        var trade = new Ledger.Event("trade", 2, "买入", "", List.of(new Ledger.Posting("BTC 长期", "USDT", "-7"), new Ledger.Posting("BTC 长期", "BTC", "0.00012345")));
        events.add(trade);
        check(Ledger.balance(events, "BTC 长期", "USDT").signum() == 0, "买入扣款");
        check(Ledger.balance(events, "机会资金", "BTC").signum() == 0, "长期底仓不归机会仓");
        check(Ledger.merge(events, events).size() == 3, "重复导入幂等");
        rejects(() -> Ledger.transfer("未分配", "机会资金", "USDT", "117", events), "超额分配被拒绝");
        rejects(() -> Ledger.transfer("未分配", "未分配", "USDT", "1", events), "同用途分配被拒绝");
        rejects(() -> Ledger.positive("-1"), "负数入金被拒绝");
        rejects(() -> Ledger.decimal("1e12"), "科学计数不隐式损失精度");
        var conflict = new Ledger.Event("open", 1, "期初", "", List.of(new Ledger.Posting("未分配", "USDT", "456")));
        rejects(() -> Ledger.merge(events, List.of(conflict)), "同 ID 冲突原子拒绝");
        System.out.println("PASS: precision, allocation, trade, bucket isolation, idempotency and validation");
    }
}
