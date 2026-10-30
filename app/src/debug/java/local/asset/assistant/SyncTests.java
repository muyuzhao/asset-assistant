package local.asset.assistant;
import android.content.*;
import org.json.*;
import java.io.File;
import java.math.BigDecimal;
import java.util.*;

final class SyncTests {
    static void ok(boolean condition,String name){if(!condition)throw new AssertionError(name);}
    static Context isolated(Context original,String suffix){return new ContextWrapper(original){@Override public File getFilesDir(){File f=new File(super.getFilesDir(),suffix);f.mkdirs();return f;}};}
    static JSONObject c2c(String id,String status) throws Exception {return new JSONObject().put("orderNumber",id).put("orderStatus",status).put("tradeType","BUY").put("asset","USDT").put("fiat","CNY").put("totalPrice","1500.00").put("amount","224.88000000").put("advertisementRole","TAKER").put("takerCommission","0").put("createTime",1000);}
    static JSONObject convert(String id) throws Exception {return new JSONObject().put("orderId",id).put("orderStatus","SUCCESS").put("fromAsset","USDT").put("toAsset","USDC").put("fromAmount","100").put("toAmount","99.89795709").put("createTime",2000);}
    static JSONObject equity(String id,String qty,String total) throws Exception{return new JSONObject().put("executionId",id).put("orderId","order-one").put("symbol","VOO").put("quote","USDC").put("qty",qty).put("total",total).put("side","BUY").put("executionAt",3000);}
    static JSONObject recurring(String id) throws Exception {return new JSONObject().put("id",id).put("transactionStatus","SUCCESS").put("sourceAsset","USDT").put("targetAsset","BTC").put("sourceAssetAmount","30").put("targetAssetAmount","0.0004").put("transactionFee","0").put("transactionFeeUnit","BTC").put("transactionDateTime",4000);}
    static void run(Context context) throws Exception {
        MarketTests.run(context);
        JSONObject parsed=(JSONObject)BinanceApi.parse("{\"orderId\":20219644646554779648}");ok(parsed.get("orderId") instanceof String&&parsed.getString("orderId").equals("20219644646554779648"),"大订单号不丢精度");
        BookStore book=new BookStore(isolated(context,"flow"));
        JSONObject p2p=SnapshotData.merge(null,2,new JSONArray().put(c2c("paid","COMPLETED")).put(c2c("cancelled","CANCELLED")),1,4000);
        BinanceReader.apply(book,2,p2p);ok(book.events().size()==1,"取消不入账");ok(Ledger.balance(book.events(),null,"USDT").compareTo(new BigDecimal("224.88"))==0,"实际 C2C 到账");
        BinanceReader.apply(book,2,p2p);ok(book.events().size()==1,"重复来源幂等");
        JSONObject cancelled=SnapshotData.merge(p2p,2,new JSONArray().put(c2c("paid","CANCELLED")),1,4000);BinanceReader.apply(book,2,cancelled);
        ok(book.source("2").getInt("pendingCount")==1&&book.events().size()==1,"已入账来源变成取消须待核对而非静默排除");
        BinanceReader.apply(book,2,p2p);
        JSONObject exchange=SnapshotData.merge(null,1,new JSONArray().put(convert("convert-one")),1,4000);BinanceReader.apply(book,1,exchange);
        ok(Ledger.balance(book.events(),null,"BTC").signum()==0,"USDC兑换不误归BTC");ok(Ledger.balance(book.events(),null,"USDT").compareTo(new BigDecimal("124.88"))==0,"兑换扣款");
        JSONObject stocks=SnapshotData.merge(null,0,new JSONArray().put(equity("fill1","0.1","70")).put(equity("fill2","0.04","28")),1,4000);
        stocks.put("orders",new JSONObject().put("order-one",new JSONObject().put("orderId","order-one").put("status","FILLED").put("fee","0.2").put("createdAt",3000).put("quote","USDC")));
        BinanceReader.apply(book,0,stocks);ok(book.events().size()==4,"多笔成交逐笔入账");ok(book.source("0").getInt("pendingCount")==1,"订单费用只待核对一次");
        stocks.put("confirmations",new JSONObject().put("api:fee:order-one",new JSONObject().put("asset","USDC").put("amount","0.2")));BinanceReader.apply(book,0,stocks);BinanceReader.apply(book,0,stocks);
        ok(book.events().size()==5,"费用只入账一次");ok(Ledger.balance(book.events(),null,"USDC").compareTo(new BigDecimal("1.69795709"))==0,"按真实quote记本金和费用");
        ok(Ledger.balance(book.events(),"机会资金","VOO").signum()==0,"不自动归机会仓");
        int count=book.events().size();long savedAt=book.source("0").getLong("savedAt");BinanceReader.failure(book,"0",new BinanceApi.ApiFailure("测试断网"));ok(book.events().size()==count&&book.source("0").getLong("savedAt")==savedAt,"失败保留账本和成功时间");
        JSONObject changed=SnapshotData.merge(book.source("1"),1,new JSONArray().put(convert("convert-one").put("toAmount","80")),1,4000);BinanceReader.apply(book,1,changed);ok(book.source("1").getInt("pendingCount")==1,"来源变更不静默覆盖入账");ok(Ledger.balance(book.events(),null,"USDC").compareTo(new BigDecimal("1.69795709"))==0,"冲突仍保留原金额");
        JSONObject partial=SnapshotData.merge(null,2,new JSONArray().put(c2c("later","TRADING")),10,20);partial=SnapshotData.merge(partial,2,new JSONArray().put(c2c("later","COMPLETED")),30,40);ok(partial.getJSONArray("rows").length()==1,"状态更新不重复存储");
        List<long[]> holes=SnapshotData.missing(partial,10,40);ok(holes.size()==1&&holes.get(0)[0]==21&&holes.get(0)[1]==29,"覆盖区间不吞掉缺口");
        long end=100*SnapshotData.DAY;for(long[] w:SnapshotData.missing(null,1,end))ok(w[1]-w[0]<30*SnapshotData.DAY,"窗口不超过接口限制");
        JSONObject feeUnknown=c2c("fee","COMPLETED").put("takerCommission","1");ok(Transactions.items(2,SnapshotData.merge(null,2,new JSONArray().put(feeUnknown),1,4000)).get(0).event()==null,"非零费用需核对到账");
        BookStore duplicates=new BookStore(isolated(context,"duplicate"));duplicates.merge(List.of(new Ledger.Event("manual",1000,"入金","",List.of(new Ledger.Posting("未分配","USDT","224.88")))));BinanceReader.apply(duplicates,2,p2p);ok(duplicates.events().size()==1&&duplicates.source("2").getInt("pendingCount")==1,"手动疑似重复隔离");
        BookStore restored=new BookStore(isolated(context,"restore"));restored.importBook(book.export());ok(restored.events().equals(book.events()),"完整备份恢复账本");ok(restored.source("0").getJSONArray("rows").length()==2,"完整备份恢复查询记录");restored.importBook(book.export());ok(restored.events().size()==count,"完整备份重复导入去重");
        JSONArray wallets=new JSONArray().put(new JSONObject().put("walletName","Spot").put("activate",true).put("balance","100").put("assetBalances",new JSONArray().put(new JSONObject().put("asset","USDT").put("free","90").put("locked","10").put("freeze","0").put("withdrawing","0"))));
        Holdings.Result holdings=Holdings.parse(wallets);ok(holdings.quantities().get("USDT").compareTo(new BigDecimal("100"))==0&&holdings.missing().isEmpty(),"钱包数量合计");
        JSONArray incomplete=new JSONArray().put(new JSONObject().put("walletName","Earn").put("activate",true).put("balance","100"));ok(!Holdings.parse(incomplete).missing().isEmpty(),"缺少明细不伪造零余额");
        String csv=CsvRecords.HEADER+"\ntrade-1,2026-01-01 10:00,补录买入,未分配,USDT,-20,\"含逗号,备注\"\ntrade-1,2026-01-01 10:00,补录买入,未分配,BTC,0.00025,\"含逗号,备注\"\n";
        ok(CsvRecords.parse(csv).size()==1&&CsvRecords.parse(csv).get(0).postings().size()==2,"CSV 同事件多分录与引号");
        BookStore csvBook=new BookStore(isolated(context,"csv"));ok(csvBook.importCsv(csv)==1&&csvBook.importCsv(csv)==0,"CSV 导入幂等");
        BookStore dca=new BookStore(isolated(context,"recurring"));JSONObject recurring=SnapshotData.merge(null,4,new JSONArray().put(recurring("recurring-one")).put(recurring("failed").put("transactionStatus","FAILED")),1,5000);
        BinanceReader.apply(dca,4,recurring);BinanceReader.apply(dca,4,recurring);ok(dca.events().size()==1,"定投成功入账、失败排除、重复同步幂等");
        ok(Ledger.balance(dca.events(),"BTC 长期","BTC").compareTo(new BigDecimal("0.0004"))==0&&Ledger.balance(dca.events(),"BTC 长期","USDT").compareTo(new BigDecimal("-30"))==0,"真实30扣款不重写为计划20且归入长期底仓");
        ok(Ledger.balance(dca.events(),"机会资金","BTC").signum()==0,"定投不混入机会仓");
        JSONObject fee=SnapshotData.merge(null,4,new JSONArray().put(recurring("fee-dca").put("transactionFee","0.00001")),1,5000);ok(Transactions.items(4,fee).get(0).action().equals("recurringNet"),"定投非零费用需实际净额");
        fee.put("confirmations",new JSONObject().put("api:4:fee-dca",new JSONObject().put("spent","30").put("received","0.00039")));BinanceReader.apply(dca,4,fee);ok(dca.events().size()==2,"定投核实净扣款和净到账入账");
        JSONObject cross=SnapshotData.merge(null,1,new JSONArray().put(convert("same-convert").put("toAsset","BTC").put("fromAmount","30").put("toAmount","0.0004").put("createTime",7000)),1,8000);BinanceReader.apply(dca,1,cross);ok(dca.events().size()==2&&dca.source("1").getInt("pendingCount")==1,"定投与闪兑差3秒的同额记录留待核对不重复入账");
        BookStore dcaRestore=new BookStore(isolated(context,"recurring-restore"));dcaRestore.importBook(dca.export());ok(dcaRestore.source("4")!=null&&dcaRestore.events().equals(dca.events()),"备份恢复定投通道与原事件");
        JSONObject config=StrategyRules.draft(new JSONObject());ok(config.getString("sell").equals("1.40,1.70,2.00,2.40")&&!config.getBoolean("enabled"),"旧卖出规则恢复但不启用策略");
        List<BigDecimal> budgets=StrategyRules.buys("1000");ok(budgets.equals(List.of(new BigDecimal("200.000000000000000000"),new BigDecimal("250.000000000000000000"),new BigDecimal("250.000000000000000000"),new BigDecimal("300.000000000000000000"))),"买入固定初始预算分档");
        BigDecimal left=new BigDecimal("1");String[] expected={"0.2","0.25","0.25","0.3"};for(int i=0;i<4;i++){BigDecimal sale=StrategyRules.sell("1",left.toPlainString(),i);ok(sale.compareTo(new BigDecimal(expected[i]))==0,"卖出锚定原始总量第"+i+"档");left=left.subtract(sale);}ok(left.signum()==0,"最后一档清空剩余机会仓");
        ok(StrategyRules.sell("1","0.02",3).compareTo(new BigDecimal("0.02"))==0,"末档清理实际尾差");
        JSONObject custom=new JSONObject().put("strategy",new JSONObject().put("sell","1.5,1.8,2.1,2.5"));ok(StrategyRules.draft(custom).getString("sell").equals("1.5,1.8,2.1,2.5"),"补充默认规则不覆盖用户自填值");
        List<TradeHistory.Entry> orders=TradeHistory.read(4,recurring);ok(orders.size()==2&&orders.stream().filter(TradeHistory.Entry::success).count()==1,"直接显示自动成交来源且保留失败状态");
        TradeHistory.Entry dcaDisplay=orders.stream().filter(TradeHistory.Entry::success).findFirst().orElseThrow();ok(dcaDisplay.detail().contains("30 USDT → 0.0004 BTC")&&!dcaDisplay.detail().contains("未分配"),"自动成交显示真实数量且不依赖本地分配");
        JSONObject noFee=c2c("unknown-fee","COMPLETED");noFee.remove("takerCommission");TradeHistory.Entry c2cDisplay=TradeHistory.read(2,SnapshotData.merge(null,2,new JSONArray().put(noFee),1,5000)).get(0);ok(c2cDisplay.success()&&c2cDisplay.detail().contains("订单数量"),"C2C手续费未知仍显示订单原值且不冒称净到账");
        TradeHistory.Entry equityDisplay=TradeHistory.read(0,stocks).get(0);ok(equityDisplay.detail().contains("USDC")&&equityDisplay.title().contains("买入"),"自动股票成交保留实际结算币种");
        JSONObject malformed=recurring("malformed");malformed.remove("targetAssetAmount");ok(TradeHistory.read(4,SnapshotData.merge(null,4,new JSONArray().put(malformed),1,5000)).get(0).status().equals("信息不完整"),"缺失字段不伪造金额或成交状态");
    }
}
