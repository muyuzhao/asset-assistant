package local.asset.assistant;
import org.json.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Three credential-free public GET sources. Never forwards Binance credentials or follows redirects. */
final class MarketReader {
    static boolean due(BookStore book,long now) throws Exception {
        JSONObject settings=book.settings();
        if(!settings.optBoolean("marketAuto",true)&&!OpportunityStrategy.running(OpportunityStrategy.state(settings)))return false;
        ZonedDateTime utc=Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC);if(utc.getHour()==0&&utc.getMinute()<10)return false;
        JSONObject old=book.source("market");if(old==null)return true;
        JSONObject current=old.optJSONObject("current");
        if(current!=null&&MarketData.target(now).toString().equals(current.optString("date"))&&current.optString("status").equals("ok")&&MarketData.ruleKey(StrategyRules.draft(book.settings())).equals(current.optString("ruleKey")))return false;
        return now-old.optLong("attemptAt",0)>=60*60*1000L;
    }
    static JSONObject refresh(BookStore book,long now) throws Exception {
        LocalDate target=MarketData.target(now);long start=target.minusDays(200).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),end=target.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        List<Callable<JSONObject>> calls=new ArrayList<>();for(String name:MarketData.NAMES)calls.add(()->fetch(name,target,start,end,now));
        ExecutorService pool=Executors.newFixedThreadPool(3);JSONArray providers=new JSONArray();
        try{
            List<Future<JSONObject>> futures=pool.invokeAll(calls,25,TimeUnit.SECONDS);
            for(int i=0;i<futures.size();i++){
                try{providers.put(futures.get(i).get());}
                catch(CancellationException|ExecutionException e){providers.put(new JSONObject().put("name",MarketData.NAMES.get(i)).put("success",false).put("error","请求超时或来源不可用"));}
            }
        }finally{pool.shutdownNow();}
        JSONObject source=merge(book.source("market"),providers,target,StrategyRules.draft(book.settings()),now);
        book.updateSource("market",source,List.of());return source;
    }
    static JSONObject merge(JSONObject old,JSONArray providers,LocalDate target,JSONObject strategy,long now) throws Exception {
        JSONObject current=MarketData.consensus(providers,target,strategy);
        JSONObject source=old==null?new JSONObject():new JSONObject(old.toString());
        source.put("attemptAt",now).put("providers",providers).put("current",current).put("error",current.getString("status").equals("ok")?"":"本次未通过多源核对，请查看来源状态");
        if(current.getString("status").equals("ok")){
            source.put("savedAt",now).put("lastConfirmed",new JSONObject(current.toString()));
            Map<String,JSONObject> days=new TreeMap<>();JSONArray historyBefore=source.optJSONArray("history");if(historyBefore!=null)for(int i=0;i<historyBefore.length();i++)days.put(historyBefore.getJSONObject(i).getString("date"),historyBefore.getJSONObject(i));
            days.put(current.getString("date"),new JSONObject(current.toString()));while(days.size()>400)days.remove(days.keySet().iterator().next());JSONArray history=new JSONArray();for(JSONObject p:days.values())history.put(p);source.put("history",history);
        }
        return source;
    }
    private static JSONObject fetch(String name,LocalDate target,long start,long end,long now) throws Exception {
        try{
            Map<LocalDate,BigDecimal> prices=new HashMap<>();Object data;
            if(name.equals("Coinbase")){
                data=get("https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=86400&start="+Instant.ofEpochSecond(start)+"&end="+Instant.ofEpochSecond(end));
                JSONArray rows=(JSONArray)data;for(int i=0;i<rows.length();i++){JSONArray row=rows.getJSONArray(i);MarketData.candle(prices,row.getLong(0),row.get(4),target);}
            }else if(name.equals("Kraken")){
                JSONObject root=(JSONObject)get("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=1440&since="+start);
                if(root.getJSONArray("error").length()!=0)throw new IOException();JSONObject result=root.getJSONObject("result");JSONArray rows=result.getJSONArray("XXBTZUSD");
                for(int i=0;i<rows.length();i++){JSONArray row=rows.getJSONArray(i);MarketData.candle(prices,row.getLong(0),row.get(4),target);}
            }else{
                JSONObject root=(JSONObject)get("https://www.bitstamp.net/api/v2/ohlc/btcusd/?step=86400&limit=201&end="+(end-1)+"&exclude_current_candle=true");
                JSONArray rows=root.getJSONObject("data").getJSONArray("ohlc");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);MarketData.candle(prices,row.getLong("timestamp"),row.get("close"),target);}
            }
            return MarketData.calculate(name,prices,target).put("fetchedAt",now);
        }catch(InterruptedException e){throw e;}
        catch(Exception e){return new JSONObject().put("name",name).put("success",false).put("error",e instanceof IllegalArgumentException?e.getMessage():"网络、HTTP 或返回格式异常");}
    }
    private static Object get(String value) throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();URI uri=URI.create(value);
        if(!Set.of("api.exchange.coinbase.com","api.kraken.com","www.bitstamp.net").contains(uri.getHost())||!uri.getScheme().equals("https"))throw new IllegalArgumentException("行情来源不支持");
        HttpsURLConnection connection=(HttpsURLConnection)uri.toURL().openConnection();connection.setRequestMethod("GET");connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(12000);connection.setRequestProperty("User-Agent","AssetAssistant/0.4 (public daily reference)");
        try{
            if(connection.getResponseCode()!=200)throw new IOException();
            try(InputStream input=connection.getInputStream()){byte[] bytes=input.readNBytes(1000001);if(bytes.length>1000000)throw new IOException();if(Thread.currentThread().isInterrupted())throw new InterruptedException();return new JSONTokener(new String(bytes,StandardCharsets.UTF_8)).nextValue();}
        }finally{connection.disconnect();}
    }
}
