package local.asset.assistant;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One atomic document for events, source snapshots and user configuration. No credentials. */
final class BookStore {
    private static BookStore shared;
    static synchronized BookStore shared(Context context) throws Exception { if (shared == null) shared = new BookStore(context.getApplicationContext()); return shared; }
    private final AtomicFile file;
    private List<Ledger.Event> events = new ArrayList<>();
    private JSONObject sources = new JSONObject(), settings = new JSONObject();
    BookStore(Context context) throws Exception {
        file = new AtomicFile(new File(context.getFilesDir(), "book.json"));
        if (file.getBaseFile().exists()) {
            JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            events = decode(root);
            sources = root.optJSONObject("sources") == null ? new JSONObject() : root.getJSONObject("sources");
            settings = root.optJSONObject("settings") == null ? new JSONObject() : root.getJSONObject("settings");
        }
        for (int i = 0; i < 3; i++) {
            File old = new File(context.getFilesDir(), "sync-" + i + ".json");
            if (!sources.has("" + i) && old.exists()) {
                JSONObject source = new JSONObject(new String(new AtomicFile(old).readFully(), StandardCharsets.UTF_8));
                source.put("coverage", new JSONArray().put(new JSONArray().put(source.getLong("start")).put(source.getLong("end"))));
                sources.put("" + i, source);
            }
        }
    }
    synchronized List<Ledger.Event> events() { return List.copyOf(events); }
    synchronized JSONObject source(String key) throws Exception { JSONObject v = sources.optJSONObject(key); return v == null ? null : new JSONObject(v.toString()); }
    synchronized JSONObject settings() throws Exception { return new JSONObject(settings.toString()); }
    synchronized String export() throws Exception { return encode(events, sources, settings).toString(2); }
    synchronized boolean contains(String id) { return events.stream().anyMatch(e -> e.id().equals(id)); }
    synchronized int merge(List<Ledger.Event> added) throws Exception { return commit(added, sources, settings); }
    synchronized int updateSource(String key, JSONObject value, List<Ledger.Event> added) throws Exception {
        JSONObject next = new JSONObject(sources.toString()); next.put(key, value);
        return commit(added, next, settings);
    }
    synchronized void saveSettings(JSONObject value) throws Exception { commit(List.of(), sources, value); }
    synchronized int importCsv(String text) throws Exception {
        List<Ledger.Event> incoming=CsvRecords.parse(text);Ledger.merge(events,incoming);
        for(Ledger.Event added:incoming)if(!contains(added.id()))for(Ledger.Event old:events){
            if(old.id().startsWith("class:")||old.id().startsWith("reverse:")||old.title().equals("用途分配"))continue;
            if(Math.abs(old.time()-added.time())>SnapshotData.DAY)continue;
            for(Ledger.Posting a:added.postings())for(Ledger.Posting b:old.postings())if(a.asset().equals(b.asset())&&Ledger.decimal(a.amount()).signum()>0&&Ledger.decimal(a.amount()).compareTo(Ledger.decimal(b.amount()))==0)throw new IllegalArgumentException("CSV 可能与已有同日同数量记录重复，请先核对并移除重复行");
        }
        write(new AtomicFile(new File(file.getBaseFile().getParentFile(),"before-import.json")),export().getBytes(StandardCharsets.UTF_8));
        return commit(incoming,sources,settings);
    }
    private int commit(List<Ledger.Event> added, JSONObject nextSources, JSONObject nextSettings) throws Exception {
        List<Ledger.Event> next = new ArrayList<>(Ledger.merge(events, added).values());
        if (next.size() > 20000) throw new IllegalArgumentException("记录超过 20000 条限制，请先导出备份");
        byte[] data = encode(next, nextSources, nextSettings).toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > 16_000_000) throw new IllegalArgumentException("账本超过 16 MB 限制，请先导出备份");
        File rollback = new File(file.getBaseFile().getParentFile(), "before-v2.json");
        if (!rollback.exists()) write(new AtomicFile(rollback), file.getBaseFile().exists()?file.readFully():export().getBytes(StandardCharsets.UTF_8));
        write(file, data);
        int count = next.size() - events.size(); events = next;
        sources = new JSONObject(nextSources.toString()); settings = new JSONObject(nextSettings.toString()); return count;
    }
    private static void write(AtomicFile file, byte[] data) throws Exception {
        FileOutputStream out = file.startWrite();
        try { out.write(data); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
    }
    synchronized int importBook(String json) throws Exception {
        JSONObject root = new JSONObject(json); List<Ledger.Event> incoming = decode(root);
        Ledger.merge(events, incoming);
        JSONObject incomingSources = root.optJSONObject("sources"), nextSources = new JSONObject(sources.toString());
        if (incomingSources != null) {
            for (Iterator<String> it = incomingSources.keys(); it.hasNext();) {
                String key = it.next();
                if (!Set.of("0", "1", "2", "4", "wallet", "market").contains(key)) throw new IllegalArgumentException("备份来源不支持");
                JSONObject value = incomingSources.getJSONObject(key);
                if (key.equals("wallet") && value.has("wallets")) Holdings.parse(value.getJSONArray("wallets"));
                else if(key.equals("market"))MarketData.validate(value);
                else if (!key.equals("wallet") && value.has("rows")) SnapshotData.validate(value, Integer.parseInt(key));
                if (!nextSources.has(key)) nextSources.put(key, value);
            }
        }
        JSONObject nextSettings = new JSONObject(settings.toString());
        if (root.optJSONObject("settings") != null && nextSettings.length() == 0) nextSettings = root.getJSONObject("settings");
        write(new AtomicFile(new File(file.getBaseFile().getParentFile(), "before-import.json")), export().getBytes(StandardCharsets.UTF_8));
        return commit(incoming, nextSources, nextSettings);
    }
    private static JSONObject encode(List<Ledger.Event> events, JSONObject sources, JSONObject settings) throws Exception {
        JSONArray rows = new JSONArray();
        for (Ledger.Event e : events) {
            JSONArray ps = new JSONArray();
            for (Ledger.Posting p : e.postings()) ps.put(new JSONObject().put("bucket", p.bucket()).put("asset", p.asset()).put("amount", p.amount()));
            rows.put(new JSONObject().put("id", e.id()).put("time", e.time()).put("title", e.title()).put("note", e.note()).put("postings", ps));
        }
        return new JSONObject().put("format", "asset-assistant").put("version", 2).put("events", rows).put("sources", sources).put("settings", settings);
    }
    private static List<Ledger.Event> decode(JSONObject root) throws Exception {
        if (!root.getString("format").equals("asset-assistant") || (root.getInt("version") != 1 && root.getInt("version") != 2)) throw new IllegalArgumentException("备份格式或版本不支持");
        JSONArray rows = root.getJSONArray("events");
        if (rows.length() > 20000) throw new IllegalArgumentException("记录超过限制");
        List<Ledger.Event> events = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); JSONArray ps = row.getJSONArray("postings"); List<Ledger.Posting> postings = new ArrayList<>();
            for (int j = 0; j < ps.length(); j++) {
                JSONObject p = ps.getJSONObject(j);
                if (!(p.get("amount") instanceof String)) throw new IllegalArgumentException("金额必须是十进制字符串");
                postings.add(new Ledger.Posting(p.getString("bucket"), p.getString("asset"), p.getString("amount")));
            }
            events.add(new Ledger.Event(row.getString("id"), row.getLong("time"), row.getString("title"), row.getString("note"), postings));
        }
        return new ArrayList<>(Ledger.merge(List.of(), events).values());
    }
}
