package local.asset.assistant;
import org.json.*;
import java.math.*;
import java.time.*;
import java.util.*;
final class MarketTests {
    static void ok(boolean value,String name){if(!value)throw new AssertionError(name);}
    static JSONObject observation(String name,String value,LocalDate day) throws Exception{return new JSONObject().put("name",name).put("success",true).put("formula",MarketData.FORMULA).put("date",day.toString()).put("value",value).put("price","50000");}
    static void run(android.content.Context context) throws Exception {
        LocalDate day=LocalDate.of(2026,10,29);Map<LocalDate,BigDecimal> prices=new HashMap<>();for(int i=0;i<=200;i++)prices.put(day.minusDays(i),new BigDecimal(i%2==0?"100":"400"));
        JSONObject result=MarketData.calculate("Coinbase",prices,day);ok(new BigDecimal(result.getString("cost")).subtract(new BigDecimal("200")).abs().doubleValue()<1e-9,"200日几何均值不是算术均值");
        Map<LocalDate,BigDecimal> scaled=new HashMap<>();prices.forEach((date,price)->scaled.put(date,price.multiply(new BigDecimal("2"))));JSONObject doubled=MarketData.calculate("Coinbase",scaled,day);
        ok(Math.abs(Double.parseDouble(doubled.getString("value"))/Double.parseDouble(result.getString("value"))-2)<1e-10,"全部价格翻倍时指标按公式翻倍");
        prices.remove(day.minusDays(3));try{MarketData.calculate("Coinbase",prices,day);throw new AssertionError("历史缺口应拒绝");}catch(IllegalArgumentException expected){}
        Map<LocalDate,BigDecimal> filtered=new HashMap<>();MarketData.candle(filtered,day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),"999999",day);ok(filtered.isEmpty(),"未收盘当前日不参与计算");
        MarketData.candle(filtered,day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),"100",day);try{MarketData.candle(filtered,day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),"101",day);throw new AssertionError("冲突日线应拒绝");}catch(IllegalArgumentException expected){}
        JSONObject strategy=StrategyRules.draft(new JSONObject());JSONArray sources=new JSONArray().put(observation("Coinbase","0.500",day)).put(observation("Kraken","0.501",day)).put(observation("Bitstamp","0.502",day));
        JSONObject agree=MarketData.consensus(sources,day,strategy);ok(agree.getString("status").equals("ok")&&new BigDecimal(agree.getString("value")).compareTo(new BigDecimal("0.501"))==0,"三源同侧小偏差取中位数");
        JSONObject cached=MarketReader.merge(null,sources,day,strategy,10000);JSONObject failed=MarketReader.merge(cached,new JSONArray(),day,strategy,20000);
        ok(failed.getJSONObject("current").getString("status").equals("unavailable")&&failed.getJSONObject("lastConfirmed").getString("value").equals("0.501")&&failed.getJSONArray("history").length()==1,"来源全失败显示未确认并保留原确认归档");
        JSONArray boundary=new JSONArray().put(observation("Coinbase","0.449",day)).put(observation("Kraken","0.451",day));ok(MarketData.consensus(boundary,day,strategy).getString("status").equals("disagreement"),"跨阈值即使差异小也不确认");
        JSONArray wide=new JSONArray().put(observation("Coinbase","0.5",day)).put(observation("Kraken","0.55",day));ok(MarketData.consensus(wide,day,strategy).getString("status").equals("disagreement"),"同侧但差异超2%仍拒绝");
        JSONArray stale=new JSONArray().put(observation("Coinbase","0.5",day)).put(observation("Kraken","0.5",day.minusDays(1)));ok(MarketData.consensus(stale,day,strategy).getString("status").equals("single"),"旧日期不凑成两源");
        BookStore book=new BookStore(SyncTests.isolated(context,"market"));JSONObject root=new JSONObject().put("current",agree).put("history",new JSONArray().put(agree)).put("attemptAt",day.plusDays(1).atTime(12,0).toInstant(ZoneOffset.UTC).toEpochMilli());
        book.updateSource("market",root,List.of());BookStore restored=new BookStore(SyncTests.isolated(context,"market-restore"));restored.importBook(book.export());ok(restored.source("market").getJSONArray("history").length()==1&&restored.events().isEmpty(),"市场归档可备份恢复且不产生资金");
        long noon=day.plusDays(1).atTime(12,0).toInstant(ZoneOffset.UTC).toEpochMilli();ok(!MarketReader.due(book,noon),"当日日线已核对不重复自动读取");
        ok(FundingMath.share("1500","210","500").compareTo(new BigDecimal("70"))==0,"C2C 按单次实际净到账分配无USD汇率");
        ok(FundingMath.share("1500","200","500").compareTo(new BigDecimal("70"))<0,"C2C 各次成交比例不同");
        try{StrategyRules.thresholds("0.45,0.4,0.35,",false);throw new AssertionError("缺档应拒绝");}catch(IllegalArgumentException expected){}
        JSONObject invalid=new JSONObject(strategy.toString()).put("newFundsPolicy","随便写文字");try{StrategyRules.validateStructured(invalid);throw new AssertionError("未知规则应拒绝");}catch(IllegalArgumentException expected){}
        JSONObject options=new JSONObject(strategy.toString()).put("firstMonth","2026-11");for(StrategyRules.Policy policy:StrategyRules.POLICIES)options.put(policy.key(),policy.ids()[0]);StrategyRules.validateStructured(options);ok(!options.getBoolean("enabled")&&StrategyRules.status(options).contains("已填写"),"结构化建议可识别且不自动启用");
        ok(StrategyRules.nextPayMonth(LocalDate.of(2026,12,31)).equals("2027-01"),"首个发薪月正确跨年");
    }
}
