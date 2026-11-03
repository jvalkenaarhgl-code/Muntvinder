package nl.autovinder.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** AutoVinder is een tweede, losse app naast Muntvinder. Geen Facebook-scraping. */
public final class MainActivity extends Activity {
    private static final int GREEN=0xFF10392F, GOLD=0xFFC89A48, BG=0xFFF5F3EC,
       MUTED=0xFF65736D, INK=0xFF203D35, LIGHT=0xFFFFFFFF, RED=0xFFAA4939;
    private final NumberFormat currency=NumberFormat.getCurrencyInstance(new Locale("nl","NL"));
    private LinearLayout listArea, root;
    private TextView summary;
    private boolean busy=false;
    private int maxVisible=40;
    private String selected="Alles";

    private int px(int dp) {return (int)(dp*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable rounded(int color,int rad){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(px(rad));return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextColor(color);v.setTextSize(size);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setLineSpacing(px(2),1f);return v;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private void pad(View v,int horizontal,int vertical){
        v.setPadding(px(horizontal),px(vertical),px(horizontal),px(vertical));
    }
    private void add(LinearLayout parent,View child){
        parent.addView(child,new LinearLayout.LayoutParams(-1,-2));
    }
    private void gap(LinearLayout p,int dp){p.addView(new View(this),new LinearLayout.LayoutParams(1,px(dp)));}
    private Button button(String name,int fill,int color,Runnable click){
        Button b=new Button(this);b.setText(name);b.setTextSize(12);b.setAllCaps(false);
        b.setTextColor(color);b.setBackground(rounded(fill,10));
        b.setOnClickListener(v->click.run());return b;
    }
    private void line(LinearLayout col,String key,String val,int highlight){
        add(col,text(key,10,MUTED,false));gap(col,3);add(col,text(val,15,highlight,true));gap(col,10);
    }
    private String money(double n){return Double.isFinite(n)?currency.format(n):"Onbekend";}
    private String intStr(Integer n){return n==null?"Onbekend":String.format(Locale.GERMANY,"%,d",n);}
    private SharedPreferences prefs(){return FeedClient.prefs(this);}
    private static int intPref(SharedPreferences p,String k,int d){
        return p.getInt(k,d);
    }
    private static String strPref(SharedPreferences p,String k){
        return p.getString(k,"");
    }

    static boolean matchesFilters(JSONObject d,SharedPreferences p){
        if(d==null)return false;
        double price=d.optDouble("price",Double.NaN);
        int limit=intPref(p,"max_price",1500);
        if(!Double.isFinite(price)||price<100||price>limit)return false;
        int mileage=d.optInt("mileage",-1);
        if((!d.has("mileage")||d.isNull("mileage")||mileage<=0)
                && !p.getBoolean("unknown_km",false))return false;
        if(mileage>intPref(p,"max_km",250000))return false;
        int year=d.optInt("year",0);
        if(year!=0 && year<intPref(p,"min_year",1995))return false;
        if(!p.getBoolean("include_damage",true) && d.optBoolean("damage",false))return false;
        String keywords=strPref(p,"keywords").trim().toLowerCase(Locale.ROOT);
        if(!keywords.isEmpty()){
            String text=(d.optString("title")+" "+d.optString("description")+" "+
                         d.optString("make")+" "+d.optString("model")).toLowerCase(Locale.ROOT);
            for(String token:keywords.split("\\s+")){
                if(!text.contains(token))return false;
            }
        }
        String city=strPref(p,"city").trim().toLowerCase(Locale.ROOT);
        if(!city.isEmpty()&&!d.optString("city","").toLowerCase(Locale.ROOT).contains(city))
            return false;
        return true;
    }
    private double netLow(JSONObject d){
        if(d.optBoolean("manual_resale",false)) {
            double resale=d.optDouble("resale_low",Double.NaN);
            return Double.isFinite(resale)?resale-d.optDouble("price")-
                    intPref(prefs(),"repair",250)-intPref(prefs(),"other_costs",175):Double.NaN;
        }
        double low=d.optDouble("resale_low",Double.NaN);
        if(!Double.isFinite(low))return Double.NaN;
        return low-d.optDouble("price")-intPref(prefs(),"repair",250)-intPref(prefs(),"other_costs",175);
    }
    private double netHigh(JSONObject d){
        double hi=d.optDouble("resale_high",Double.NaN);
        return Double.isFinite(hi)?hi-d.optDouble("price")-
                intPref(prefs(),"repair",250)-intPref(prefs(),"other_costs",175):Double.NaN;
    }
    private double localScore(JSONObject d){
        double low=netLow(d);
        if(Double.isFinite(low)) {
            double score=low>0?200+Math.min(low,3000)/14:60+low/30;
            if(d.optBoolean("high_risk"))score-=40;
            if(d.optBoolean("damage"))score-=8;
            if(d.optBoolean("manual_resale"))score-=18;
            if(d.optBoolean("price_unverified"))score-=180;
            return score;
        }
        return 15+Math.max(0,(1500-d.optDouble("price",1500))/250)
                   -(d.optBoolean("high_risk")?20:0)
                   -(d.optBoolean("price_unverified")?100:0);
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(GREEN);
        getWindow().setNavigationBarColor(GREEN);
        ScanJobService.schedule(this);
        draw();
        askNotifications();
        refresh(false);
    }
    private void askNotifications(){
        if(Build.VERSION.SDK_INT>=33
           &&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
           &&!prefs().getBoolean("permission_asked",false)){
            prefs().edit().putBoolean("permission_asked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},51130);
        }
    }
    private void draw(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);
        root=vertical();scroll.addView(root);setContentView(scroll);
        LinearLayout head=vertical();head.setBackgroundColor(GREEN);pad(head,17,19);
        add(head,text("AUTOVINDER",12,GOLD,true));gap(head,4);
        add(head,text("Goedkoop inkopen, slim verkopen",21,Color.WHITE,true));gap(head,6);
        add(head,text("Auto's ≤ budget · monteurvriendelijk · prijsvergelijking",12,0xFFD0E6DB,false));
        gap(head,11);
        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("↻  Verversen",GOLD,GREEN,()->refresh(true)),
               new LinearLayout.LayoutParams(0,px(45),1));
        View divider=new View(this);actions.addView(divider,new LinearLayout.LayoutParams(px(8),1));
        actions.addView(button("⚙  Filters",0xFF285D50,Color.WHITE,this::settings),
               new LinearLayout.LayoutParams(0,px(45),1));
        add(head,actions);
        add(root,head);

        LinearLayout content=vertical();pad(content,15,14);
        summary=text("Advertenties laden…",15,INK,true);add(content,summary);gap(content,8);
        add(content,text("Bron: Marktplaats automatisch · Facebook Marketplace handmatig",11,MUTED,false));
        gap(content,12);
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(0);
        for(String filter:new String[]{"Alles","Marktplaats","Facebook","Bewaard"}){
            Button b=button(filter,selected.equals(filter)?GREEN:LIGHT,
                 selected.equals(filter)?Color.WHITE:INK,()->{selected=filter;draw();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,px(42),1);
            p.setMargins(px(2),0,px(2),0);tabs.addView(b,p);
        }
        add(content,tabs);gap(content,10);
        add(content,button("Facebook Marketplace doorzoeken",0xFFE4EDE8,GREEN,this::openFacebookSearch));
        gap(content,6);
        add(content,button("+ Facebook-advertentie handmatig toevoegen",LIGHT,GREEN,this::addFacebookAd));
        gap(content,8);
        listArea=vertical();add(content,listArea);add(root,content);
        render();
    }
    private List<JSONObject> manualListings(){
        List<JSONObject> result=new ArrayList<>();
        try{
            JSONArray arr=new JSONArray(prefs().getString("facebook_ads","[]"));
            for(int i=0;i<arr.length();i++){
                JSONObject item=arr.optJSONObject(i);if(item!=null)result.add(item);
            }
        }catch(Exception ignored){}
        return result;
    }
    private Set<String> favorites(){
        return new HashSet<>(prefs().getStringSet("favorites",Collections.emptySet()));
    }
    private void toggleFavorite(String id){
        Set<String> fav=favorites();
        if(fav.contains(id))fav.remove(id);else fav.add(id);
        prefs().edit().putStringSet("favorites",fav).apply();
        render();
    }
    private void render(){
        if(listArea==null)return;
        listArea.removeAllViews();
        JSONObject feed=FeedClient.feed(this);
        JSONArray src=feed.optJSONArray("deals");
        List<JSONObject> all=new ArrayList<>();
        if(src!=null)for(int i=0;i<src.length();i++) {
            JSONObject item=src.optJSONObject(i);if(item!=null)all.add(item);
        }
        all.addAll(manualListings());
        Set<String> fav=favorites();
        List<JSONObject> displayed=new ArrayList<>();
        for(JSONObject d:all){
            if(!matchesFilters(d,prefs()))continue;
            String source=d.optString("source","");
            if(selected.equals("Marktplaats")&&!source.equals("Marktplaats"))continue;
            if(selected.equals("Facebook")&&!source.startsWith("Facebook"))continue;
            if(selected.equals("Bewaard")&&!fav.contains(d.optString("id")))continue;
            displayed.add(d);
        }
        displayed.sort((a,b)->{
            int comp=Double.compare(localScore(b),localScore(a));
            if(comp!=0)return comp;
            return Double.compare(a.optDouble("price",1e7),b.optDouble("price",1e7));
        });
        summary.setText(displayed.size()+" auto's · max "+money(intPref(prefs(),"max_price",1500))+
             " · max "+intStr(intPref(prefs(),"max_km",250000))+" km");
        String generated=feed.optString("generated_at","");
        if(!generated.isEmpty()) {
            String msg="Laatste scan: "+generated.replace('T',' ').replace("+00:00"," UTC");
            if(feed.optBoolean("stale"))msg+=" · Oude resultaten";
            JSONArray errs=feed.optJSONArray("errors");
            if(errs!=null&&errs.length()>0)msg+=" · Sommige zoekopdrachten mislukt";
            add(listArea,text(msg,11,MUTED,false));gap(listArea,8);
        }
        String error=prefs().getString(FeedClient.KEY_ERROR,"");
        if(!error.isEmpty()){add(listArea,text("Feedfout: "+error,12,RED,false));gap(listArea,6);}
        if(displayed.isEmpty()){
            add(listArea,text("Geen advertenties binnen deze filters. Probeer een hogere maxprijs, een andere zoekterm of 'km onbekend toestaan'.",13,MUTED,false));
        }else{
            int count=Math.min(maxVisible,displayed.size());
            for(int i=0;i<count;i++)card(displayed.get(i),fav.contains(displayed.get(i).optString("id")),i);
            if(displayed.size()>count) {
                gap(listArea,8);
                add(listArea,button("Toon meer ("+(displayed.size()-count)+" resterend)",
                     GREEN,Color.WHITE,()->{maxVisible+=35;render();}));
            }
        }
        gap(listArea,12);
        add(listArea,text("Waarderingen: alleen een rekenvoorbeeld op basis van vergelijkbare ACTIEVE vraagprijzen. Geen verkochte auto's of definitieve taxaties. Reparaties, gebreken, APK, kentekencheck, RDW-status, verzekeringskosten en tenaamstelling controleren voor aankoop.",11,MUTED,false));
    }
    private void card(JSONObject d,boolean fav,int index){
        LinearLayout c=vertical();pad(c,14,15);c.setBackground(rounded(LIGHT,15));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        String source=d.optString("source","Marktplaats");
        String label=(index<3?"#"+(index+1)+" · ":"")+source+
                  (d.optBoolean("damage")?" · SCHADE":"");
        TextView chip=text(label,11,d.optBoolean("damage")?RED:GREEN,true);
        top.addView(chip,new LinearLayout.LayoutParams(0,-2,1));
        TextView star=text(fav?"★":"☆",27,GOLD,true);pad(star,10,4);
        star.setOnClickListener(v->toggleFavorite(d.optString("id")));
        top.addView(star);add(c,top);gap(c,7);
        add(c,text(d.optString("title","Auto"),17,INK,true));gap(c,9);
        String photo=d.optString("image_url","");
        if(photo.startsWith("https://")){
            ImageView image=new ImageView(this);image.setBackground(rounded(0xFFE9ECE7,12));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            c.addView(image,new LinearLayout.LayoutParams(-1,px(160)));
            PhotoLoader.load(image,photo);
            image.setOnClickListener(v->open(d.optString("url")));
            gap(c,10);
        }
        String year=d.isNull("year")?"?":d.optString("year","?");
        String km=d.isNull("mileage")?"km onbekend":intStr(d.optInt("mileage"))+" km";
        String city=d.optString("city","");
        add(c,text(d.optString("make")+" "+d.optString("model")+" · "+year+" · "+km+
                (city.isEmpty()?"":" · "+city),12,MUTED,true));gap(c,12);
        double price=d.optDouble("price",Double.NaN);
        line(c,d.optString("price_kind","FIXED").equals("FIXED")?"VRAAGPRIJS":"BIEDPRIJS — NIET GEGARANDEERD",
             money(price),INK);
        double cost=price+intPref(prefs(),"repair",250)+intPref(prefs(),"other_costs",175);
        line(c,"TOTALE INKOOP + REPARATIE + OVERIGE KOSTEN",money(cost),INK);
        double lo=d.optDouble("resale_low",Double.NaN),hi=d.optDouble("resale_high",Double.NaN);
        if(Double.isFinite(lo)){
            line(c,d.optBoolean("manual_resale")?"ZELF INGEVULDE VERKOOPPRIJS":"VOORZICHTIGE VERKOOPINDICATIE",
                 money(lo)+" – "+money(hi),GREEN);
            double low=netLow(d),high=netHigh(d);
            line(c,"MOGELIJKE BRUTOMARGE (EXCL. ONVOORZIENE KOSTEN)",
                 money(low)+" tot "+money(high),low>0?GREEN:RED);
        }else{
            add(c,text("Doorverkoopwaarde onbekend: nog geen drie bruikbare vergelijkingen.",12,MUTED,false));
            gap(c,10);
        }
        String risk=d.optString("damage_note","");
        if(d.optBoolean("price_unverified")){
            add(c,text("⚠ Vraagprijs mogelijk een lokbedrag of placeholder: eerst de echte verkoopprijs controleren.",12,RED,true));
            gap(c,8);
        }
        if(d.optBoolean("high_risk")) {
            add(c,text("⚠ HOOG RISICO: "+risk+"; niet automatisch als winstgevende deal zien.",12,RED,true));
            gap(c,8);
        }else if(d.optBoolean("damage")){
            add(c,text("Schade genoemd: "+risk+". Werkelijke reparatiekosten eerst bekijken.",12,RED,false));
            gap(c,8);
        }
        if(!d.optBoolean("manual_resale")){
            add(c,text("Vergelijkbare vraagprijzen: "+d.optInt("comparables",0)+
               " · "+d.optString("deal_label","Prijsindicatie"),11,MUTED,false));gap(c,8);
        }else{
            add(c,text("Facebook: advertentie en verkoopprijs handmatig toegevoegd, niet automatisch geverifieerd.",11,MUTED,false));gap(c,8);
        }
        add(c,button("Bekijk originele advertentie ↗",GREEN,Color.WHITE,()->open(d.optString("url"))));
        if(source.startsWith("Facebook")){gap(c,6);add(c,button("Verwijder Facebook-advertentie",0xFFF4EDE8,RED,()->deleteFacebook(d.optString("id"))));}
        add(listArea,c);gap(listArea,13);
    }
    private EditText field(LinearLayout body,String label,String prefill,boolean numeric){
        add(body,text(label,12,INK,true));
        EditText entry=new EditText(this);
        entry.setText(prefill);entry.setTextSize(15);
        if(numeric)entry.setInputType(InputType.TYPE_CLASS_NUMBER);
        add(body,entry);gap(body,8);return entry;
    }
    private int num(EditText value,int fallback,int min,int max){
        try{return Math.min(max,Math.max(min,Integer.parseInt(value.getText().toString().trim())));}
        catch(NumberFormatException e){return fallback;}
    }
    private void settings(){
        LinearLayout content=vertical();pad(content,17,15);
        add(content,text("De filters gelden direct voor de gescande autolijst. De cloudscanner verzamelt maximaal €5.000 aan koopaanbiedingen en zoekt extra vergelijkingen tot €12.500. Voor hogere zoekprijzen moet de scanner ook worden aangepast.",12,MUTED,false));
        gap(content,12);
        EditText max=field(content,"Maximale inkoopprijs (€)",
                     String.valueOf(intPref(prefs(),"max_price",1500)),true);
        EditText mileage=field(content,"Maximale kilometerstand",
                     String.valueOf(intPref(prefs(),"max_km",250000)),true);
        EditText year=field(content,"Minimaal bouwjaar",
                     String.valueOf(intPref(prefs(),"min_year",1995)),true);
        EditText keyword=field(content,"Zoekwoorden / merk en model",
                     strPref(prefs(),"keywords"),false);
        EditText city=field(content,"Plaatsfilter (leeg = heel Nederland)",
                     strPref(prefs(),"city"),false);
        EditText repair=field(content,"Reparatiekosten per auto (€) — schatting",
                     String.valueOf(intPref(prefs(),"repair",250)),true);
        EditText other=field(content,"Overige kosten per auto (€) — schatting",
                     String.valueOf(intPref(prefs(),"other_costs",175)),true);
        CheckBox damage=new CheckBox(this);
        damage.setText("Ook auto's met schade of defecten");
        damage.setChecked(prefs().getBoolean("include_damage",true));add(content,damage);
        CheckBox unknown=new CheckBox(this);
        unknown.setText("Ook advertenties zonder bekende km-stand");
        unknown.setChecked(prefs().getBoolean("unknown_km",false));add(content,unknown);
        CheckBox alerts=new CheckBox(this);
        alerts.setText("Meldingen bij nieuwe betrouwbare topdeals");
        alerts.setChecked(DealNotifications.enabled(this));add(content,alerts);
        add(content,text("Meldingen: maximaal één per 12 uur, niet direct vanaf Facebook. Android kan achtergrondcontroles uitstellen.",11,MUTED,false));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("AutoVinder-filters").setView(scroll)
            .setNegativeButton("Annuleren",null)
            .setPositiveButton("Filters opslaan",(dialog,which)->{
                prefs().edit()
                    .putInt("max_price",num(max,1500,100,5000))
                    .putInt("max_km",num(mileage,250000,1000,800000))
                    .putInt("min_year",num(year,1995,1980,2026))
                    .putString("keywords",keyword.getText().toString().trim())
                    .putString("city",city.getText().toString().trim())
                    .putInt("repair",num(repair,250,0,50000))
                    .putInt("other_costs",num(other,175,0,50000))
                    .putBoolean("include_damage",damage.isChecked())
                    .putBoolean("unknown_km",unknown.isChecked())
                    .putBoolean(DealNotifications.KEY_ENABLED,alerts.isChecked())
                    .apply();
                if(alerts.isChecked()&&!DealNotifications.allowed(this))
                   Toast.makeText(this,"Geef AutoVinder toestemming voor meldingen in Android-instellingen.",Toast.LENGTH_LONG).show();
                maxVisible=40;draw();
            }).show();
    }
    private void openFacebookSearch(){
        String query="auto "+strPref(prefs(),"keywords");
        try {
            open("https://www.facebook.com/marketplace/search/?query="+
                java.net.URLEncoder.encode(query.trim(),"UTF-8"));
        }catch(Exception e){open("https://www.facebook.com/marketplace/");}
    }
    private void addFacebookAd(){
        LinearLayout body=vertical();pad(body,17,14);
        add(body,text("Facebook is geen automatische bron. Kopieer een Marketplace-advertentielink en vul de gegevens zelf in.",12,MUTED,false));gap(body,11);
        EditText title=field(body,"Titel / merk en model","",false);
        EditText url=field(body,"Facebook Marketplace-link (https://…)","",false);
        EditText price=field(body,"Vraagprijs (€)","",true);
        EditText year=field(body,"Bouwjaar","",true);
        EditText km=field(body,"Kilometerstand","",true);
        EditText resale=field(body,"Eigen geschatte verkoopprijs (€) — optioneel","",true);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Facebook-advertentie bewaren")
            .setView(scroll).setNegativeButton("Annuleren",null)
            .setPositiveButton("Opslaan",(dialog,which)->{
                String address=url.getText().toString().trim();
                Uri parsed=Uri.parse(address);
                String host=parsed.getHost()==null?"":parsed.getHost().toLowerCase(Locale.ROOT);
                if(!address.startsWith("https://")||
                   !(host.equals("facebook.com")||host.endsWith(".facebook.com")||
                     host.equals("fb.com")||host.endsWith(".fb.com"))){
                    Toast.makeText(this,"Gebruik een Facebook Marketplace HTTPS-link.",Toast.LENGTH_LONG).show();return;
                }
                if(title.getText().toString().trim().isEmpty()){
                    Toast.makeText(this,"Titel ontbreekt",Toast.LENGTH_LONG).show();return;
                }
                try {
                    JSONObject d=new JSONObject();
                    d.put("id","fb:"+address);
                    d.put("source","Facebook (handmatig)");
                    d.put("title",title.getText().toString().trim());
                    d.put("description","");
                    d.put("url",address);
                    d.put("price",num(price,0,0,500000));
                    d.put("year",num(year,0,0,2026));
                    d.put("mileage",num(km,0,0,1000000));
                    d.put("price_kind","FIXED");
                    String[] split=title.getText().toString().trim().split("\\s+");
                    d.put("make",split.length>0?split[0]:"");
                    d.put("model",split.length>1?split[1]:"");
                    d.put("city","");
                    d.put("manual_resale",true);
                    if(!resale.getText().toString().trim().isEmpty()){
                        int value=num(resale,0,0,1000000);
                        d.put("resale_low",value);d.put("resale_high",value);
                    }
                    JSONArray arr=new JSONArray(prefs().getString("facebook_ads","[]"));
                    arr.put(d);prefs().edit().putString("facebook_ads",arr.toString()).apply();
                    selected="Facebook";draw();
                }catch(Exception ignored){
                    Toast.makeText(this,"Kon advertentie niet bewaren",Toast.LENGTH_LONG).show();
                }
            }).show();
    }
    private void deleteFacebook(String id){
        JSONArray old;
        try{old=new JSONArray(prefs().getString("facebook_ads","[]"));}
        catch(Exception e){return;}
        JSONArray arr=new JSONArray();
        for(int i=0;i<old.length();i++){
            JSONObject d=old.optJSONObject(i);
            if(d!=null&&!id.equals(d.optString("id")))arr.put(d);
        }
        prefs().edit().putString("facebook_ads",arr.toString()).apply();draw();
    }
    private void open(String address){
        try{
            Uri uri=Uri.parse(address);
            if(!"https".equalsIgnoreCase(uri.getScheme()))throw new Exception();
            startActivity(new Intent(Intent.ACTION_VIEW,uri));
        }catch(Exception e){
            Toast.makeText(this,"Kon de advertentie niet openen",Toast.LENGTH_SHORT).show();
        }
    }
    private void refresh(boolean manual){
        if(busy)return;
        busy=true;
        if(manual)Toast.makeText(this,"Autodata bijwerken…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            String err=null;
            try{FeedClient.fetch(getApplicationContext());}
            catch(Exception e){err=e.getMessage();FeedClient.saveError(this,err);}
            String failure=err;
            runOnUiThread(()->{
                busy=false;render();
                if(manual)Toast.makeText(this,
                    failure==null?"Feed bijgewerkt":"Fout bij verversen: "+failure,
                    Toast.LENGTH_LONG).show();
            });
        },"autovinder-sync").start();
    }
}
