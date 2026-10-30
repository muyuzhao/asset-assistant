package local.asset.assistant;

import org.json.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Persistent, read-only daily signals. Never infers bank cash, holdings or executed trades. */
final class OpportunityStrategy {
    static final String KEY="opportunity";
    static JSONObject state(JSONObject settings) throws Exception {
        JSONObject saved=settings.optJSONObject(KEY);
        return saved==null?new JSONObject():new JSONObject(saved.toString());
    }
    static boolean running(JSONObject s){return s.optBoolean("active");}
    static boolean inCycle(JSONObject s){return Set.of("buy","sell").contains(s.optString("phase"));}
    static JSONObject rules(JSONObject settings) throws Exception {
        JSONObject s=StrategyRules.draft(settings);
        s.put("newFundsPolicy","next_cycle").put("freezePolicy","first_sell")
            .put("startupPolicy","wait_cross").put("zeroCashPolicy","skip_cycle");
        if(s.optString("pausePolicy").isBlank())s.put("pausePolicy","continue");
        return s;
    }
    static JSONObject enable(JSONObject old,JSONObject rules,JSONObject market,long now) throws Exception {
        StrategyRules.validateStructured(rules);
        JSONObject s=new JSONObject(old.toString());
        if(inCycle(s)&&!MarketData.ruleKey(s.getJSONObject("rules")).equals(MarketData.ruleKey(rules)))
            throw new IllegalArgumentException("本轮尚未结束，不能更换本轮阈值");
        s.put("active",true).put("rules",new JSONObject(rules.toString())).put("enabledAt",now);
        if(!inCycle(s))s.put("phase","waiting");
        s.remove("lastDate");s.remove("lastValue");s.remove("gap");
        JSONObject current=confirmed(market,rules,now);
        if(current!=null)baseline(s,current);
        return s;
    }
    private static JSONObject confirmed(JSONObject source,JSONObject rules,long now) {
        if(source==null||!source.optString("error").isBlank())return null;
        JSONObject c=source.optJSONObject("current");
        if(c==null||!c.optString("status").equals("ok")||c.optInt("count")<2
            ||!MarketData.FORMULA.equals(c.optString("formula"))
            ||!MarketData.ruleKey(rules).equals(c.optString("ruleKey"))
            ||!MarketData.target(now).toString().equals(c.optString("date")))return null;
        return c;
    }
    static boolean dataReady(JSONObject s,JSONObject market,long now){return s.optJSONObject("rules")!=null&&confirmed(market,s.optJSONObject("rules"),now)!=null;}
    private static void baseline(JSONObject s,JSONObject c) throws Exception {
        s.put("lastDate",c.getString("date")).put("lastValue",MarketData.positive(c.get("value")).toPlainString());
    }
    static JSONObject evaluate(JSONObject old,JSONObject market,long now) throws Exception {
        JSONObject s=new JSONObject(old.toString());
        if(!running(s))return s;
        JSONObject rules=s.getJSONObject("rules"),c=confirmed(market,rules,now);
        if(c==null)return s;
        LocalDate day=LocalDate.parse(c.getString("date"));
        String last=s.optString("lastDate");
        if(!last.isBlank()&&!day.isAfter(LocalDate.parse(last)))return s;
        BigDecimal value=MarketData.positive(c.get("value"));
        boolean adjacent=!last.isBlank()&&day.equals(LocalDate.parse(last).plusDays(1));
        BigDecimal previous=last.isBlank()?null:MarketData.positive(s.get("lastValue"));
        s.put("gap",!last.isBlank()&&!adjacent);
        List<BigDecimal> buys=StrategyRules.thresholds(rules.getString("buy"),false);
        List<BigDecimal> sells=StrategyRules.thresholds(rules.getString("sell"),true);
        if(!inCycle(s)&&adjacent&&previous.compareTo(buys.get(0))>0&&value.compareTo(buys.get(0))<=0){
            s.put("cycle",s.optInt("cycle")+1).put("phase","buy").put("started",day.toString()).put("buyMask",0).put("sellMask",0);
            s.remove("ended");s.remove("endReason");
        }
        // A gap cannot establish a new crossing. An existing cycle may still reach a new stage today.
        if(s.optString("phase").equals("buy")){
            if(value.compareTo(sells.get(0))>=0){
                s.put("phase","sell");expirePendingBuys(s);
            }else{
                int mask=s.optInt("buyMask");
                for(int i=0;i<4;i++)if((mask&(1<<i))==0&&value.compareTo(buys.get(i))<=0){
                    signal(s,"buy",i,day,value);mask|=1<<i;
                }
                s.put("buyMask",mask);
            }
        }
        if(s.optString("phase").equals("sell")){
            int mask=s.optInt("sellMask");
            for(int i=0;i<4;i++)if((mask&(1<<i))==0&&value.compareTo(sells.get(i))>=0){
                signal(s,"sell",i,day,value);mask|=1<<i;
            }
            s.put("sellMask",mask);
        }
        if(rules.optString("pausePolicy").equals("remind_120")&&adjacent){
            BigDecimal line=new BigDecimal("1.20");
            if(previous.compareTo(line)<0&&value.compareTo(line)>=0)signal(s,"pause",0,day,value);
            if(previous.compareTo(line)>=0&&value.compareTo(line)<0)signal(s,"resume",0,day,value);
        }
        baseline(s,c);return s;
    }
    private static void signal(JSONObject s,String kind,int stage,LocalDate day,BigDecimal value) throws Exception {
        JSONArray signals=s.optJSONArray("signals");if(signals==null)signals=new JSONArray();
        String id=s.optInt("cycle")+":"+kind+":"+(kind.equals("buy")||kind.equals("sell")?stage:day);
        for(int i=0;i<signals.length();i++)if(signals.getJSONObject(i).optString("id").equals(id))return;
        signals.put(new JSONObject().put("id",id).put("cycle",s.optInt("cycle")).put("kind",kind).put("stage",stage)
            .put("date",day.toString()).put("value",value.toPlainString()).put("status","pending"));
        // Retain recent history, always preserving every current-cycle and pending signal.
        JSONArray kept=new JSONArray();
        for(int i=0;i<signals.length();i++){JSONObject item=signals.getJSONObject(i);
            if(i>=signals.length()-160||item.optInt("cycle")==s.optInt("cycle")||item.optString("status").equals("pending"))kept.put(item);
        }
        s.put("signals",kept);
    }
    private static void expirePendingBuys(JSONObject s) throws Exception {
        JSONArray a=s.optJSONArray("signals");if(a==null)return;
        for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);
            if(x.optInt("cycle")==s.optInt("cycle")&&x.optString("kind").equals("buy")&&x.optString("status").equals("pending"))x.put("status","expired");
        }
    }
    static JSONObject acknowledge(JSONObject old,String id,boolean done) throws Exception {
        JSONObject s=new JSONObject(old.toString());JSONArray a=s.optJSONArray("signals");
        if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);if(!x.getString("id").equals(id))continue;
            if(!x.optString("status").equals("pending"))throw new IllegalArgumentException("这条提醒已处理，请查看最新状态");
            if(x.optString("kind").equals("sell")&&x.optInt("stage")==3&&done){
                x.put("status","handled");return finish(s,"已确认本轮机会仓清空");
            }
            x.put("status",done?"handled":"skipped");return s;
        }
        throw new IllegalArgumentException("提醒不存在");
    }
    static JSONObject finish(JSONObject old,String reason) throws Exception {
        JSONObject s=new JSONObject(old.toString());if(!inCycle(s))throw new IllegalArgumentException("当前没有进行中的一轮");
        JSONArray a=s.optJSONArray("signals");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);
            if(x.optInt("cycle")==s.optInt("cycle")&&x.optString("status").equals("pending"))x.put("status","closed");
        }
        return s.put("phase","waiting").put("ended",s.optString("lastDate")).put("endReason",reason);
    }
    static String phaseLabel(JSONObject s){
        if(!running(s))return s.length()==0?"提醒未开启":"提醒已暂停";
        return switch(s.optString("phase")){case "buy"->"本轮买入阶段";case "sell"->"本轮卖出阶段";default->"观察中 · 等待下穿";};
    }
    static String nextStage(JSONObject s)throws Exception{
        JSONObject rules=s.optJSONObject("rules");if(rules==null)return "开启后等待下一次下穿";
        if(!inCycle(s))return "从上方下穿 "+StrategyRules.thresholds(rules.getString("buy"),false).get(0);
        boolean sell=s.optString("phase").equals("sell");int mask=s.optInt(sell?"sellMask":"buyMask");
        List<BigDecimal> lines=StrategyRules.thresholds(rules.getString(sell?"sell":"buy"),sell);
        for(int i=0;i<4;i++)if((mask&(1<<i))==0)return (sell?"卖出":"买入")+"第 "+(i+1)+" 档 "+(sell?"≥ ":"≤ ")+lines.get(i);
        return sell?"等待确认本轮已清空":"等待卖出线 ≥ "+StrategyRules.thresholds(rules.getString("sell"),true).get(0);
    }
    static String signalTitle(JSONObject x){
        String kind=x.optString("kind");int stage=x.optInt("stage");
        return switch(kind){case "buy"->"买入第 "+(stage+1)+" 档 · "+StrategyRules.SHARES[stage]+"%";
            case "sell"->"卖出第 "+(stage+1)+" 档 · "+(stage==3?"全部剩余":StrategyRules.SHARES[stage]+"%");
            case "pause"->"检查是否暂停长期定投";default->"检查是否恢复长期定投";};
    }
    static String signalStatus(JSONObject x){return switch(x.optString("status")){case "handled"->"已处理";case "skipped"->"已跳过";case "expired"->"买入提示已过时";case "closed"->"本轮已结束";default->"待处理";};}
    static JSONArray notices(JSONObject s,long now)throws Exception{
        JSONArray result=new JSONArray(),signals=s.optJSONArray("signals");if(!running(s)||signals==null)return result;
        String today=MarketData.target(now).toString();
        for(int i=0;i<signals.length();i++){JSONObject x=signals.getJSONObject(i);
            if(x.optString("status").equals("pending")&&!x.optBoolean("notified")&&x.optString("date").equals(today))result.put(x);
        }
        return result;
    }
    static void refresh(BookStore book,long now) throws Exception {
        synchronized(book){JSONObject settings=book.settings(),old=state(settings),next=evaluate(old,book.source("market"),now);
            if(!old.toString().equals(next.toString())){settings.put(KEY,next);book.saveSettings(settings);}
        }
    }
}
