package local.asset.assistant;
import org.json.*;
import java.util.*;

/** Read-only source facts. Does not require local allocations or infer wallet balances. */
final class TradeHistory {
    record Entry(String id,long time,String title,String detail,String status) { boolean success(){return status.equals("成功");} }
    static List<Entry> read(int channel,JSONObject source) throws Exception {
        List<Entry> entries=new ArrayList<>();JSONArray rows=source.optJSONArray("rows");if(rows==null)return entries;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String id=SnapshotData.id(row,channel);
            long time=row.optLong(channel==0?"executionAt":channel==4?"transactionDateTime":"createTime",0);
            try{
                String title,detail;boolean success;
                if(channel==0){
                    String side=row.getString("side");if(!Set.of("BUY","SELL").contains(side))throw new IllegalArgumentException();
                    title=row.getString("symbol")+(side.equals("BUY")?" 买入":" 卖出");success=true;
                    detail=Transactions.number(row,"qty")+" "+row.getString("symbol")+"\n成交金额 "+Transactions.number(row,"total")+" "+row.getString("quote");
                }else if(channel==1){
                    title="闪兑";success=row.getString("orderStatus").equals("SUCCESS");
                    detail=Transactions.number(row,"fromAmount")+" "+row.getString("fromAsset")+" → "+Transactions.number(row,"toAmount")+" "+row.getString("toAsset");
                }else if(channel==4){
                    title=row.getString("targetAsset")+" 定投";success=row.getString("transactionStatus").equals("SUCCESS");
                    detail=Transactions.number(row,"sourceAssetAmount")+" "+row.getString("sourceAsset")+" → "+Transactions.number(row,"targetAssetAmount")+" "+row.getString("targetAsset");
                    if(row.has("transactionFee"))detail+="\n手续费 "+Transactions.number(row,"transactionFee")+" "+row.optString("transactionFeeUnit");
                }else if(channel==2){
                    String side=row.getString("tradeType");if(!Set.of("BUY","SELL").contains(side))throw new IllegalArgumentException();boolean buy=side.equals("BUY");
                    title=buy?"C2C 买入":"C2C 卖出";success=row.getString("orderStatus").equals("COMPLETED");
                    detail=(buy?"实付 ":"实收 ")+Transactions.number(row,"totalPrice")+" "+row.getString("fiat")+"\n订单数量 "+Transactions.number(row,"amount")+" "+row.getString("asset");
                }else throw new IllegalArgumentException();
                if(time<=0)throw new IllegalArgumentException();entries.add(new Entry(id,time,title,detail,success?"成功":"未完成"));
            }catch(Exception e){entries.add(new Entry(id,time,"来源记录","部分成交字段未返回，稍后同步再试。","信息不完整"));}
        }
        entries.sort(Comparator.comparingLong(Entry::time).reversed());return entries;
    }
}
