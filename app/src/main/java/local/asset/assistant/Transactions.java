package local.asset.assistant;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;

/** Converts confirmed source facts into stable, idempotent events. Unknown facts stay pending. */
final class Transactions {
    record Item(String id, String title, String description, Ledger.Event event, String action, JSONObject raw) {}
    static String number(JSONObject o, String key) throws Exception {
        Object v = o.get(key); if (!(v instanceof String)) throw new IllegalArgumentException("金额不是精确字符串");
        return Ledger.decimal((String) v).stripTrailingZeros().toPlainString();
    }
    static Ledger.Posting posting(String asset, String amount) { return new Ledger.Posting("未分配", asset, amount); }
    static String neg(String amount) { return Ledger.decimal(amount).negate().toPlainString(); }
    static List<Item> items(int channel, JSONObject source) throws Exception {
        List<Item> result = new ArrayList<>(); if (source == null || !source.has("rows")) return result;
        JSONArray rows = source.getJSONArray("rows"); JSONObject confirms = source.optJSONObject("confirmations"); Set<String> fees = new HashSet<>();
        for (int i=0; i<rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); String external = SnapshotData.id(row, channel), id = "api:" + channel + ":" + external;
            try {
                JSONObject meta = new JSONObject().put("source", channel).put("externalId", external);
                JSONObject confirm = confirms == null ? null : confirms.optJSONObject(id);
                if (channel == 0) {
                    String asset = row.getString("symbol"), quote = row.getString("quote"), qty = Ledger.positive(number(row, "qty")), total = Ledger.positive(number(row, "total"));
                    String side = row.getString("side"); if (!Set.of("BUY", "SELL").contains(side)) throw new IllegalArgumentException("未知成交方向"); boolean buy = side.equals("BUY");
                    meta.put("kind", "equity").put("side", side).put("orderId", row.getString("orderId")).put("quote", quote).put("total", total);
                    Ledger.Event event = new Ledger.Event(id, row.getLong("executionAt"), "股票" + (buy ? "买入" : "卖出"), meta.toString(), List.of(posting(asset, buy ? qty : neg(qty)), posting(quote, buy ? neg(total) : total)));
                    result.add(new Item(id, event.title(), qty + " " + asset + " · " + total + " " + quote, event, "", row));
                    String orderId = row.getString("orderId");
                    if (fees.add(orderId)) {
                        JSONObject orders = source.optJSONObject("orders"), order = orders == null ? null : orders.optJSONObject(orderId);
                        String feeId = "api:fee:" + orderId;
                        if (order == null || !Set.of("FILLED", "CANCELED", "EXPIRED", "REJECTED").contains(order.optString("status"))) {
                            result.add(new Item(feeId, "股票订单费用待核对", "订单尚未最终完成或订单详情未取得", null, "", row));
                        } else {
                            String fee = number(order, "fee"); if (Ledger.decimal(fee).signum() < 0) throw new IllegalArgumentException("手续费为负数");
                            if (Ledger.decimal(fee).signum() > 0) {
                                JSONObject feeConfirmation = confirms == null ? null : confirms.optJSONObject(feeId);
                                if (feeConfirmation == null) result.add(new Item(feeId, "确认股票实际手续费", "订单报告 " + fee + " USD；请按账单确认实际扣款资产与数量。整单只记一次。", null, "fee", order));
                                else {
                                    String amount = Ledger.positive(number(feeConfirmation, "amount")), feeAsset = feeConfirmation.getString("asset");
                                    JSONObject feeMeta = new JSONObject().put("kind", "equityFee").put("orderId", orderId).put("reportedUSD", fee);
                                    Ledger.Event feeEvent = new Ledger.Event(feeId, order.getLong("createdAt"), "股票手续费", feeMeta.toString(), List.of(posting(feeAsset, neg(amount))));
                                    result.add(new Item(feeId, "股票手续费", amount + " " + feeAsset, feeEvent, "", order));
                                }
                            }
                        }
                    }
                } else if (channel == 1) {
                    if (!row.getString("orderStatus").equals("SUCCESS")) { result.add(new Item(id, "闪兑未成功", "状态 " + row.optString("orderStatus") + " · 不入账", null, "skip", row)); continue; }
                    String from = row.getString("fromAsset"), to = row.getString("toAsset"), fromAmount = Ledger.positive(number(row,"fromAmount")), toAmount = Ledger.positive(number(row,"toAmount"));
                    if (from.equals(to)) throw new IllegalArgumentException("兑换资产相同");
                    meta.put("kind", "convert").put("from", from).put("to", to).put("fromAmount", fromAmount).put("toAmount", toAmount);
                    Ledger.Event event = new Ledger.Event(id, row.getLong("createTime"), "闪兑成交", meta.toString(), List.of(posting(from, neg(fromAmount)), posting(to,toAmount)));
                    result.add(new Item(id, "闪兑成交", fromAmount + " " + from + " → " + toAmount + " " + to + (to.equals("BTC") ? " · 用途待分类" : ""), event, "", row));
                } else if (channel == 4) {
                    if(!row.getString("transactionStatus").equals("SUCCESS")){result.add(new Item(id,"定投未成功","状态 "+row.optString("transactionStatus")+" · 不入账",null,"skip",row));continue;}
                    String from=row.getString("sourceAsset"),to=row.getString("targetAsset");
                    if(!from.equals("USDT")||!to.equals("BTC"))throw new IllegalArgumentException("此通道仅自动处理 USDT→BTC 定投");
                    String spent=Ledger.positive(number(row,"sourceAssetAmount")),received=Ledger.positive(number(row,"targetAssetAmount"));
                    boolean zeroFee=row.has("transactionFee")&&Ledger.decimal(number(row,"transactionFee")).signum()==0;
                    if(!zeroFee&&confirm==null){result.add(new Item(id,"确认定投净扣款与净到账","定投手续费非零或缺失，请按账单填写含费用的 USDT 总扣款与 BTC 实际净到账。",null,"recurringNet",row));continue;}
                    if(confirm!=null){spent=Ledger.positive(number(confirm,"spent"));received=Ledger.positive(number(confirm,"received"));}
                    meta.put("kind","recurring").put("from",from).put("to",to).put("fromAmount",spent).put("toAmount",received)
                        .put("planId",row.optString("planId")).put("reportedFee",row.optString("transactionFee","未知")).put("feeUnit",row.optString("transactionFeeUnit","未知"));
                    Ledger.Event event=new Ledger.Event(id,row.getLong("transactionDateTime"),"BTC 定投买入",meta.toString(),List.of(new Ledger.Posting("BTC 长期",from,neg(spent)),new Ledger.Posting("BTC 长期",to,received)));
                    result.add(new Item(id,event.title(),spent+" USDT → "+received+" BTC · 长期底仓",event,"",row));
                } else {
                    if (!row.getString("orderStatus").equals("COMPLETED")) { result.add(new Item(id, "C2C 未完成", "状态 " + row.optString("orderStatus") + " · 不入账", null, "skip", row)); continue; }
                    String asset = row.getString("asset"), amount = Ledger.positive(number(row,"amount"));
                    String side = row.getString("tradeType"); if (!Set.of("BUY","SELL").contains(side)) throw new IllegalArgumentException("未知成交方向");
                    boolean zeroKnown = false;
                    if (row.has("commission")) zeroKnown = Ledger.decimal(number(row,"commission")).signum() == 0;
                    else if (row.optString("advertisementRole").equals("TAKER") && row.has("takerCommission")) zeroKnown = Ledger.decimal(number(row,"takerCommission")).signum() == 0;
                    if (!zeroKnown && confirm == null) { result.add(new Item(id, "确认 C2C 实际到账", "手续费口径待核对，请填写实际到账/扣除 " + asset + " 数量。", null, "net", row)); continue; }
                    if (confirm != null) amount = Ledger.positive(number(confirm, "amount"));
                    meta.put("kind", "c2c").put("side", side).put("fiat", row.getString("fiat")).put("fiatAmount", Ledger.positive(number(row,"totalPrice"))).put("asset", asset).put("amount", amount);
                    if (row.has("commission")) meta.put("reportedCommission", row.getString("commission"));
                    if (row.has("takerCommission")) meta.put("reportedTakerCommission", row.getString("takerCommission"));
                    Ledger.Event event = new Ledger.Event(id, row.getLong("createTime"), "C2C " + (side.equals("BUY") ? "买入" : "卖出"), meta.toString(), List.of(posting(asset, side.equals("BUY") ? amount : neg(amount))));
                    result.add(new Item(id, event.title(), meta.getString("fiatAmount") + " " + meta.getString("fiat") + " ↔ " + amount + " " + asset, event, "", row));
                }
            } catch (Exception e) { result.add(new Item(id, "记录待核对", "字段、币种或精度不受支持；未自动入账", null, "", row)); }
        }
        return result;
    }
    static JSONObject meta(Ledger.Event event) { try { return new JSONObject(event.note()); } catch(Exception e) { return new JSONObject(); } }
    static String details(Ledger.Event event) {
        JSONObject m = meta(event); if (!m.has("kind")) return event.note();
        return switch(m.optString("kind")) {
            case "c2c" -> "实付/实收 " + m.optString("fiatAmount") + " " + m.optString("fiat") + " · 外部入金/出金"+c2cRate(m);
            case "convert" -> "资产兑换 · 不计作新投入";
            case "recurring" -> "BTC 定投 · 长期底仓 · 按实际扣款与到账记账";
            case "equity" -> "按实际结算资产 " + m.optString("quote") + " 记账 · 订单手续费另列";
            case "equityFee" -> "整单手续费 · 已人工确认扣款币种";
            case "classification" -> "已分类为 " + m.optString("bucket") + " · 不改变账户总资产";
            case "allocation" -> "对应 C2C 订单分配 " + m.optString("fiatShare") + " CNY";
            default -> "已保留来源及核对依据";
        };
    }
    private static String c2cRate(JSONObject m){try{return "\n本笔实际成本 "+Ledger.decimal(m.getString("fiatAmount")).divide(Ledger.decimal(m.getString("amount")),6,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" "+m.getString("fiat")+" / "+m.getString("asset")+"（含已核对净到账影响）";}catch(Exception e){return "";}}
}
