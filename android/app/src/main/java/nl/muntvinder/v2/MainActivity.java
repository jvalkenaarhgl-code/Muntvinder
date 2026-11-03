package nl.muntvinder.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native Android UI. Toont alleen resultaten afkomstig uit de JSON-feed. */
public final class MainActivity extends Activity {
    private static final int GREEN = 0xFF10392F, DARK = 0xFF173B34,
            GOLD = 0xFFC89A48, BG = 0xFFF6F4EE, LIGHT = 0xFFFEFDF9,
            MUTED = 0xFF61716B, RED = 0xFFB14C3B;
    private final NumberFormat euros = NumberFormat.getCurrencyInstance(new Locale("nl", "NL"));
    private LinearLayout frame, listArea;
    private TextView statusText, countText;
    private String selected = "Alles";
    private boolean favoritesOnly = false;
    private boolean busy = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(GREEN);
        getWindow().setNavigationBarColor(GREEN);
        ScanJobService.schedule(this);
        draw();
        if (!FeedClient.url(this).isEmpty()) refreshOnline(false);
    }
    @Override protected void onResume() { super.onResume(); if (frame != null) render(); }

    private int px(int dp) { return (int)(getResources().getDisplayMetrics().density * dp + .5f); }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(px(radius)); return g;
    }
    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private TextView text(String s,int sp,int color,boolean bold) {
        TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setLineSpacing(px(2),1.0f); return v;
    }
    private void pad(View v, int lr,int tb) {v.setPadding(px(lr),px(tb),px(lr),px(tb));}
    private void add(LinearLayout to, View child) {
        to.addView(child,new LinearLayout.LayoutParams(-1,-2));
    }
    private void spacer(LinearLayout to,int height) {
        View v=new View(this);to.addView(v,new LinearLayout.LayoutParams(1,px(height)));
    }
    private Button button(String title, int bg, int color, Runnable action) {
        Button b = new Button(this);
        b.setText(title);b.setTextSize(12);b.setAllCaps(false);b.setTextColor(color);
        b.setBackground(rounded(bg,10));b.setOnClickListener(v->action.run());return b;
    }
    private void draw() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        frame=vertical();scroll.addView(frame);setContentView(scroll);

        LinearLayout top=vertical();top.setBackgroundColor(GREEN);pad(top,18,18);
        add(top,text("MUNTVINDER",12,GOLD,true));spacer(top,5);
        add(top,text("Slim Nederlandse proofsets vinden",21,Color.WHITE,true));spacer(top,7);
        add(top,text("Marktplaats: losse sets + bundels    •    Catawiki: bundels",12,0xFFD2E3D9,false));spacer(top,12);
        LinearLayout actions = new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh=button("↻  Verversen", GOLD,GREEN,()->refreshOnline(true));
        actions.addView(refresh,new LinearLayout.LayoutParams(0,px(42),1));
        View space=new View(this);actions.addView(space,new LinearLayout.LayoutParams(px(8),1));
        Button settings=button("⚙  Instellingen",0xFF28534A,Color.WHITE,this::settings);
        actions.addView(settings,new LinearLayout.LayoutParams(0,px(42),1));
        add(top,actions);add(frame,top);

        LinearLayout status=vertical();pad(status,18,13);
        statusText=text("",12,MUTED,false);add(status,statusText);
        countText=text("",20,DARK,true);spacer(status,7);add(status,countText);add(frame,status);

        HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);pad(tabs,14,4);
        for(String tab:new String[]{"Alles","Marktplaats","Catawiki","Bewaard"}) {
            boolean active=tab.equals(selected) || (favoritesOnly && tab.equals("Bewaard"));
            Button btn=button(tab,active?GREEN:LIGHT,active?Color.WHITE:MUTED,()->{
                selected=tab;favoritesOnly=tab.equals("Bewaard");draw();
            });
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,px(42));p.setMargins(px(4),0,px(4),0);
            tabs.addView(btn,p);
        }
        horizontal.addView(tabs);add(frame,horizontal);
        listArea=vertical();pad(listArea,16,12);add(frame,listArea);
        render();
    }
    private JSONObject getFeed() {
        String s=FeedClient.cached(this);
        try { return s.isEmpty()?new JSONObject():new JSONObject(s); }
        catch(Exception e){return new JSONObject();}
    }
    private void render() {
        if (listArea==null)return;
        listArea.removeAllViews();
        JSONObject feed=getFeed();JSONArray raw=feed.optJSONArray("deals");
        List<JSONObject> items=new ArrayList<>();
        Set<String> fav=favorites();
        if(raw!=null) for(int i=0;i<raw.length();i++) {
            JSONObject d=raw.optJSONObject(i);if(d==null)continue;
            String source=d.optString("source");
            if(favoritesOnly && !fav.contains(d.optString("id")))continue;
            if(!favoritesOnly && !selected.equals("Alles") && !source.equals(selected))continue;
            items.add(d);
        }
        // Volgorde in feed is best-buy rank, fallback laagste totaalprijs.
        countText.setText(items.size()+" koopkansen"+(favoritesOnly?" bewaard":" gevonden"));
        String generated=feed.optString("generated_at", "");
        String errors=feed.optJSONArray("errors")!=null && feed.optJSONArray("errors").length()>0
                ? " · Bronmelding: "+feed.optJSONArray("errors").optString(0):"";
        statusText.setText((generated.isEmpty()||generated.equals("null")?"Nog geen online scan":"Laatste bronscan: "+generated.replace('T',' ').replace("+00:00"," UTC"))
               +(feed.optBoolean("stale")?" · NIET VOLLEDIG ACTUEEL":"")+errors);
        if (FeedClient.prefs(this).contains(FeedClient.KEY_ERROR)) {
            note("Laatste downloadfout: "+FeedClient.prefs(this).getString(FeedClient.KEY_ERROR,""),RED);
        }
        if (FeedClient.url(this).isEmpty()) note("Nog geen gegevensbron gekoppeld. De app moet eerst met een actieve scanner worden gepubliceerd.",GREEN);
        if (items.isEmpty()) {
            note("Er zijn nog geen echte aanbiedingen beschikbaar in deze weergave. Er worden geen voorbeeldkoopjes verzonnen.",MUTED);
        } else {
            for(JSONObject d:items)dealCard(d,fav.contains(d.optString("id")));
        }
        spacer(listArea,22);
        add(listArea,text("Doorverkoopprijzen zijn indicaties op basis van actuele VRAAGprijzen, geen bewezen transacties. Controleer staat, volledigheid, verzendkosten en beschikbaarheid voor aankoop.",11,MUTED,false));
    }
    private void note(String message,int color) {
        LinearLayout l=vertical();pad(l,14,14);l.setBackground(rounded(LIGHT,12));
        add(l,text(message,13,color,false));add(listArea,l);spacer(listArea,12);
    }
    private static Double number(JSONObject o,String name) {
        return o.isNull(name)||!o.has(name)?null:o.optDouble(name,Double.NaN);
    }
    private String money(Double n) {return n==null||Double.isNaN(n)?"Onbekend":euros.format(n);}
    private void detail(LinearLayout card,String label,String value,int col) {
        add(card,text(label,11,MUTED,false));spacer(card,3);
        add(card,text(value,15,col,true));spacer(card,10);
    }
    private void dealCard(JSONObject d, boolean isFav) {
        LinearLayout card=vertical();pad(card,15,15);card.setBackground(rounded(LIGHT,16));
        String source=d.optString("source");
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView chip=text(source+(d.optBoolean("bundle")?" · BUNDEL":" · LOSSE SET"),11,GREEN,true);
        top.addView(chip,new LinearLayout.LayoutParams(0,-2,1));
        TextView star=text(isFav?"★":"☆",26,GOLD,true);pad(star,10,2);
        star.setOnClickListener(v->toggleFavorite(d.optString("id")));
        top.addView(star);add(card,top);spacer(card,7);
        add(card,text(d.optString("title"),16,DARK,true));spacer(card,12);

        String kind=d.optString("price_kind");
        Double price=number(d,"price"), total=number(d,"purchase_total"),lo=number(d,"resale_low"),hi=number(d,"resale_high");
        detail(card,source.equals("Catawiki")?"HUIDIG BOD":"VRAAGPRIJS / BOD",money(price)+(kind.equals("FIXED")?" · vaste prijs":""),DARK);
        if(total!=null)detail(card,"GESCHATTE TOTALE INKOOP",money(total),DARK);
        detail(card,"GESCHATTE MARKTPLAATS-DOORVERKOOP",lo==null?"Onvoldoende vergelijkingen":money(lo)+" – "+money(hi),GREEN);
        Double lowProfit=number(d,"profit_low"), highProfit=number(d,"profit_high");
        if(lowProfit!=null)detail(card,"MOGELIJKE MARGE (EXCL. EVENTUELE VERKOOPKOSTEN)",money(lowProfit)+" tot "+money(highProfit),lowProfit>=0?GREEN:RED);
        int count=d.optInt("comparison_count");
        add(card,text(count>0?"Gebaseerd op minimaal "+count+" vergelijkbare actieve vraagprijzen":"Geen betrouwbare waardeschatting: nog te weinig gelijkwaardige aanbiedingen",11,MUTED,false));
        spacer(card,8);
        String footnote=d.optString("purchase_note",d.optString("price_note",""));
        if(!footnote.isEmpty()) add(card,text(footnote,11,MUTED,false));
        spacer(card,13);
        Button go=button("Bekijk originele advertentie  ↗",GREEN,Color.WHITE,()->openUrl(d.optString("url")));
        add(card,go);
        add(listArea,card);spacer(listArea,13);
    }
    private Set<String> favorites() {
        return new HashSet<>(FeedClient.prefs(this).getStringSet("favorites",Collections.emptySet()));
    }
    private void toggleFavorite(String id) {
        Set<String> fav=favorites();if(fav.contains(id))fav.remove(id);else fav.add(id);
        FeedClient.prefs(this).edit().putStringSet("favorites",fav).apply();render();
    }
    private void openUrl(String address) {
        try {
            Uri uri=Uri.parse(address);
            if(!"https".equals(uri.getScheme()))throw new Exception();
            Intent i=new Intent(Intent.ACTION_VIEW,uri);startActivity(i);
        } catch(Exception e){Toast.makeText(this,"Geen geldige advertentielink",Toast.LENGTH_SHORT).show();}
    }
    private void refreshOnline(boolean manual) {
        if(busy)return;
        if(FeedClient.url(this).isEmpty()){if(manual)settings();return;}
        busy=true;
        if(manual)Toast.makeText(this,"Feed verversen…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            String err=null;
            try {FeedClient.fetch(getApplicationContext());}
            catch(Exception e){err=e.getMessage();FeedClient.saveError(getApplicationContext(),err);}
            String finalErr=err;
            runOnUiThread(()->{
                busy=false;
                render();
                if(manual)Toast.makeText(this,finalErr==null?"Gegevensfeed bijgewerkt":"Fout: "+finalErr,Toast.LENGTH_LONG).show();
            });
        },"muntvinder-refresh").start();
    }
    private void settings() {
        EditText edit=new EditText(this);edit.setText(FeedClient.url(this));
        edit.setSingleLine(false);edit.setMinLines(2);
        edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        edit.setHint("https://gebruikersnaam.github.io/muntvinder/feed.json");
        LinearLayout body=vertical();pad(body,18,12);
        add(body,text("De app gebruikt standaard een ingebouwde gegevensbron. Alleen om van scanner te wisselen hoef je hier een andere HTTPS-link op te geven. Android kan automatische verversingen uitstellen.",13,MUTED,false));
        spacer(body,12);add(body,edit);
        new AlertDialog.Builder(this).setTitle("Automatisch scannen instellen").setView(body)
            .setNegativeButton("Annuleren",null)
            .setPositiveButton("Opslaan",(dialog,which)->{
                try {FeedClient.saveUrl(this,edit.getText().toString().trim());ScanJobService.schedule(this);render();refreshOnline(true);}
                catch(IllegalArgumentException e) {Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
            }).show();
    }
}
