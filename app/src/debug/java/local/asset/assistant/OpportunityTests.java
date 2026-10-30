package local.asset.assistant;

import android.content.Context;
import org.json.*;
import java.time.*;

/** Isolated signal lifecycle checks; never places orders or reads the user's book. */
final class OpportunityTests {
    private static final LocalDate START=LocalDate.of(2026,10,1);
    private static JSONObject rules;
    private static void ok(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static long now(int day){return START.plusDays(day+1).atTime(1,0).toInstant(ZoneOffset.UTC).toEpochMilli();}
    private static JSONObject market(int day,String value)throws Exception{
        return new JSONObject().put("current",new JSONObject().put("date",START.plusDays(day).toString()).put("value",value)
            .put("formula",MarketData.FORMULA).put("count",3).put("status","ok").put("ruleKey",MarketData.ruleKey(rules)));
    }
    private static JSONObject tick(JSONObject s,int day,String value)throws Exception{return OpportunityStrategy.evaluate(s,market(day,value),now(day));}
    private static int size(JSONObject s){JSONArray a=s.optJSONArray("signals");return a==null?0:a.length();}
    static void run(Context context)throws Exception{
        rules=OpportunityStrategy.rules(new JSONObject());StrategyRules.validateStructured(rules);
        JSONObject s=OpportunityStrategy.enable(new JSONObject(),rules,market(0,"0.30"),now(0));
        s=tick(s,1,"0.29");ok(size(s)==0&&!OpportunityStrategy.inCycle(s),"启用时低估不追补");
        s=tick(s,2,"0.50");String before=s.toString();
        JSONObject started=tick(s,3,"0.29");ok(before.equals(s.toString()),"计算不修改传入的旧状态");s=started;
        ok(size(s)==4&&s.getInt("buyMask")==15&&s.getInt("cycle")==1,"单日跨越四档全部产生且开始一轮");
        ok(OpportunityStrategy.notices(s,now(3)).length()==4&&OpportunityStrategy.notices(s,now(4)).length()==0,"系统通知只发送本日的新信号，不迟发旧日提醒");
        JSONObject delivered=new JSONObject(s.toString());JSONArray notices=OpportunityStrategy.notices(delivered,now(3));for(int i=0;i<notices.length();i++)notices.getJSONObject(i).put("notified",true);
        ok(OpportunityStrategy.notices(new JSONObject(delivered.toString()),now(3)).length()==0,"通知已送标记持久化后不重复推送");
        ok(tick(s,3,"0.28").toString().equals(s.toString()),"同日重复刷新不重复信号");
        s=tick(s,4,"0.28");ok(size(s)==4,"低位持续不重复提醒");
        String first=s.getJSONArray("signals").getJSONObject(0).getString("id");
        s=OpportunityStrategy.acknowledge(s,first,false);ok(s.getJSONArray("signals").getJSONObject(0).getString("status").equals("skipped"),"跳过只处理提醒，不产生交易");
        s=tick(s,5,"1.40");ok(size(s)==5&&s.getString("phase").equals("sell"),"到达第一卖出线转为卖出阶段");
        ok(s.getJSONArray("signals").getJSONObject(1).getString("status").equals("expired"),"进入卖出后不要求补买旧档位");
        s=tick(s,6,"0.20");ok(size(s)==5&&s.getString("phase").equals("sell"),"首次卖出信号后不再买入");
        s=tick(s,7,"2.50");ok(size(s)==8&&s.getInt("sellMask")==15&&OpportunityStrategy.inCycle(s),"卖出四档信号齐全但不自动当作清仓");
        String last=s.getJSONArray("signals").getJSONObject(7).getString("id");
        s=OpportunityStrategy.acknowledge(s,last,true);ok(!OpportunityStrategy.inCycle(s)&&s.getString("endReason").contains("清空"),"用户确认末档清仓才结束");
        s=tick(s,8,"0.45");ok(s.getInt("cycle")==2&&size(s)==9,"结束后新的完整下穿开启下一轮");
        s=OpportunityStrategy.finish(s,"本轮不参与");s=tick(s,9,"0.30");ok(s.getInt("cycle")==2&&!OpportunityStrategy.inCycle(s),"不参与本轮后不在低位重新开轮");
        JSONObject gap=OpportunityStrategy.enable(new JSONObject(),rules,market(0,"0.50"),now(0));
        gap=tick(gap,2,"0.40");ok(size(gap)==0&&gap.optBoolean("gap"),"缺日不能冒充完整下穿");
        gap=tick(gap,3,"0.50");gap=tick(gap,4,"0.40");ok(size(gap)==2,"缺日后建立新相邻数据仍可下穿");
        JSONObject frozen=OpportunityStrategy.enable(new JSONObject(),rules,market(0,"0.50"),now(0));
        for(String status:new String[]{"single","disagreement","unavailable"}){JSONObject bad=market(1,"0.30");bad.getJSONObject("current").put("status",status);ok(OpportunityStrategy.evaluate(frozen,bad,now(1)).toString().equals(frozen.toString()),"不合格多源不产生信号："+status);}
        JSONObject stale=market(0,"0.30");ok(OpportunityStrategy.evaluate(frozen,stale,now(1)).toString().equals(frozen.toString()),"旧日期不充当新信号");
        JSONObject wrong=market(1,"0.30");wrong.getJSONObject("current").put("ruleKey","other");ok(OpportunityStrategy.evaluate(frozen,wrong,now(1)).toString().equals(frozen.toString()),"规则不一致不产生信号");
        wrong=market(1,"0.30");wrong.put("error","offline");ok(OpportunityStrategy.evaluate(frozen,wrong,now(1)).toString().equals(frozen.toString()),"失败保留的旧确认不生成提醒");
        frozen.put("active",false);ok(size(tick(frozen,1,"0.30"))==0,"暂停不生成提醒");
        frozen=OpportunityStrategy.enable(frozen,rules,market(2,"0.30"),now(2));ok(size(tick(frozen,3,"0.30"))==0,"恢复不追补暂停期间的下穿");
        JSONObject noBaseline=OpportunityStrategy.enable(new JSONObject(),rules,null,now(0));ok(size(tick(noBaseline,1,"0.30"))==0,"首次可靠数据仅建立基线");
        JSONObject changed=new JSONObject(rules.toString()).put("buy","0.44,0.40,0.35,0.30");
        try{OpportunityStrategy.enable(started,changed,market(3,"0.29"),now(3));throw new AssertionError("本轮阈值应锁定");}catch(IllegalArgumentException expected){}
        JSONObject previousRules=rules;rules=new JSONObject(rules.toString()).put("pausePolicy","remind_120");
        JSONObject pause=OpportunityStrategy.enable(new JSONObject(),rules,market(0,"1.10"),now(0));pause=tick(pause,1,"1.20");pause=tick(pause,2,"1.19");ok(size(pause)==2&&pause.getJSONArray("signals").getJSONObject(0).getString("kind").equals("pause"),"可选长期定投越线与恢复只生成提示");rules=previousRules;
        BookStore book=new BookStore(SyncTests.isolated(context,"opportunity"));JSONObject settings=new JSONObject().put("strategy",rules).put(OpportunityStrategy.KEY,s);book.saveSettings(settings);
        ok(OpportunityStrategy.state(new BookStore(SyncTests.isolated(context,"opportunity")).settings()).toString().equals(s.toString()),"重启保留周期及去重状态");
        BookStore restored=new BookStore(SyncTests.isolated(context,"opportunity-restore"));restored.importBook(book.export());ok(OpportunityStrategy.state(restored.settings()).toString().equals(s.toString()),"备份恢复提醒进度");
        ok(book.events().isEmpty()&&restored.events().isEmpty(),"所有策略状态均不产生资金或持仓事件");
    }
}
