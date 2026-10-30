package local.asset.assistant;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.math.RoundingMode;

public final class MainActivity extends Activity {
    private static final int INK=0xff203b33, GREEN=0xff245a49, MUTED=0xff75837d, BG=0xfff4f6f3, TINT=0xffedf3ee, LINE=0xffe8eee8;
    private BookStore book;
    private Vault vault;
    private LinearLayout content;
    private int page;
    private ScrollView pageScroll;
    private int renderedPage=-1;
    private final int[] scrollPositions=new int[4];
    private int tradeLimit = 40;
    private int tradeChannel = 4;
    private boolean showSyncDetails;

    private java.util.function.Consumer<String> syncObserver;
    private final String[] pages = {"总览", "成交", "策略", "设置"};
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        page=getIntent().getIntExtra("page",0);if(page<0||page>3)page=0;
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        vault = new Vault(this);
        try { book = BookStore.shared(this);
            for(int i:BinanceReader.HISTORY_CHANNELS) { try { BinanceReader.reprocess(book,i); } catch(Exception e) { toast("部分来源记录需要复核，请查看连接页"); } }
            BackgroundJobs.configure(this,book); render(); }
        catch (Exception e) { new AlertDialog.Builder(this).setTitle("本地数据无法读取").setMessage("原数据已保留，请先备份后排查。").setPositiveButton("关闭", (d,w) -> finish()).setCancelable(false).show(); }
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView label(String value,int size,int color) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setFontFeatureSettings("tnum");return t;
    }
    private TextView text(LinearLayout parent,String value,int size,int color){TextView t=label(value,size,color);t.setPadding(0,dp(3),0,dp(3));parent.addView(t);return t;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void clickable(View view,Runnable action){view.setClickable(true);view.setFocusable(true);view.setOnClickListener(v->action.run());view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14245a49),view.getBackground(),background(Color.WHITE,10)));}
    private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private void space(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private void divider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(LINE);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private void info(String title,String detail){new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("知道了",null).show();}
    private ImageView iconAction(LinearLayout parent,String icon,String description,int color,Runnable action){ImageView v=new ImageView(this);v.setImageDrawable(new UiIcon(icon,color));v.setPadding(dp(12),dp(12),dp(12),dp(12));v.setContentDescription(description);v.setTooltipText(description);parent.addView(v,new LinearLayout.LayoutParams(dp(44),dp(44)));clickable(v,action);return v;}
    private void heading(LinearLayout box,String title,String help){LinearLayout h=row();TextView name=label(title,14,INK);name.setTypeface(null,Typeface.BOLD);h.addView(name);if(help!=null)iconAction(h,"help",title+"说明",MUTED,()->info(title,help));else h.setMinimumHeight(dp(32));box.addView(h);}
    private LinearLayout card(String title){LinearLayout box=column();box.setPadding(dp(16),dp(12),dp(16),dp(12));box.setBackground(background(Color.WHITE,16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);content.addView(box,p);if(!title.isBlank())heading(box,title,null);return box;}
    private TextView badge(String value,int color,int fill){TextView t=label(value,10,color);t.setPadding(dp(8),dp(4),dp(8),dp(4));t.setBackground(background(fill,20));return t;}
    private TextView action(LinearLayout parent,String value,boolean primary,Runnable run){TextView t=label(value,13,primary?Color.WHITE:GREEN);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(44));t.setPadding(dp(14),dp(10),dp(14),dp(10));t.setBackground(background(primary?GREEN:TINT,10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);parent.addView(t,p);clickable(t,run);return t;}
    private void button(LinearLayout parent,String value,Runnable run){action(parent,value,false,run);}
    private void link(LinearLayout parent,String title,String value,Runnable run){LinearLayout r=row();r.setMinimumHeight(dp(48));TextView name=label(title,13,INK);r.addView(name,new LinearLayout.LayoutParams(0,-2,1));if(value!=null){TextView status=label(value,11,MUTED);r.addView(status);}ImageView arrow=new ImageView(this);arrow.setImageDrawable(new UiIcon("chevron",MUTED));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(16),dp(16));p.leftMargin=dp(6);r.addView(arrow,p);r.setPadding(0,dp(4),0,dp(4));parent.addView(r);clickable(r,run);r.setContentDescription(title+(value==null?"":"，"+value));}
    private TextView coin(String asset){String mark=asset.equals("BTC")?"₿":asset.equals("VOO")?"V":asset.equals("USDC")?"$":"T";int color=asset.equals("BTC")?0xffad712a:asset.equals("VOO")||asset.equals("USDC")?0xff5272a3:GREEN;TextView t=label(mark,15,color);t.setTypeface(null,Typeface.BOLD);t.setGravity(Gravity.CENTER);t.setBackground(background(asset.equals("BTC")?0xfffbf1e1:asset.equals("VOO")||asset.equals("USDC")?0xffedf2fa:TINT,12));return t;}
    private void assetRow(LinearLayout parent,String asset,String subtitle,String amount,String unit){
        LinearLayout r=row();r.setPadding(0,dp(7),0,dp(7));r.addView(coin(asset),new LinearLayout.LayoutParams(dp(32),dp(32)));LinearLayout names=column();LinearLayout.LayoutParams n=new LinearLayout.LayoutParams(0,-2,1);n.leftMargin=dp(10);r.addView(names,n);TextView symbol=label(asset,13,INK);symbol.setTypeface(null,Typeface.BOLD);names.addView(symbol);if(subtitle!=null){TextView sub=label(subtitle,10,MUTED);sub.setPadding(0,dp(3),0,0);names.addView(sub);}LinearLayout value=column();value.setGravity(Gravity.END);TextView number=label(amount,14,INK);number.setTypeface(null,Typeface.BOLD);value.addView(number);if(unit!=null){TextView currency=label(unit,10,MUTED);currency.setPadding(0,dp(3),0,0);value.addView(currency);}r.addView(value);parent.addView(r);
    }
    @Override protected void onResume() {
        super.onResume(); if(book==null)return;
        syncObserver = value -> runOnUiThread(() -> { if(!isFinishing()&&!isDestroyed()) render(); }); SyncCoordinator.observer=syncObserver;
        try { BackgroundJobs.remind(this,book);BackgroundJobs.opportunity(this,book);render();
            Runnable market=()->{try{if(MarketReader.due(book,System.currentTimeMillis()))SyncCoordinator.start(this,book,5,false,null);}catch(Exception ignored){}};
            if(vault.exists() && book.settings().optBoolean("refreshOnOpen",true)) {if(!SyncCoordinator.start(this,book,3,false,market))market.run();}else market.run();
        } catch(Exception ignored){}
    }
    @Override protected void onPause() { super.onPause(); if(SyncCoordinator.observer==syncObserver)SyncCoordinator.observer=null; }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);page=intent.getIntExtra("page",0);if(page<0||page>3)page=0;if(book!=null)render();}
    private void render() {
        if(pageScroll!=null&&renderedPage>=0)scrollPositions[renderedPage]=pageScroll.getScrollY();
        LinearLayout root=column();root.setBackgroundColor(BG);
        LinearLayout header=row();header.setPadding(dp(20),dp(4),dp(8),dp(4));
        LinearLayout names=row();TextView title=label(pages[page],20,INK);title.setTypeface(null,Typeface.BOLD);names.addView(title);TextView brand=label("积蓄",11,MUTED);LinearLayout.LayoutParams brandP=new LinearLayout.LayoutParams(-2,-2);brandP.leftMargin=dp(10);names.addView(brand,brandP);header.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        if(SyncCoordinator.busy())header.addView(badge("更新中",GREEN,TINT));
        iconAction(header,"refresh","刷新数据",GREEN,()->startSync(-1,false));root.addView(header,new LinearLayout.LayoutParams(-1,dp(56)));
        if(SyncCoordinator.busy()){TextView progress=label(SyncCoordinator.status,10,MUTED);progress.setSingleLine(true);progress.setEllipsize(android.text.TextUtils.TruncateAt.END);progress.setPadding(dp(20),0,dp(20),dp(6));root.addView(progress);}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);content=column();content.setPadding(dp(16),dp(4),dp(16),dp(8));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        View border=new View(this);border.setBackgroundColor(LINE);root.addView(border,new LinearLayout.LayoutParams(-1,dp(1)));LinearLayout tabs=row();tabs.setPadding(dp(10),dp(4),dp(10),dp(4));tabs.setBackgroundColor(Color.WHITE);
        for(int i=0;i<pages.length;i++){final int index=i;LinearLayout tab=column();tab.setGravity(Gravity.CENTER);tab.setMinimumHeight(dp(54));ImageView icon=new ImageView(this);icon.setImageDrawable(new UiIcon(pages[i],page==i?GREEN:MUTED));tab.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));TextView caption=label(pages[i],10,page==i?GREEN:MUTED);caption.setGravity(Gravity.CENTER);caption.setPadding(0,dp(4),0,0);tab.addView(caption);tabs.addView(tab,new LinearLayout.LayoutParams(0,-2,1));clickable(tab,()->{page=index;render();});tab.setContentDescription(pages[i]);tab.setSelected(page==i);}
        root.addView(tabs);setContentView(root);if(page==0)overview();else if(page==1)trades();else if(page==2)plans();else connections();pageScroll=scroll;renderedPage=page;int offset=scrollPositions[page];scroll.post(()->scroll.scrollTo(0,offset));
    }
    private String fmt(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private String date(long time) { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(time)); }
    private void marketCard() {
        LinearLayout box=card("");LinearLayout h=row();TextView title=label("AHR999",14,INK);title.setTypeface(null,Typeface.BOLD);h.addView(title);iconAction(h,"help","AHR999 说明",MUTED,()->info("AHR999","根据三家交易所的已收盘 BTC/USD 日线复算，与其他网站的发布值可能不同。≤0.45 为低估区，0.45–1.20 为积累区，≥1.20 为风险区。\n\n至少两源同日可用、差异≤2%且位于配置阈值同侧才核对通过；盘中数据不参与。此处显示市场参考，不代表买卖成交。"));box.addView(h);
        try{
            JSONObject source=book.source("market"),current=source==null?null:source.optJSONObject("current");
            if(current==null)text(box,"暂无市场数据",17,MUTED);
            else{
                boolean fresh=MarketData.target(System.currentTimeMillis()).toString().equals(current.optString("date"))&&MarketData.ruleKey(StrategyRules.draft(book.settings())).equals(current.optString("ruleKey"));
                boolean confirmed=fresh&&current.optString("status").equals("ok");
                String stateText=confirmed?"已核对":!fresh?"待更新":switch(current.optString("status")){case "single"->"单源";case "disagreement"->"有分歧";default->"无数据";};TextView state=badge(stateText,confirmed?GREEN:0xffa36d35,confirmed?TINT:0xfffbf1e1);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);LinearLayout filler=row();h.addView(filler,new LinearLayout.LayoutParams(0,1,1));h.addView(state,sp);
                if(current.has("value")){
                    BigDecimal value=new BigDecimal(current.getString("value"));LinearLayout numberRow=row();TextView number=label(fmt(value.setScale(4,RoundingMode.HALF_UP)),32,confirmed?INK:MUTED);number.setTypeface(null,Typeface.BOLD);numberRow.addView(number);
                    TextView zone=label(value.compareTo(new BigDecimal("0.45"))<=0?"低估区":value.compareTo(new BigDecimal("1.20"))<0?"积累区":"风险区",12,confirmed?GREEN:MUTED);LinearLayout.LayoutParams zp=new LinearLayout.LayoutParams(-2,-2);zp.leftMargin=dp(12);numberRow.addView(zone,zp);box.addView(numberRow);space(box,8);
                    box.addView(new IndexBand(this,value.doubleValue()),new LinearLayout.LayoutParams(-1,dp(18)));LinearLayout captions=row();for(String caption:new String[]{"低估","积累","风险"}){TextView t=label(caption,9,MUTED);t.setGravity(Gravity.CENTER);captions.addView(t,new LinearLayout.LayoutParams(0,-2,1));}box.addView(captions);
                }
                LinearLayout foot=row();foot.setMinimumHeight(dp(40));TextView stamp=label(current.optString("date")+" · UTC 日线",10,MUTED);foot.addView(stamp,new LinearLayout.LayoutParams(0,-2,1));foot.addView(label(current.optInt("count")+" 源复算  ›",10,GREEN));box.addView(foot);clickable(foot,this::marketDetails);foot.setContentDescription("AHR999 数据来源与历史");
                if(!confirmed)text(box,fresh?"本次数据未通过交叉核对":"显示历史参考，请刷新后查看",11,0xffa36d35);
            }
        }catch(Exception e){text(box,"市场数据暂时不可用",13,MUTED);}
        if(!hasMarket())button(box,"获取市场数据",()->startSync(5,false));

    }
    private boolean hasMarket(){try{return book.source("market")!=null&&book.source("market").has("current");}catch(Exception e){return false;}}
    private void marketDetails() {
        try{LinearLayout f=form();JSONObject source=book.source("market");if(source==null)throw new IllegalArgumentException("请先刷新市场数据");
            heading(f,"交叉核对","至少两源同日可用、相对差异≤2%且在配置阈值同侧才核对通过。自动读取在北京时间 08:10 后尝试，后台由 Android 调度。\n\n复算公式：AHR999 = (P/G200) × (P/F)。G200 为 200 日收盘价几何均值；F=10^(5.84×log10(币龄)−17.01)，币龄从2009-01-03按UTC日期计算。仅采用已收盘日线。公式版本 "+MarketData.FORMULA+"，与其他网站发布值可能不同。");
            JSONArray providers=source.optJSONArray("providers");if(providers!=null)for(int i=0;i<providers.length();i++){JSONObject provider=providers.getJSONObject(i);LinearLayout head=row();head.setMinimumHeight(dp(36));TextView name=label(provider.optString("name"),13,INK);name.setTypeface(null,Typeface.BOLD);head.addView(name,new LinearLayout.LayoutParams(0,-2,1));head.addView(badge(provider.optBoolean("success")?"可用":"不可用",provider.optBoolean("success")?GREEN:MUTED,provider.optBoolean("success")?TINT:BG));f.addView(head);
                if(provider.optBoolean("success")){keyValue(f,"AHR999",new BigDecimal(provider.getString("value")).setScale(6,RoundingMode.HALF_UP).toPlainString());keyValue(f,"日线日期",provider.optString("date")+" UTC");keyValue(f,"BTC/USD 收盘",new BigDecimal(provider.getString("price")).setScale(2,RoundingMode.HALF_UP).toPlainString());}else text(f,provider.optString("error"),11,MUTED);space(f,6);divider(f);}
            link(f,"刷新市场数据",null,()->startSync(5,false));heading(f,"已归档日线",null);JSONArray history=source.optJSONArray("history");if(history==null||history.length()==0)text(f,"暂无已确认日线",12,MUTED);else for(int i=history.length()-1;i>=Math.max(0,history.length()-30);i--){JSONObject item=history.getJSONObject(i);keyValue(f,item.getString("date"),new BigDecimal(item.getString("value")).setScale(4,RoundingMode.HALF_UP).toPlainString());}showSheet("市场数据",f);
        }catch(Exception e){error(e);}
    }
    private void keyValue(LinearLayout parent,String name,String value){LinearLayout r=row();r.setMinimumHeight(dp(30));r.addView(label(name,11,MUTED),new LinearLayout.LayoutParams(0,-2,1));r.addView(label(value,12,INK));parent.addView(r);}
    private void overview() {
        LinearLayout hero=card("");hero.setBackground(background(GREEN,18));LinearLayout top=row();TextView name=label("账户总资产",12,0xffd9e8df);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));iconAction(top,"help","账户总资产说明",0xffd9e8df,()->info("账户总资产","币安钱包返回的账户估值，以 USDT 计价。持仓数量来自已启用的钱包，市场波动会改变估值。\n\n钱包明细与更新时间可以在下方查看。"));hero.addView(top);
        JSONObject wallet=null;Holdings.Result holdings=null;
        try{wallet=book.source("wallet");if(wallet!=null&&wallet.has("wallets"))holdings=Holdings.parse(wallet.getJSONArray("wallets"));}catch(Exception ignored){}
        LinearLayout valueRow=row();TextView total=label(holdings==null?"—":fmt(holdings.valuation().setScale(2,RoundingMode.HALF_UP)),32,Color.WHITE);total.setTypeface(null,Typeface.BOLD);valueRow.addView(total);TextView unit=label("USDT",11,0xffd9e8df);LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-2,-2);up.leftMargin=dp(10);up.gravity=Gravity.BOTTOM;up.bottomMargin=dp(5);valueRow.addView(unit,up);hero.addView(valueRow);space(hero,12);
        text(hero,wallet!=null&&wallet.has("savedAt")?"更新于 "+date(wallet.optLong("savedAt")):"连接账户后自动同步",10,0xffc4d9cd);
        if(wallet!=null&&!wallet.optString("error").isBlank())text(hero,"刷新暂未完成 · 保留上次数据",11,0xffffd6a7);
        LinearLayout assets=card("");LinearLayout assetsHeader=row();TextView title=label("我的持仓",14,INK);title.setTypeface(null,Typeface.BOLD);assetsHeader.addView(title,new LinearLayout.LayoutParams(0,-2,1));TextView detail=label("明细 ›",11,GREEN);detail.setGravity(Gravity.CENTER);detail.setPadding(dp(8),dp(14),0,dp(14));assetsHeader.addView(detail);clickable(detail,this::walletDetails);assets.addView(assetsHeader);
        if(holdings==null){text(assets,vault.exists()?"尚未取得持仓数据":"连接账户后查看实际持仓",13,MUTED);action(assets,vault.exists()?"刷新资产":"连接币安",true,()->{if(vault.exists())startSync(3,false);else credentials();});}
        else{
            int shown=0;for(String asset:List.of("BTC","VOO","USDT","USDC")){BigDecimal amount=holdings.quantities().get(asset);if(amount==null||amount.signum()==0)continue;if(shown++>0)divider(assets);assetRow(assets,asset,switch(asset){case "BTC"->"比特币";case "VOO"->"标普 500";case "USDT"->"Tether";default->"USD Coin";},fmt(amount),null);}
            for(var entry:holdings.quantities().entrySet())if(!List.of("BTC","VOO","USDT","USDC").contains(entry.getKey())&&entry.getValue().signum()!=0){if(shown++>0)divider(assets);assetRow(assets,entry.getKey(),null,fmt(entry.getValue()),null);}
            if(shown==0)text(assets,"暂无非零资产",13,MUTED);if(!holdings.missing().isEmpty())text(assets,"部分钱包未返回完整数量",11,0xffa36d35);
        }
        marketCard();
    }
    private void trades() {
        String[] names={"BTC 定投","VOO","闪兑","C2C"};int[] channels={4,0,1,2};LinearLayout filters=row();filters.setPadding(0,dp(2),0,dp(12));
        for(int i=0;i<names.length;i++){final int channel=channels[i];TextView chip=label(names[i],11,tradeChannel==channel?Color.WHITE:MUTED);chip.setGravity(Gravity.CENTER);chip.setMinHeight(dp(40));chip.setBackground(background(tradeChannel==channel?GREEN:Color.WHITE,10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(6);filters.addView(chip,p);clickable(chip,()->{tradeChannel=channel;tradeLimit=40;if(pageScroll!=null)pageScroll.scrollTo(0,0);render();});chip.setSelected(tradeChannel==channel);}content.addView(filters);
        try{
            JSONObject source=book.source(""+tradeChannel);if(source==null||!source.has("rows")){LinearLayout empty=card("");text(empty,"暂无成交记录",16,INK);button(empty,"同步成交",()->startSync(-1,false));return;}
            List<TradeHistory.Entry> entries=TradeHistory.read(tradeChannel,source);LinearLayout list=card("");LinearLayout heading=row();TextView count=label(entries.size()+" 条记录",11,MUTED);heading.addView(count,new LinearLayout.LayoutParams(0,-2,1));TextView updated=label(new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(source.getLong("savedAt")))+" 更新",10,MUTED);heading.addView(updated);list.addView(heading);space(list,8);
            for(int i=0;i<Math.min(tradeLimit,entries.size());i++){
                TradeHistory.Entry e=entries.get(i);if(i>0)divider(list);LinearLayout item=column();item.setPadding(0,dp(12),0,dp(12));LinearLayout head=row();TextView name=label(e.title(),13,INK);name.setTypeface(null,Typeface.BOLD);head.addView(name,new LinearLayout.LayoutParams(0,-2,1));head.addView(badge(e.status(),e.success()?GREEN:MUTED,e.success()?TINT:BG));item.addView(head);
                String[] lines=e.detail().split("\n");text(item,lines[0],14,INK);text(item,e.time()>0?date(e.time()):"时间未返回",10,MUTED);list.addView(item);
                clickable(item,()->{LinearLayout f=form();heading(f,e.title(),null);text(f,e.time()>0?date(e.time()):"时间未返回",11,MUTED);space(f,8);text(f,e.detail(),14,INK);space(f,8);text(f,"状态 · "+e.status(),12,e.success()?GREEN:MUTED);showSheet("成交详情",f);});item.setContentDescription(e.title()+"，"+lines[0]+"，"+e.status()+"，查看详情");
            }
            if(entries.isEmpty())text(list,"当前范围内没有成交",13,MUTED);if(entries.size()>tradeLimit)button(list,"查看更多",()->{tradeLimit+=40;render();});if(!source.optString("error").isBlank())text(list,"刷新暂未完成，显示上次记录",11,0xffa36d35);
        }catch(Exception e){text(content,"成交数据暂时不可用",13,MUTED);}
    }
    private void plans() {
        LinearLayout fixed=card("");LinearLayout head=row();TextView title=label("固定投入",14,INK);title.setTypeface(null,Typeface.BOLD);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));iconAction(head,"help","固定投入说明",MUTED,()->info("固定投入","这是你的投入计划，实际执行以币安成交记录为准。App 只读同步，不会代你下单。\n\n可在投入提醒中开启每月 10 日和每周二的系统通知。"));fixed.addView(head);
        assetRow(fixed,"VOO","每月 10 日","按计划",null);divider(fixed);assetRow(fixed,"BTC","每周二","按计划",null);divider(fixed);link(fixed,"投入提醒",null,this::preferences);
        LinearLayout opportunity=card("");LinearLayout h=row();TextView name=label("BTC 机会策略",14,INK);name.setTypeface(null,Typeface.BOLD);h.addView(name);iconAction(h,"help","BTC 机会策略说明",MUTED,()->info("BTC 机会策略","机会金放在独立储蓄账户，按自己的预算预留，不读取银行卡余额。\n\n开始一轮时，以你实际准备投入的钱作为本轮固定预算；后来新存的钱留下轮。买入按这个预算分档；卖出按首次卖出前本轮实际机会 BTC 总量分档，最后卖出全部剩余。长期 BTC 不参与卖出。\n\n软件只根据多源核对后的日线提醒，不自动下单、不推算卡余额、不把提醒当成交。每档每轮一次，启用前的信号不追补。"));h.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        try{JSONObject s=OpportunityStrategy.state(book.settings());String state=!OpportunityStrategy.running(s)?(s.length()==0?"未开启":"已暂停"):switch(s.optString("phase")){case "buy"->"买入中";case "sell"->"卖出中";default->"观察中";};h.addView(badge(state,OpportunityStrategy.running(s)?GREEN:MUTED,TINT));}catch(Exception ignored){}opportunity.addView(h);
        LinearLayout amount=row();amount.setPadding(0,dp(4),0,dp(12));amount.addView(label("机会预算",11,MUTED),new LinearLayout.LayoutParams(0,-2,1));TextView cash=label("自行安排",14,INK);cash.setTypeface(null,Typeface.BOLD);amount.addView(cash);opportunity.addView(amount);
        try{JSONObject s=StrategyRules.draft(book.settings());LinearLayout columns=row();columns.setGravity(Gravity.TOP);LinearLayout buy=stageColumn("买入",s.getString("buy"),false),sell=stageColumn("卖出",s.getString("sell"),true);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);bp.rightMargin=dp(8);columns.addView(buy,bp);columns.addView(sell,new LinearLayout.LayoutParams(0,-2,1));opportunity.addView(columns);}catch(Exception e){text(opportunity,"规则需要重新填写",12,MUTED);}
        space(opportunity,6);try{JSONObject s=OpportunityStrategy.state(book.settings());
            if(OpportunityStrategy.running(s)){
                if(!OpportunityStrategy.dataReady(s,book.source("market"),System.currentTimeMillis()))text(opportunity,"等待最新日线核对",11,0xffa36d35);
                NotificationManager nm=getSystemService(NotificationManager.class);NotificationChannel nc=nm.getNotificationChannel("opportunity");
                if(!nm.areNotificationsEnabled()||(nc!=null&&nc.getImportance()==NotificationManager.IMPORTANCE_NONE))link(opportunity,"系统通知未开启","去开启",this::notificationSettings);
            }
            link(opportunity,OpportunityStrategy.running(s)?"本轮提醒与进度":"开启机会提醒",OpportunityStrategy.running(s)?pendingCount(s)+" 条待处理":null,this::opportunityPanel);
        }catch(Exception ignored){}divider(opportunity);link(opportunity,"策略设置",null,this::strategyConfig);
    }
    private LinearLayout stageColumn(String title,String input,boolean sell){LinearLayout box=column();box.setPadding(dp(10),dp(8),dp(10),dp(6));box.setBackground(background(sell?0xfffaf3eb:TINT,10));text(box,title,11,sell?0xffa47443:GREEN).setTypeface(null,Typeface.BOLD);String[] values=input.split("[,，/]");for(int i=0;i<4;i++){LinearLayout r=row();r.setMinimumHeight(dp(36));TextView threshold=label((sell?"≥ ":"≤ ")+(i<values.length?values[i].trim():"—"),16,INK);threshold.setTypeface(null,Typeface.BOLD);r.addView(threshold,new LinearLayout.LayoutParams(0,-2,1));r.addView(label(sell&&i==3?"全部":StrategyRules.SHARES[i]+"%",11,sell?0xffa47443:GREEN));box.addView(r);}return box;}
    private void connections() {
        LinearLayout account=card("");LinearLayout status=row();status.setMinimumHeight(dp(40));TextView title=label("币安账户",14,INK);title.setTypeface(null,Typeface.BOLD);status.addView(title,new LinearLayout.LayoutParams(0,-2,1));status.addView(badge(vault.exists()?"已连接":"未连接",vault.exists()?GREEN:MUTED,vault.exists()?TINT:BG));account.addView(status);divider(account);link(account,"访问密钥",vault.exists()?"管理":"连接",this::credentials);
        LinearLayout sync=card("同步与提醒");link(sync,"同步偏好与投入提醒",null,this::preferences);divider(sync);link(sync,"更新历史成交",null,()->startSync(-1,true));divider(sync);link(sync,"同步详情",showSyncDetails?"收起":"展开",()->{showSyncDetails=!showSyncDetails;render();});
        LinearLayout storage=card("本地数据");link(storage,"备份与恢复",null,this::backup);
        if(!showSyncDetails)return;
        for(int channel=0;channel<5;channel++){final int c=channel;LinearLayout box=card(c==3?"账户资产":BinanceReader.CHANNELS[c]);try{JSONObject source=book.source(c==3?"wallet":""+c);if(source==null||!source.has("savedAt"))text(box,"尚未同步",12,MUTED);else{text(box,"更新于 "+date(source.getLong("savedAt")),11,MUTED);if(c!=3){text(box,source.getJSONArray("rows").length()+" 条订单",14,INK);link(box,"查看成交",null,()->{tradeChannel=c;page=1;render();});}else link(box,"钱包明细",null,this::walletDetails);}if(source!=null&&!source.optString("error").isBlank())text(box,"最近刷新未完成",11,0xffa36d35);}catch(Exception ignored){text(box,"缓存暂不可用",12,MUTED);}link(box,"刷新此项",null,()->startSync(c,false));}
    }
    private void backup(){new AlertDialog.Builder(this).setTitle("本地数据备份").setItems(new String[]{"导出备份","恢复已有备份"},(d,w)->{
        if(w==0)startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"积蓄备份-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".json"),10);
        else startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),11);
    }).show();}
    private void credentials() {
        LinearLayout f=form(); heading(f,"只读 API", "在币安创建系统生成的 HMAC 读取密钥。凭据仅在本机 Keystore 加密保存，不进入备份，不要发送到聊天。");
        EditText key=field(f,"API Key",false), secret=field(f,"Secret Key",false);
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        f.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        dialog("录入只读密钥",f,()->{if(SyncCoordinator.busy())throw new IllegalArgumentException("请等待同步结束");vault.save(key.getText().toString().trim(),secret.getText().toString().trim());key.setText("");secret.setText("");},true);
    }
    private void startSync(int channel,boolean backfill) {
        if(SyncCoordinator.busy()){toast("同步进行中，请稍候");return;}
        if(channel!=5&&!vault.exists()){credentials();return;}
        SyncCoordinator.start(this,book,channel,backfill,null);render();
    }

    private void walletDetails() {
        try{JSONObject source=book.source("wallet");if(source==null||!source.has("wallets"))throw new IllegalArgumentException("请先刷新账户资产");LinearLayout f=form();JSONArray wallets=source.getJSONArray("wallets");text(f,"更新于 "+date(source.getLong("savedAt")),10,MUTED);
            for(int i=0;i<wallets.length();i++){JSONObject wallet=wallets.getJSONObject(i);if(!wallet.getBoolean("activate"))continue;space(f,8);heading(f,wallet.getString("walletName"),null);keyValue(f,"估值",wallet.getString("balance")+" USDT");JSONArray rows=wallet.optJSONArray("assetBalances");if(rows==null)text(f,"数量明细未返回",11,MUTED);else for(int j=0;j<rows.length();j++){JSONObject a=rows.getJSONObject(j);BigDecimal total=BigDecimal.ZERO;boolean known=true;for(String key:List.of("free","locked","freeze","withdrawing")){if(!a.has(key)){known=false;continue;}total=total.add(Ledger.decimal(a.getString(key)));}if(known&&total.signum()==0)continue;String asset=a.getString("asset").equals("EQ_VOO")?"VOO":a.getString("asset");keyValue(f,asset+" · 可用",a.optString("free","未返回"));for(String key:List.of("locked","freeze","withdrawing"))if(!a.has(key)||Ledger.decimal(a.getString(key)).signum()!=0)keyValue(f,switch(key){case "locked"->"锁定";case "freeze"->"冻结";default->"提现中";},a.optString(key,"未返回"));}divider(f);}showSheet("钱包明细",f);
        }catch(Exception e){error(e);}
    }
    private CheckBox toggle(LinearLayout f,String title,boolean checked){CheckBox c=new CheckBox(this);c.setText(title);c.setTextSize(13);c.setTextColor(INK);c.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));c.setMinHeight(dp(40));c.setPadding(0,0,0,0);c.setChecked(checked);f.addView(c);return c;}
    private void preferences() {
        try{
            JSONObject old=book.settings();LinearLayout f=form();heading(f,"自动更新", "后台任务由 Android 调度，不保证准点；手机省电设置可能延迟同步。C2C 接口最多返回最近六个月的记录。");
            CheckBox refresh=toggle(f,"打开时刷新资产",old.optBoolean("refreshOnOpen",true)),background=toggle(f,"后台同步 · 约每 6 小时",old.optBoolean("backgroundSync")),reminders=toggle(f,"固定投入提醒",old.optBoolean("reminders"));space(f,10);
            EditText hour=field(f,"提醒时间 · 北京时间整点",true);hour.setText(""+old.optInt("reminderHour",10));hour.setHint("0–23");
            EditText start=field(f,"历史起始日期",false);start.setText(Instant.ofEpochMilli(old.optLong("historyStart",System.currentTimeMillis()-180*SnapshotData.DAY)).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString());start.setFocusable(false);start.setOnClickListener(v->{LocalDate date=LocalDate.parse(start.getText().toString());new DatePickerDialog(this,(view,y,m,d)->start.setText(LocalDate.of(y,m+1,d).toString()),date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();});
            dialog("同步与提醒",f,()->{LocalDate date=LocalDate.parse(start.getText().toString());long timestamp=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();if(timestamp>System.currentTimeMillis()||date.isBefore(LocalDate.of(2010,1,1)))throw new IllegalArgumentException("起始日期应在 2010 年至今天之间");int h=Integer.parseInt(hour.getText().toString());if(h<0||h>23){hour.setError("填写 0–23 的整点小时");throw new IllegalArgumentException("提醒时间填写 0–23 的整点小时");}JSONObject next=book.settings();next.put("historyStart",timestamp).put("refreshOnOpen",refresh.isChecked()).put("backgroundSync",background.isChecked()).put("reminders",reminders.isChecked()).put("reminderHour",h);book.saveSettings(next);BackgroundJobs.configure(this,book);if(reminders.isChecked()&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},20);},false);
        }catch(Exception e){error(e);}
    }
    private int pendingCount(JSONObject s)throws Exception{int count=0;JSONArray a=s.optJSONArray("signals");if(a!=null)for(int i=0;i<a.length();i++)if(a.getJSONObject(i).optString("status").equals("pending"))count++;return count;}
    private void opportunityPanel(){
        try{
            JSONObject s=OpportunityStrategy.state(book.settings());LinearLayout f=form();
            heading(f,OpportunityStrategy.phaseLabel(s),"开启后等待相邻两个已核对日线从第一买入线上方跌到线上或下方，开始一轮。暂停保留本轮进度，但不追补暂停期间的历史信号。提醒状态与实际成交分别记录，不推算银行卡余额或机会仓数量。");
            if(s.has("lastDate"))keyValue(f,"最近判断日线",s.getString("lastDate")+" UTC");
            if(OpportunityStrategy.inCycle(s))keyValue(f,"当前这一轮","第 "+s.optInt("cycle")+" 轮 · "+s.optString("started"));
            keyValue(f,"下一步",OpportunityStrategy.nextStage(s));
            if(OpportunityStrategy.running(s)&&!OpportunityStrategy.dataReady(s,book.source("market"),System.currentTimeMillis()))text(f,"日线尚未核对，暂不生成新提醒",11,0xffa36d35);
            if(s.optBoolean("gap"))text(f,OpportunityStrategy.inCycle(s)?"期间有缺日，仅判断今天的档位，不补历史":"观察数据有缺日，等待新的完整下穿",11,0xffa36d35);
            NotificationManager manager=getSystemService(NotificationManager.class);android.app.NotificationChannel channel=manager.getNotificationChannel("opportunity");
            boolean allowed=checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED&&manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE);
            if(!allowed)link(f,"系统通知未允许","去开启",this::notificationSettings);
            ScrollView scroll=new ScrollView(this);scroll.addView(f);AlertDialog sheet=new AlertDialog.Builder(this).setTitle("机会提醒").setView(scroll).setPositiveButton("关闭",null).create();
            if(OpportunityStrategy.running(s))button(f,"暂停提醒",()->{if(changeOpportunity(current->current.put("active",false)))sheet.dismiss();});
            else action(f,s.length()==0?"开启提醒":"恢复提醒",true,()->{if(enableOpportunity())sheet.dismiss();});
            JSONArray signals=s.optJSONArray("signals");space(f,10);heading(f,"档位记录","每档每轮只生成一次信号。“已处理”表示你手动确认处理过，不作为币安成交或持仓依据。没钱可以跳过；不参与整轮则选择下方入口。进入卖出阶段后，未处理的买入提示会标成过时，不要求补买。");
            if(signals==null||signals.length()==0)text(f,"还没有触发记录",12,MUTED);
            else{int shown=0;for(int i=signals.length()-1;i>=0;i--){JSONObject x=signals.getJSONObject(i);if(shown>=20&&!x.optString("status").equals("pending"))continue;shown++;
                link(f,OpportunityStrategy.signalTitle(x),OpportunityStrategy.signalStatus(x),()->{sheet.dismiss();signalDetail(x.optString("id"));});
            }}
            if(OpportunityStrategy.inCycle(s)){space(f,6);divider(f);link(f,"本轮不参与",null,()->new AlertDialog.Builder(this).setTitle("跳过这一轮？").setMessage("本轮余下档位不再提醒。继续观察，等下一次完整下穿再开始。不会修改银行卡或币安资产。").setNegativeButton("取消",null).setPositiveButton("本轮不参与",(d,w)->{if(changeOpportunity(current->OpportunityStrategy.finish(current,"用户选择本轮不参与")))sheet.dismiss();}).show());
                link(f,"本轮机会仓已清空",null,()->new AlertDialog.Builder(this).setTitle("确认本轮已结束？").setMessage("仅在本轮机会仓已全部卖完时确认。之后等待新的完整下穿，不把长期 BTC 当作机会仓。").setNegativeButton("取消",null).setPositiveButton("确认已清空",(d,w)->{if(changeOpportunity(current->OpportunityStrategy.finish(current,"已确认本轮机会仓清空")))sheet.dismiss();}).show());}
            sheet.setOnShowListener(v->styleDialog(sheet));sheet.show();
        }catch(Exception e){error(e);}
    }
    private void signalDetail(String id){
        try{
            JSONObject state=OpportunityStrategy.state(book.settings()),signal=null;JSONArray a=state.optJSONArray("signals");if(a!=null)for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))signal=a.getJSONObject(i);
            if(signal==null)throw new IllegalArgumentException("这条提醒不存在");JSONObject x=signal;String kind=x.getString("kind");
            String instruction=kind.equals("buy")?"按本轮开始时确定的机会预算使用 "+StrategyRules.SHARES[x.getInt("stage")]+"%。以储蓄账户实际可用机会金为准，没钱可跳过；新存的钱留给下轮。":kind.equals("sell")?(x.getInt("stage")==3?"卖出本轮机会仓的全部实际剩余 BTC。":"按首次卖出前本轮实际机会 BTC 总量的 "+StrategyRules.SHARES[x.getInt("stage")]+"% 卖出，不按当前剩余量算。")+"长期定投 BTC 不参与。":"仅提醒你检查币安的长期定投计划，不会自动暂停或恢复。";
            String historical=MarketData.target(System.currentTimeMillis()).toString().equals(x.getString("date"))?"":"历史信号，请先核对当前指标。\n\n";
            String message="第 "+x.optInt("cycle")+" 轮 · "+x.getString("date")+" UTC 收盘\nAHR999 "+new BigDecimal(x.getString("value")).setScale(4,RoundingMode.HALF_UP)+"\n\n"+historical+instruction+"\n\n"+OpportunityStrategy.signalStatus(x)+"。信号不代表成交，实际操作在币安完成。";
            AlertDialog.Builder d=new AlertDialog.Builder(this).setTitle(OpportunityStrategy.signalTitle(x)).setMessage(message).setNegativeButton("关闭",null);
            if(x.optString("status").equals("pending")){
                d.setNeutralButton("跳过这档",(dialog,w)->changeOpportunity(current->OpportunityStrategy.acknowledge(current,id,false)));
                String done=kind.equals("sell")&&x.optInt("stage")==3?"本轮已全部卖完":kind.equals("pause")||kind.equals("resume")?"已查看":"我已执行";
                d.setPositiveButton(done,(dialog,w)->changeOpportunity(current->OpportunityStrategy.acknowledge(current,id,true)));
            }
            d.show();
        }catch(Exception e){error(e);}
    }
    interface OpportunityChange{JSONObject apply(JSONObject current)throws Exception;}
    private boolean changeOpportunity(OpportunityChange change){
        try{synchronized(book){if(SyncCoordinator.busy())throw new IllegalArgumentException("请等同步结束后操作");JSONObject settings=book.settings();settings.put(OpportunityStrategy.KEY,change.apply(OpportunityStrategy.state(settings)));book.saveSettings(settings);}BackgroundJobs.configure(this,book);BackgroundJobs.opportunity(this,book);render();return true;}catch(Exception e){error(e);return false;}
    }
    private boolean enableOpportunity(){
        try{synchronized(book){if(SyncCoordinator.busy())throw new IllegalArgumentException("请等同步结束后开启");JSONObject settings=book.settings(),rules=OpportunityStrategy.rules(settings);rules.put("schemaVersion",3);rules.remove("firstMonth");settings.put("strategy",rules).put(OpportunityStrategy.KEY,OpportunityStrategy.enable(OpportunityStrategy.state(settings),rules,book.source("market"),System.currentTimeMillis()));book.saveSettings(settings);}BackgroundJobs.configure(this,book);BackgroundJobs.opportunity(this,book);render();if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},21);return true;}catch(Exception e){error(e);return false;}
    }
    private void notificationSettings(){if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},21);else startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==21){try{BackgroundJobs.opportunity(this,book);render();}catch(Exception e){error(e);}}}
    private void strategyConfig() {
        try{
            JSONObject old=OpportunityStrategy.rules(book.settings());LinearLayout f=form();heading(f,"日线与提醒","只用多源核对通过的最新已收盘 UTC 日线。每档每轮只提醒一次，不自动下单。开启提醒后会保持后台行情更新；Android 省电限制可能延迟，打开 App 也会核对。");
            CheckBox automatic=toggle(f,"自动更新 AHR999",book.settings().optBoolean("marketAuto",true));
            EditText[] buy=thresholdFields(f,"买入阈值",old.optString("buy"),StrategyRules.BUY),sell=thresholdFields(f,"卖出阈值",old.optString("sell"),StrategyRules.SELL);
            ruleRow(f,"新存的钱","留给下一轮","四档都按本轮开始时确定的预算计算；后来新增的资金留给下一轮，不追补已经触发的档位。软件不读取或记录银行卡余额。");
            ruleRow(f,"卖出比例","按本轮原始数量","原“卖出基数”。第一次卖出前，确认本轮实际买到多少机会 BTC，之后 20%、25%、25%都按这份原始数量算，不按每次剩余量算；最后一档卖出实际剩余。长期定投 BTC 排除在外。软件不猜测这份数量，也不自动下单。");
            ruleRow(f,"什么时候开始","等下一次下穿","原“首次低估”。启用时即使 AHR999 已低于第一买入线，也不会立即开一轮。先观察到线上方，再由相邻两个已核对日线从上方跌到线上或下方，才开始。没有已核对的相邻日期就继续观察。");
            ruleRow(f,"卡里暂时没钱","跳过或本轮不参与","原“零资金”。软件不读卡余额，出现提醒后由你判断是否有机会金。可以跳过一档，也可选择“本轮不参与”，等待下一次完整下穿，不追补本轮信号。");
            heading(f,"长期定投","机会仓与长期仓分别处理。长期定投按自己的计划继续；可选在 AHR999 从下向上越过 1.20 时提醒你检查暂停、回到 1.20 以下提醒检查恢复。两者都只提醒，不会修改币安计划。");
            Spinner pause=choice(f,"",new String[]{"长期定投继续","越过 1.20 时提醒检查暂停或恢复"});pause.setSelection(old.optString("pausePolicy").equals("remind_120")?1:0);
            ruleRow(f,"开始观察时间","开启提醒时","原“首次生效月”已取消。现在直接以你开启提醒的时间开始观察，不按月份补算银行卡余额，也不追补此前的买卖信号。");
            JSONObject legacy=old.optJSONObject("legacyRules");if(legacy==null&&old.optInt("schemaVersion")<2)legacy=old;if(legacy!=null){StringBuilder notes=new StringBuilder();for(String key:new String[]{"source","dailyTime","newFunds","sellFreeze","restart","pauseDca","fxSource"})if(!legacy.optString(key).isBlank())notes.append(key).append("：").append(legacy.optString(key)).append("\n");if(notes.length()>0)link(f,"旧版规则备注",null,()->info("旧版规则备注",notes.toString()));}
            dialog("机会策略设置",f,()->{JSONObject s=new JSONObject(old.toString());if(old.optInt("schemaVersion")<2)s.put("legacyRules",new JSONObject(old.toString()));s.put("buy",readThresholdFields(buy,false)).put("sell",readThresholdFields(sell,true)).put("budgetAsset","USDT").put("schemaVersion",3).put("enabled",false).put("pausePolicy",pause.getSelectedItemPosition()==0?"continue":"remind_120");for(String key:new String[]{"newFunds","sellFreeze","restart","pauseDca","fxSource","source","dailyTime","firstMonth"})s.remove(key);StrategyRules.validateStructured(s);JSONObject settings=book.settings(),state=OpportunityStrategy.state(settings);if(OpportunityStrategy.running(state))throw new IllegalArgumentException("请先在本轮提醒中暂停提醒，再修改设置");if(OpportunityStrategy.inCycle(state)&&!MarketData.ruleKey(s).equals(MarketData.ruleKey(state.getJSONObject("rules"))))throw new IllegalArgumentException("本轮还没结束，阈值不能中途修改。请先处理本轮或结束本轮，再改下一轮阈值。");settings.put("strategy",s).put("marketAuto",automatic.isChecked());book.saveSettings(settings);BackgroundJobs.configure(this,book);},false);
        }catch(Exception e){error(e);}
    }
    private void ruleRow(LinearLayout parent,String title,String value,String help){LinearLayout r=row();TextView name=label(title,12,INK);r.addView(name);iconAction(r,"help",title+"说明",MUTED,()->info(title,help));r.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));r.addView(label(value,10,MUTED));parent.addView(r);}
    private EditText[] thresholdFields(LinearLayout f,String title,String saved,String examples){
        heading(f,title,title.startsWith("买入")?"四档阈值从高到低填写，分别使用初始预算的 20%、25%、25%、30%。例：0.45、0.40、0.35、0.30。":"四档阈值从低到高填写，分别卖出原始机会仓的 20%、25%、25%，最后卖出全部剩余。例：1.40、1.70、2.00、2.40。");String[] values=saved.split("[,，/]",-1),hints=examples.split(",");LinearLayout grid=row();EditText[] fields=new EditText[4];
        for(int i=0;i<4;i++){LinearLayout cell=column();LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)cp.leftMargin=dp(6);grid.addView(cell,cp);TextView name=label("第 "+(i+1)+" 档",10,MUTED);name.setGravity(Gravity.CENTER);cell.addView(name);fields[i]=field(cell,"",true);fields[i].setTextSize(13);fields[i].setGravity(Gravity.CENTER);fields[i].setPadding(dp(4),dp(10),dp(4),dp(10));fields[i].setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);fields[i].setHint(hints[i]);fields[i].setContentDescription(title+"第 "+(i+1)+" 档");if(values.length==4)fields[i].setText(values[i].trim());}f.addView(grid);space(f,8);return fields;
    }
    private String readThresholdFields(EditText[] fields,boolean increasing) {
        List<String> values=new ArrayList<>();BigDecimal previous=null;
        for(int i=0;i<fields.length;i++){
            try{String raw=Ledger.positive(fields[i].getText().toString().trim());BigDecimal current=Ledger.decimal(raw);if(previous!=null&&(increasing?current.compareTo(previous)<=0:current.compareTo(previous)>=0))throw new IllegalArgumentException("须"+(increasing?"大于":"小于")+"上一档");previous=current;values.add(raw);fields[i].setError(null);}
            catch(Exception e){fields[i].setError(e.getMessage());fields[i].requestFocus();throw new IllegalArgumentException((increasing?"卖出":"买入")+"第 "+(i+1)+" 档："+e.getMessage());}
        }return String.join(",",values);
    }

    private LinearLayout form(){LinearLayout f=column();f.setPadding(dp(20),dp(6),dp(20),dp(12));return f;}
    private EditText field(LinearLayout f,String title,boolean number){if(!title.isBlank()){TextView name=text(f,title,11,MUTED);name.setPadding(0,dp(10),0,dp(6));}EditText e=new EditText(this);e.setContentDescription(title);e.setSingleLine(true);e.setTextSize(14);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setIncludeFontPadding(false);e.setMinHeight(dp(44));e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(background(BG,8));if(number)e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);f.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    private Spinner choice(LinearLayout f,String title,String[] values){if(!title.isBlank())text(f,title,11,MUTED);Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,values){
        private View item(int position,boolean popup){TextView t=label(values[position]+(popup?"":"  ⌄"),13,INK);t.setPadding(dp(12),dp(12),dp(12),dp(12));t.setMinHeight(dp(46));t.setGravity(Gravity.CENTER_VERTICAL);if(!popup)t.setBackground(background(BG,8));return t;}
        @Override public View getView(int position,View recycled,android.view.ViewGroup parent){return item(position,false);}
        @Override public View getDropDownView(int position,View recycled,android.view.ViewGroup parent){return item(position,true);}
    };spinner.setAdapter(adapter);spinner.setMinimumHeight(dp(46));spinner.setBackgroundColor(Color.TRANSPARENT);f.addView(spinner,new LinearLayout.LayoutParams(-1,-2));return spinner;}
    private void showSheet(String title,LinearLayout form){ScrollView scroll=new ScrollView(this);scroll.addView(form);AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("关闭",null).create();d.setOnShowListener(v->styleDialog(d));d.show();}
    private void styleDialog(AlertDialog d){for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE}){Button b=d.getButton(which);if(b!=null){b.setTextSize(13);b.setTextColor(GREEN);b.setAllCaps(false);b.setMinHeight(dp(44));}}}
    interface Checked { void run() throws Exception; }
    private void dialog(String title, LinearLayout form, Checked action, boolean secure) {
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        if (secure && dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnShowListener(d -> { styleDialog(dialog); if (secure) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { try { synchronized(book) { if(SyncCoordinator.busy())throw new IllegalArgumentException("请等待同步结束后保存"); action.run(); } dialog.dismiss(); render(); toast("已保存到本机"); } catch (Exception e) { error(e); } }); }); dialog.show();
    }
    private void error(Exception e) { new AlertDialog.Builder(this).setTitle("未保存").setMessage(e instanceof IllegalArgumentException || e instanceof IllegalStateException ? e.getMessage() : "操作失败，原有数据已保留。请检查文件格式、存储空间或访问权限。").setPositiveButton("知道了", null).show(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (request == 10) { try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { out.write(book.export().getBytes(StandardCharsets.UTF_8)); } toast("备份已导出，不含 API 凭据"); }
            else if (request == 11) { if(SyncCoordinator.busy())throw new IllegalArgumentException("请等待同步结束后恢复"); String json; try (InputStream in = getContentResolver().openInputStream(data.getData())) { byte[] bytes = in.readNBytes(16_000_001); if (bytes.length > 16_000_000) throw new IllegalArgumentException("文件超过 16 MB"); json = new String(bytes, StandardCharsets.UTF_8); }
                book.importBook(json); for(int i:BinanceReader.HISTORY_CHANNELS)BinanceReader.reprocess(book,i); BackgroundJobs.configure(this,book); render(); toast("备份已恢复，重复记录自动跳过"); }
        } catch (Exception e) { error(e); }
    }
    @Override protected void onDestroy() { super.onDestroy(); if(SyncCoordinator.observer==syncObserver)SyncCoordinator.observer=null; }
}
