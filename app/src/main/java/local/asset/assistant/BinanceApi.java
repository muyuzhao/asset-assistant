package local.asset.assistant;
import javax.net.ssl.HttpsURLConnection;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit GET allowlist; never logs URLs, headers or credentials. */
final class BinanceApi {
    private static final Set<String> ALLOWED = Set.of("/api/v3/time", "/sapi/v1/equity/trade/history", "/sapi/v1/equity/order/detail", "/sapi/v1/convert/tradeFlow", "/sapi/v1/c2c/orderMatch/listUserOrderHistory", "/sapi/v1/asset/wallet/balance", "/sapi/v1/lending/auto-invest/history/list");
    private final String[] credentials;
    private long offset;
    BinanceApi(String[] credentials) { this.credentials = credentials; }
    void time() throws Exception { long start = System.currentTimeMillis(); JSONObject r = object("/api/v3/time", ""); offset = r.getLong("serverTime") - (start + (System.currentTimeMillis() - start) / 2); }
    long now() { return System.currentTimeMillis() + offset; }
    JSONObject object(String path, String query) throws Exception { Object r = get(path, query); if (!(r instanceof JSONObject)) throw new IOException("返回格式不匹配"); return (JSONObject) r; }
    JSONArray array(String path, String query) throws Exception { Object r = get(path, query); if (!(r instanceof JSONArray)) throw new IOException("返回格式不匹配"); return (JSONArray) r; }
    static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private Object get(String path, String query) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!ALLOWED.contains(path)) throw new IllegalArgumentException("端点不在只读白名单");
        boolean signed = !path.equals("/api/v3/time");
        if (signed) {
            query += (query.isEmpty() ? "" : "&") + "recvWindow=10000&timestamp=" + now();
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(credentials[1].getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder signature = new StringBuilder(); for (byte b : mac.doFinal(query.getBytes(StandardCharsets.UTF_8))) signature.append(String.format(Locale.ROOT, "%02x", b & 255));
            query += "&signature=" + signature;
        }
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://api.binance.com" + path + (query.isEmpty() ? "" : "?" + query)).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod("GET"); connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        if (signed) connection.setRequestProperty("X-MBX-APIKEY", credentials[0]);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                String code = "";
                try (InputStream error = connection.getErrorStream()) {
                    if (error != null) { JSONObject r = new JSONObject(new String(error.readNBytes(16384), StandardCharsets.UTF_8)); code = r.optString("code").replaceAll("[^0-9-]", ""); }
                } catch (Exception ignored) {}
                throw new ApiFailure("HTTP " + status + (code.isEmpty() ? "" : " / " + code) + (status == 429 || status == 418 ? "：限流，请稍后重试" : "：请核对读取权限、网络、IP 限制和账户支持情况"));
            }
            try (InputStream stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(8_000_001); if (bytes.length > 8_000_000) throw new ApiFailure("返回内容超过容量");
                Object result = parse(new String(bytes, StandardCharsets.UTF_8));
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (result instanceof JSONObject r) {
                    if (r.has("code") && !Set.of("000000", "0", "200").contains(r.optString("code"))) throw new ApiFailure("接口错误代码 " + r.optString("code").replaceAll("[^0-9-]", ""));
                    if (r.has("success") && !r.getBoolean("success")) throw new ApiFailure("接口报告查询失败");
                }
                return result;
            }
        } finally { connection.disconnect(); }
    }
    static Object parse(String raw) throws Exception {
        raw = raw.replaceAll("(\"(?:id|planId|orderId|orderNumber|executionId|quoteId)\"\\s*:\\s*)([0-9]+)(?=\\s*[,}])", "$1\"$2\"");
        return new JSONTokener(raw).nextValue();
    }
    static String message(Exception e) { return e instanceof ApiFailure || e instanceof IllegalArgumentException ? e.getMessage() : "网络或返回格式异常，已保留上次成功数据"; }
    static final class ApiFailure extends IOException { ApiFailure(String message) { super(message); } }
}
