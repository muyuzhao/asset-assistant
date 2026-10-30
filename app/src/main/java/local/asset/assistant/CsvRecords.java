package local.asset.assistant;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Explicit interchange format, never guesses exchange statement columns. */
public final class CsvRecords {
    public static final String HEADER="id,time,title,bucket,asset,amount,note";
    public static String template(){return "\uFEFF"+HEADER+"\r\n";}
    public static List<Ledger.Event> parse(String input){
        if(input.startsWith("\uFEFF"))input=input.substring(1);
        List<List<String>> rows=rows(input);
        if(rows.isEmpty()||!rows.get(0).equals(List.of("id","time","title","bucket","asset","amount","note")))throw new IllegalArgumentException("CSV 表头须为 "+HEADER);
        Map<String,List<Ledger.Posting>> postings=new LinkedHashMap<>();Map<String,List<String>> info=new LinkedHashMap<>();
        for(int i=1;i<rows.size();i++){
            List<String> row=rows.get(i);if(row.size()==1&&row.get(0).isBlank())continue;
            if(row.size()!=7)throw new IllegalArgumentException("CSV 第 "+(i+1)+" 行列数不正确");
            String id=row.get(0).trim();if(id.isEmpty()||id.startsWith("api:")||id.startsWith("class:")||id.startsWith("reverse:"))throw new IllegalArgumentException("CSV 编号不能为空或使用系统保留前缀");
            List<String> meta=List.of(row.get(1),row.get(2),row.get(6));
            if(info.containsKey(id)&&!info.get(id).equals(meta))throw new IllegalArgumentException("同一 CSV 事件编号的时间、标题与备注须一致");
            info.put(id,meta);postings.computeIfAbsent(id,k->new ArrayList<>()).add(new Ledger.Posting(row.get(3),row.get(4),row.get(5)));
        }
        List<Ledger.Event> result=new ArrayList<>();
        for(String id:info.keySet()){
            List<String> m=info.get(id);long time;
            try{time=Instant.parse(m.get(0)).toEpochMilli();}catch(Exception e){try{time=LocalDateTime.parse(m.get(0),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();}catch(Exception ex){throw new IllegalArgumentException("CSV 时间须为 ISO 时间或北京时间 yyyy-MM-dd HH:mm");}}
            if(time>System.currentTimeMillis())throw new IllegalArgumentException("不能导入未来事件");
            result.add(new Ledger.Event("csv:"+id,time,m.get(1),m.get(2),postings.get(id)));
        }
        return result;
    }
    private static List<List<String>> rows(String input){
        List<List<String>> result=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<input.length();i++){
            char c=input.charAt(i);
            if(quoted){if(c=='"'){if(i+1<input.length()&&input.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);continue;}
            if(c=='"'){if(cell.length()!=0||closed)throw new IllegalArgumentException("CSV 引号位置不正确");quoted=true;}
            else if(c==','){row.add(cell.toString());cell.setLength(0);closed=false;}
            else if(c=='\n'||c=='\r'){row.add(cell.toString());result.add(row);row=new ArrayList<>();cell.setLength(0);closed=false;if(c=='\r'&&i+1<input.length()&&input.charAt(i+1)=='\n')i++;}
            else {if(closed)throw new IllegalArgumentException("CSV 引号后存在多余字符");cell.append(c);}
        }
        if(quoted)throw new IllegalArgumentException("CSV 引号未闭合");if(cell.length()>0||!row.isEmpty()||closed){row.add(cell.toString());result.add(row);}return result;
    }
}
