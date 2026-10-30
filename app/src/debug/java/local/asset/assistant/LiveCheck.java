package local.asset.assistant;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.*;

/** Explicit developer-triggered live READ verification; never returns credentials or raw account payloads. */
public final class LiveCheck extends Instrumentation {
    private Bundle args;
    private String stage="initialization";
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){Bundle result=new Bundle();StringBuilder report=new StringBuilder();try{
        BookStore book=BookStore.shared(getTargetContext());
        java.util.List<Ledger.Event> before=book.events();
        BinanceReader reader=args.getString("mode", "wallet").equals("market")?null:new BinanceReader(getTargetContext(),book,s->{Bundle progress=new Bundle();progress.putString("stream",s+"\n");sendStatus(0,progress);});
        if(args.getString("mode", "wallet").equals("market")) {
            JSONObject source=MarketReader.refresh(book,System.currentTimeMillis());report.append("market current=").append(source.getJSONObject("current")).append("\n");
            JSONArray providers=source.getJSONArray("providers");for(int i=0;i<providers.length();i++){JSONObject p=providers.getJSONObject(i);report.append("provider ").append(p.optString("name")).append(" success=").append(p.optBoolean("success")).append(" date=").append(p.optString("date")).append(" value=").append(p.optString("value")).append(" error=").append(p.optString("error")).append("\n");}
        }
        else if(args.getString("mode", "wallet").equals("recurring")) {
            BinanceApi api=new BinanceApi(new Vault(getTargetContext()).read());stage="server-time";api.time();
            stage="auto-invest-history";
            JSONObject response=api.object("/sapi/v1/lending/auto-invest/history/list","startTime="+(api.now()-29*SnapshotData.DAY)+"&endTime="+api.now()+"&current=1&size=100");
            JSONArray rows=response.optJSONArray("list");
            report.append("autoInvest total=").append(response.optLong("total",-1)).append(" rows=").append(rows==null?-1:rows.length()).append("\n");
            if(rows!=null) {
                if(rows.length()>0) {
                    JSONObject sample=rows.getJSONObject(0);java.util.Map<String,String> schema=new java.util.TreeMap<>();
                    for(java.util.Iterator<String> k=sample.keys();k.hasNext();){String key=k.next();schema.put(key,sample.get(key).getClass().getSimpleName());}
                    report.append("autoInvest schema=").append(schema).append("\n");
                    report.append("autoInvest feeZero=").append(sample.has("transactionFee")&&Ledger.decimal(sample.getString("transactionFee")).signum()==0).append("\n");
                }
                java.util.Map<String,Integer> pairs=new java.util.TreeMap<>();
                for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String pair=row.optString("sourceAsset")+"→"+row.optString("targetAsset")+" / "+row.optString("transactionStatus",row.optString("status"));pairs.put(pair,pairs.getOrDefault(pair,0)+1);}
                report.append("autoInvest pairs=").append(pairs).append("\n");
                String expectedTime=args.getString("expectedTime", "");
                if(!expectedTime.isEmpty()) {
                    boolean matched=false;
                    for(int i=0;i<rows.length();i++) {
                        JSONObject row=rows.getJSONObject(i);
                        if(args.getString("compareDetails", "false").equals("true")) report.append("comparison time=").append(row.optString("transactionDateTime",row.optString("transactionTime")))
                            .append(" spend=").append(row.optString("sourceAssetAmount"))
                            .append(" receive=").append(row.optString("targetAssetAmount",row.optString("executedAmount"))).append("\n");
                        if(Long.parseLong(expectedTime)/1000!=row.optLong("transactionDateTime",row.optLong("transactionTime",-1))/1000)continue;
                        matched=row.optString("sourceAsset").equals("USDT")&&row.optString("targetAsset").equals("BTC")
                            &&Ledger.decimal(row.getString("sourceAssetAmount")).compareTo(Ledger.decimal(args.getString("expectedSpend")))==0
                            &&Ledger.decimal(row.optString("targetAssetAmount",row.optString("executedAmount"))).compareTo(Ledger.decimal(args.getString("expectedReceive")))==0;
                    }
                    report.append("knownExecutionMatched=").append(matched).append("\n");
                }
            }
        }
        else if(args.getString("mode","wallet").equals("wallet")){reader.wallet();JSONObject wallet=book.source("wallet");Holdings.Result h=Holdings.parse(wallet.getJSONArray("wallets"));report.append("wallets=").append(h.walletNames()).append(" assets=").append(h.quantities().keySet()).append(" missing=").append(h.missing()).append("\n");}
        else {int channel=Integer.parseInt(args.getString("channel","0"));reader.sync(channel,args.getString("backfill","false").equals("true"));JSONObject source=book.source(""+channel);report.append("channel=").append(channel).append(" rows=").append(source.getJSONArray("rows").length()).append(" pending=").append(source.optInt("pendingCount")).append(" excluded=").append(source.optInt("excludedCount")).append(" events=").append(book.events().size()).append("\n");}
        report.append("ledgerUnchanged=").append(before.equals(book.events())).append("\n");
        JSONObject converts=book.source("1");
        if(converts!=null&&converts.has("rows")) {
            java.util.Map<String,Integer> pairs=new java.util.TreeMap<>();JSONArray rows=converts.getJSONArray("rows");
            for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String pair=r.optString("fromAsset")+"→"+r.optString("toAsset");pairs.put(pair,pairs.getOrDefault(pair,0)+1);}
            report.append("convertPairs=").append(pairs).append("\n");
        }
        result.putString("stream",report.toString());finish(-1,result);
    }catch(Exception e){result.putString("stream","FAIL stage="+stage+" type="+e.getClass().getSimpleName()+": "+BinanceApi.message(e)+"\n");finish(1,result);}}
}
