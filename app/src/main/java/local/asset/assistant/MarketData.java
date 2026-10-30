package local.asset.assistant;
import org.json.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Public-market reference values, never asset postings. Formula and UTC day are explicit. */
final class MarketData {
    static final String FORMULA="geom200-power5.84-17.01-v1";
    static final List<String> NAMES=List.of("Coinbase","Kraken","Bitstamp");
    static String ruleKey(JSONObject strategy){return strategy.optString("buy")+"|"+strategy.optString("sell");}
    static LocalDate target(long now){return Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);}
    static String statusLabel(String status){return switch(status){case "ok"->"多源核对通过";case "single"->"仅单源可用，待交叉核对";case "disagreement"->"来源分歧，暂不确认";default->"数据不足，暂不确认";};}
    static BigDecimal positive(Object raw){
        try{BigDecimal n=new BigDecimal(raw.toString());if(n.signum()<=0||n.compareTo(new BigDecimal("1000000000"))>0)throw new IllegalArgumentException();return n;}
        catch(Exception e){throw new IllegalArgumentException("行情数值无效");}
    }
    static void candle(Map<LocalDate,BigDecimal> prices,long seconds,Object close,LocalDate target){
        if(seconds<=0||seconds%86400!=0)throw new IllegalArgumentException("日线时间未按 UTC 对齐");
        LocalDate day=Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate();
        if(day.isAfter(target)||day.isBefore(target.minusDays(200)))return;
        BigDecimal value=positive(close),old=prices.putIfAbsent(day,value);
        if(old!=null&&old.compareTo(value)!=0)throw new IllegalArgumentException("来源同一日返回冲突收盘价");
    }
    static JSONObject calculate(String name,Map<LocalDate,BigDecimal> prices,LocalDate target) throws Exception {
        for(int i=0;i<=200;i++)if(!prices.containsKey(target.minusDays(i)))throw new IllegalArgumentException("缺少连续 201 天已收盘日线");
        JSONObject today=point(prices,target),yesterday=point(prices,target.minusDays(1));
        return today.put("name",name).put("pair","BTC/USD").put("previousDate",yesterday.getString("date"))
            .put("previousValue",yesterday.getString("value")).put("formula",FORMULA).put("days",201).put("success",true);
    }
    private static JSONObject point(Map<LocalDate,BigDecimal> prices,LocalDate day) throws Exception {
        double logs=0;for(int i=0;i<200;i++)logs+=StrictMath.log(prices.get(day.minusDays(i)).doubleValue());
        double cost=StrictMath.exp(logs/200.0);long age=ChronoUnit.DAYS.between(LocalDate.of(2009,1,3),day);
        if(age<=0)throw new IllegalArgumentException("指标日期无效");
        double fitted=StrictMath.pow(10,5.84*StrictMath.log10(age)-17.01),price=prices.get(day).doubleValue();
        double value=(price/cost)*(price/fitted);if(!Double.isFinite(value)||value<=0)throw new IllegalArgumentException("指标计算无效");
        return new JSONObject().put("date",day.toString()).put("value",BigDecimal.valueOf(value).toPlainString())
            .put("price",prices.get(day).toPlainString()).put("cost",BigDecimal.valueOf(cost).toPlainString()).put("fitted",BigDecimal.valueOf(fitted).toPlainString());
    }
    static JSONObject consensus(JSONArray providers,LocalDate target,JSONObject strategy) throws Exception {
        List<BigDecimal> values=new ArrayList<>(),prices=new ArrayList<>();JSONArray accepted=new JSONArray();
        List<BigDecimal> lines=new ArrayList<>(StrategyRules.thresholds(strategy.getString("buy"),false));lines.addAll(StrategyRules.thresholds(strategy.getString("sell"),true));lines.add(new BigDecimal("1.20"));
        String first=null;boolean sameSide=true;Set<String> names=new HashSet<>();
        for(int i=0;i<providers.length();i++){
            JSONObject p=providers.getJSONObject(i);
            if(!p.optBoolean("success")||!target.toString().equals(p.optString("date"))||!FORMULA.equals(p.optString("formula")))continue;
            if(!NAMES.contains(p.optString("name"))||!names.add(p.getString("name")))throw new IllegalArgumentException("行情来源重复或不支持");
            BigDecimal v=positive(p.get("value"));values.add(v);prices.add(positive(p.get("price")));accepted.put(p.getString("name"));
            StringBuilder side=new StringBuilder();for(BigDecimal line:lines)side.append(Integer.signum(v.compareTo(line))).append(',');
            if(first==null)first=side.toString();else if(!first.equals(side.toString()))sameSide=false;
        }
        JSONObject result=new JSONObject().put("date",target.toString()).put("sources",accepted).put("count",values.size()).put("formula",FORMULA).put("ruleKey",ruleKey(strategy));
        if(values.isEmpty())return result.put("status","unavailable");
        values.sort(Comparator.naturalOrder());prices.sort(Comparator.naturalOrder());BigDecimal median=median(values);
        BigDecimal spread=values.get(values.size()-1).subtract(values.get(0)).divide(median,12,RoundingMode.HALF_UP);
        String status=values.size()<2?"single":!sameSide||spread.compareTo(new BigDecimal("0.02"))>0?"disagreement":"ok";
        return result.put("status",status).put("value",median.toPlainString()).put("price",median(prices).toPlainString()).put("spread",spread.toPlainString()).put("sameSide",sameSide);
    }
    private static BigDecimal median(List<BigDecimal> values){int n=values.size();return n%2==1?values.get(n/2):values.get(n/2-1).add(values.get(n/2)).divide(new BigDecimal("2"));}
    static void validate(JSONObject source) throws Exception {
        JSONArray history=source.optJSONArray("history");if(history!=null&&history.length()>400)throw new IllegalArgumentException("市场参考历史超过容量");
        if(history!=null)for(int i=0;i<history.length();i++){JSONObject r=history.getJSONObject(i);LocalDate.parse(r.getString("date"));positive(r.get("value"));}
        JSONArray providers=source.optJSONArray("providers");if(providers!=null&&providers.length()>3)throw new IllegalArgumentException("市场参考来源数量异常");
        for(String key:List.of("current","lastConfirmed")){JSONObject r=source.optJSONObject(key);if(r==null)continue;LocalDate.parse(r.getString("date"));if(!Set.of("ok","single","disagreement","unavailable").contains(r.getString("status")))throw new IllegalArgumentException("市场参考状态异常");if(r.has("value"))positive(r.get("value"));if(r.optString("status").equals("ok")&&(r.optInt("count")<2||!FORMULA.equals(r.optString("formula"))))throw new IllegalArgumentException("市场参考核对信息不完整");}
    }
}
