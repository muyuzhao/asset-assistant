package local.asset.assistant;
import org.json.*;
import java.util.*;

final class SnapshotData {
    static final long DAY = 86400000L;
    static final String[] IDS = {"executionId", "orderId", "orderNumber", "", "id"};
    static String id(JSONObject row, int channel) throws Exception {
        Object value = row.get(IDS[channel]);
        if (!(value instanceof String) && !(value instanceof Long) && !(value instanceof Integer)) throw new IllegalArgumentException("订单编号精度不可靠");
        String text = value.toString(); if (text.isBlank() || text.length() > 130) throw new IllegalArgumentException("订单编号无效"); return text;
    }
    static void validate(JSONObject source, int channel) throws Exception {
        JSONArray rows = source.getJSONArray("rows"); if (rows.length() > 20000) throw new IllegalArgumentException("历史记录过多");
        for (int i = 0; i < rows.length(); i++) id(rows.getJSONObject(i), channel);
        JSONArray coverage = source.optJSONArray("coverage");
        if (coverage != null) for (int i = 0; i < coverage.length(); i++) { JSONArray range = coverage.getJSONArray(i); if (range.getLong(0) <= 0 || range.getLong(1) < range.getLong(0)) throw new IllegalArgumentException("历史区间无效"); }
    }
    static JSONObject merge(JSONObject old, int channel, JSONArray fetched, long start, long end) throws Exception {
        JSONObject next = old == null ? new JSONObject() : new JSONObject(old.toString());
        Map<String, JSONObject> rows = new LinkedHashMap<>(); JSONArray previous = next.optJSONArray("rows");
        if (previous != null) for (int i = 0; i < previous.length(); i++) rows.put(id(previous.getJSONObject(i), channel), previous.getJSONObject(i));
        for (int i = 0; i < fetched.length(); i++) { JSONObject row = fetched.getJSONObject(i); String id = id(row, channel); row.put(IDS[channel], id); rows.put(id, row); }
        if (rows.size() > 20000) throw new IllegalArgumentException("历史记录超过容量，请缩小范围");
        JSONArray result = new JSONArray(); for (JSONObject row : rows.values()) result.put(row);
        List<long[]> ranges = ranges(next); ranges.add(new long[]{start, end}); ranges.sort(Comparator.comparingLong(a -> a[0]));
        List<long[]> union = new ArrayList<>(); for (long[] range : ranges) { if (union.isEmpty() || range[0] > union.get(union.size()-1)[1]+1) union.add(range.clone()); else union.get(union.size()-1)[1] = Math.max(union.get(union.size()-1)[1], range[1]); }
        JSONArray coverage = new JSONArray(); for (long[] range : union) coverage.put(new JSONArray().put(range[0]).put(range[1]));
        return next.put("channel", channel).put("rows", result).put("coverage", coverage).put("start", union.get(0)[0]).put("end", union.get(union.size()-1)[1]).put("savedAt", System.currentTimeMillis()).put("error", "");
    }
    static List<long[]> ranges(JSONObject source) throws Exception {
        List<long[]> result = new ArrayList<>(); if (source == null) return result;
        JSONArray ranges = source.optJSONArray("coverage");
        if (ranges != null) for (int i=0; i<ranges.length(); i++) result.add(new long[]{ranges.getJSONArray(i).getLong(0), ranges.getJSONArray(i).getLong(1)});
        else if (source.has("start") && source.has("end")) result.add(new long[]{source.getLong("start"), source.getLong("end")});
        return result;
    }
    static List<long[]> missing(JSONObject source, long start, long end) throws Exception {
        List<long[]> remaining = new ArrayList<>(); remaining.add(new long[]{start, end});
        for (long[] covered : ranges(source)) {
            List<long[]> next = new ArrayList<>();
            for (long[] r : remaining) {
                if (covered[1] < r[0] || covered[0] > r[1]) next.add(r);
                else { if (r[0] < covered[0]) next.add(new long[]{r[0], covered[0]-1}); if (r[1] > covered[1]) next.add(new long[]{covered[1]+1, r[1]}); }
            }
            remaining = next;
        }
        List<long[]> windows = new ArrayList<>();
        for (long[] range : remaining) for (long s = range[0]; s <= range[1];) { long e = Math.min(range[1], s + 29 * DAY - 1); windows.add(new long[]{s,e}); s = e + 1; }
        return windows;
    }
}
