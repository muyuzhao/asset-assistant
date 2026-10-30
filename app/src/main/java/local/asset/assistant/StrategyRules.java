package local.asset.assistant;
import org.json.*;
import java.math.*;
import java.util.*;
import java.time.*;

/** Strategy defaults. Calculations are previews; no cycle or signal is enabled by filling a draft. */
final class StrategyRules {
    static final String BUY="0.45,0.40,0.35,0.30", SELL="1.40,1.70,2.00,2.40";
    static final int[] SHARES={20,25,25,30};
    record Policy(String key,String tag,String advice,String[] ids,String[] labels) {}
    static final Policy[] POLICIES={
        new Policy("newFundsPolicy","新增资金","建议固定本轮预算，新入金留到下轮，避免改变已锁定的档位金额。",new String[]{"next_cycle","manual_review"},new String[]{"固定本轮预算；新资金留下轮（推荐）","新增资金如何参与，逐次人工确认"}),
        new Policy("freezePolicy","卖出基数","建议首次卖出前冻结 Q_total；进入卖出阶段后，本周期停止新增机会仓买入。",new String[]{"first_sell","manual_freeze"},new String[]{"首次卖出前冻结，随后停止买入（推荐）","人工确认结束买入后冻结"}),
        new Policy("startupPolicy","首次低估","建议等待一次完整的上方→下方穿越，避免用当前低值猜测周期起点。",new String[]{"wait_cross","manual_start"},new String[]{"等待下一次完整下穿（推荐）","核对历史后手动建立周期"}),
        new Policy("zeroCashPolicy","零资金","不读取银行卡余额。触发时没钱可以跳过一档，或选择本轮不参与，等待下一次完整下穿。",new String[]{"wait_new_cross","manual_review","skip_cycle"},new String[]{"不开空周期，资金到位后等新下穿","资金到位后人工确认启动","提醒后由用户跳过或本轮不参与"}),
        new Policy("pausePolicy","定投暂停","长期计划独立执行；可选只在指标越线时提醒暂停/恢复，均由你在币安操作。",new String[]{"continue","remind_120"},new String[]{"长期定投继续，不随指标暂停（推荐）","≥1.20 提示暂停，<1.20 提示恢复"})
    };
    static JSONObject draft(JSONObject settings) throws Exception {
        JSONObject saved=settings.optJSONObject("strategy");JSONObject s=saved==null?new JSONObject():new JSONObject(saved.toString());
        if(s.optString("buy").isBlank())s.put("buy",BUY);
        if(s.optString("sell").isBlank())s.put("sell",SELL);
        return s.put("budgetAsset","USDT").put("enabled",false);
    }
    static String nextPayMonth(LocalDate today){return (today.getDayOfMonth()<=10?YearMonth.from(today):YearMonth.from(today).plusMonths(1)).toString();}
    static void validateStructured(JSONObject s) throws Exception {
        List<BigDecimal> buy=thresholds(s.getString("buy"),false),sell=thresholds(s.getString("sell"),true);if(buy.get(0).compareTo(sell.get(0))>=0)throw new IllegalArgumentException("买入最高阈值须小于卖出最低阈值");
        for(Policy policy:POLICIES){String value=s.optString(policy.key());if(!value.isBlank()&&!Arrays.asList(policy.ids()).contains(value))throw new IllegalArgumentException(policy.tag()+"选项无法识别，请重新选择");}
        if(!s.optString("firstMonth").isBlank())try{YearMonth.parse(s.getString("firstMonth"));}catch(Exception e){throw new IllegalArgumentException("首次生效月份请选择有效的年月");}
    }
    static String status(JSONObject s) {
        int missing=0;for(Policy policy:POLICIES)if(!Arrays.asList(policy.ids()).contains(s.optString(policy.key())))missing++;
        if(s.optString("firstMonth").isBlank())missing++;
        return missing==0?"规则选项已填写 · 周期尚未启用":"还有 "+missing+" 项待选择 · 可一键填入建议后逐项检查";
    }
    static List<BigDecimal> thresholds(String input,boolean increasing) {
        String[] parts=input.trim().split("[,，/]",-1);if(parts.length!=4)throw new IllegalArgumentException("每组阈值需要四个数字");
        List<BigDecimal> result=new ArrayList<>();
        for(String part:parts){BigDecimal value=Ledger.decimal(Ledger.positive(part.trim()));
            if(!result.isEmpty()&&(increasing?value.compareTo(result.get(result.size()-1))<=0:value.compareTo(result.get(result.size()-1))>=0))throw new IllegalArgumentException(increasing?"卖出阈值须递增":"买入阈值须递减");result.add(value);}
        return result;
    }
    static List<BigDecimal> buys(String budget) {
        BigDecimal total=Ledger.decimal(Ledger.positive(budget)),used=BigDecimal.ZERO;List<BigDecimal> amounts=new ArrayList<>();
        for(int i=0;i<4;i++){BigDecimal amount=i==3?total.subtract(used):total.multiply(BigDecimal.valueOf(SHARES[i])).divide(BigDecimal.valueOf(100),18,RoundingMode.DOWN);amounts.add(amount);used=used.add(amount);}return amounts;
    }
    static BigDecimal sell(String original,String remaining,int stage) {
        if(stage<0||stage>3)throw new IllegalArgumentException("卖出档位无效");
        BigDecimal q=Ledger.decimal(Ledger.positive(original)),left=Ledger.decimal(remaining);
        if(left.signum()<0||left.compareTo(q)>0)throw new IllegalArgumentException("机会仓剩余数量须在零与原始总量之间");
        return stage==3?left:q.multiply(BigDecimal.valueOf(SHARES[stage])).divide(BigDecimal.valueOf(100),18,RoundingMode.DOWN).min(left);
    }
    static String table(JSONObject s) {
        StringBuilder text=new StringBuilder();String[] buys=s.optString("buy",BUY).split("[,，/]"),sells=s.optString("sell",SELL).split("[,，/]");
        for(int i=0;i<4;i++)text.append("买入 ").append(i+1).append("：AHR999 ≤ ").append(i<buys.length?buys[i].trim():"待填").append(" · 初始预算 ").append(SHARES[i]).append("%\n");
        text.append("\n");
        for(int i=0;i<4;i++)text.append("卖出 ").append(i+1).append("：AHR999 ≥ ").append(i<sells.length?sells[i].trim():"待填").append(i==3?" · 全部剩余机会仓":" · 原始机会仓 "+SHARES[i]+"%").append(i==3?"":"\n");
        return text.toString();
    }
}
