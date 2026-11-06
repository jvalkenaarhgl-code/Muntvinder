package nl.muntvinder.v2;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    private String selectedSource = "Alle bronnen";
    private String selectedType = "Alles";
    private boolean favoritesOnly = false;
    private ScrollView scrollRoot;
    private ProofsetPanel proofsets;
    private String activeTab = "scanner";
    private final Map<ImageView, String> photosPending = new HashMap<>();
    private boolean busy = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(GREEN);
        getWindow().setNavigationBarColor(GREEN);
        ScanJobService.schedule(this);
        proofsets = new ProofsetPanel(this);
        draw();
        maybeAskNotificationPermission();
        if (!FeedClient.url(this).isEmpty()) refreshOnline(false);
    }
    @Override protected void onResume() { super.onResume(); if (frame != null && "scanner".equals(activeTab)) render(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if(proofsets!=null)proofsets.onFileResult(request,result,data);
    }

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
        // One cohesive Android screen: only the BODY changes when selecting a tab.
        LinearLayout shell=vertical();shell.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        frame=vertical();scroll.addView(frame);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout navigation=new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setBackgroundColor(LIGHT);
        navigation.setPadding(px(6),px(6),px(6),px(6));
        String[] labels={"⌕  Scanner","▦  Rendement"};
        String[] ids={"scanner","rendement"};
        for(int i=0;i<ids.length;i++){
            final String tab=ids[i];
            boolean selected=tab.equals(activeTab);
            Button tabButton=button(labels[i],selected?GREEN:LIGHT,selected?Color.WHITE:GREEN,()->{
                if(tab.equals(activeTab))return;
                activeTab=tab;draw();
            });
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);
            p.setMargins(px(2),0,px(2),0);
            navigation.addView(tabButton,p);
        }
        shell.addView(navigation,new LinearLayout.LayoutParams(-1,px(67)));
        setContentView(shell);

        if(!"scanner".equals(activeTab)){
            scrollRoot=null;
            listArea=null;
            if(proofsets!=null)proofsets.render(frame,proofsets.isCalculator(),this::draw);
            return;
        }
        scrollRoot = scroll;
        scroll.setOnScrollChangeListener((View v,int sx,int sy,int oldX,int oldY)->loadVisiblePhotos());

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

        LinearLayout filterArea=vertical();pad(filterArea,12,4);
        add(filterArea,text("TYPE AANBIEDING",10,MUTED,true));
        HorizontalScrollView kindScroll=new HorizontalScrollView(this);
        kindScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout kindTabs=new LinearLayout(this);kindTabs.setOrientation(LinearLayout.HORIZONTAL);
        for(String tab:new String[]{"Alles","Losse sets","Bundels"}) {
            boolean active=selectedType.equals(tab);
            Button btn=button(tab,active?GREEN:LIGHT,active?Color.WHITE:MUTED,()->{
                selectedType=tab;draw();
            });
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,px(43));
            p.setMargins(px(3),px(6),px(4),px(5));kindTabs.addView(btn,p);
        }
        kindScroll.addView(kindTabs);add(filterArea,kindScroll);
        add(filterArea,text("BRON EN FAVORIETEN",10,MUTED,true));
        HorizontalScrollView sourceScroll=new HorizontalScrollView(this);
        sourceScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);
        for(String tab:new String[]{"Alle bronnen","Marktplaats","Catawiki","★ Bewaard"}) {
            boolean isSaved=tab.equals("★ Bewaard");
            boolean active=isSaved?favoritesOnly:(!favoritesOnly && tab.equals(selectedSource));
            Button btn=button(tab,active?GREEN:LIGHT,active?Color.WHITE:MUTED,()->{
                if(isSaved) favoritesOnly=true;
                else {favoritesOnly=false;selectedSource=tab;}
                draw();
            });
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,px(42));
            p.setMargins(px(3),px(6),px(4),px(4));tabs.addView(btn,p);
        }
        sourceScroll.addView(tabs);add(filterArea,sourceScroll);add(frame,filterArea);
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
        photosPending.clear();
        JSONObject feed=getFeed();JSONArray raw=feed.optJSONArray("deals");
        List<JSONObject> items=new ArrayList<>();
        Set<String> fav=favorites();
        if(raw!=null) for(int i=0;i<raw.length();i++) {
            JSONObject d=raw.optJSONObject(i);if(d==null)continue;
            String source=d.optString("source");
            if(favoritesOnly && !fav.contains(d.optString("id")))continue;
            if(!favoritesOnly && !selectedSource.equals("Alle bronnen") && !source.equals(selectedSource))continue;
            if(selectedType.equals("Bundels") && !d.optBoolean("bundle"))continue;
            if(selectedType.equals("Losse sets") && d.optBoolean("bundle"))continue;
            items.add(d);
        }
        // Volgorde in feed is best-buy rank, fallback laagste totaalprijs.
        int bundles=0;
        for(JSONObject d:items)if(d.optBoolean("bundle"))bundles++;
        countText.setText(items.size()+" advertenties · "+(items.size()-bundles)+" losse · "+bundles+" bundels");
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
        add(listArea,text("Foto's komen van de originele advertentie. Het type bundel/losse set wordt uit de tekst afgeleid; controleer de foto's en beschrijving. Doorverkoop is een indicatie, geen bewezen transactie.",11,MUTED,false));
        if(scrollRoot != null) listArea.post(this::loadVisiblePhotos);
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
        boolean bundle=d.optBoolean("bundle");
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels=vertical();
        add(labels,text(source.toUpperCase(Locale.ROOT),10,MUTED,true));
        spacer(labels,4);
        JSONArray ys=d.optJSONArray("years");
        String years=ys!=null && ys.length()==1?ys.optString(0):"";
        int numberOfSets=d.optInt("count",1);
        String kindLabel=bundle?("▦  BUNDEL"+(numberOfSets>1?" · "+numberOfSets+" SETS":" · MEERDERE SETS"))
                              :("●  LOSSE SET"+(!years.isEmpty()?" · "+years:""));
        TextView chip=text(kindLabel,12,bundle?0xFF764B0D:GREEN,true);
        chip.setBackground(rounded(bundle?0xFFFFEAC1:0xFFE1F1E7,9));
        pad(chip,9,6);
        labels.addView(chip,new LinearLayout.LayoutParams(-2,-2));
        top.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView star=text(isFav?"★":"☆",26,GOLD,true);pad(star,10,2);
        star.setOnClickListener(v->toggleFavorite(d.optString("id")));
        top.addView(star);add(card,top);spacer(card,12);
        add(card,text(d.optString("title"),17,DARK,true));spacer(card,6);
        String opportunity=d.optString("deal_label","");
        int position=d.optInt("deal_position",0);
        if(!opportunity.isEmpty()) {
            String prefix=position>0 && position<=3?"#"+position+" · ":"";
            add(card,text(prefix+opportunity,12,
                position==1 && d.optBoolean("alert_candidate",false)?GREEN:MUTED,true));
            spacer(card,9);
        }
        showCardPhoto(card,d);

        String kind=d.optString("price_kind");
        Double price=number(d,"price"), total=number(d,"purchase_total"),lo=number(d,"resale_low"),hi=number(d,"resale_high");
        boolean fixed=kind.equals("FIXED");
        String priceLabel=fixed?"VASTE VRAAGPRIJS":
                          kind.equals("MIN_BID")||kind.equals("FAST_BID")?"BIEDPRIJS — GEEN KOOPPRIJS":"PRIJS / BOD";
        detail(card,source.equals("Catawiki")?"HUIDIG BOD":priceLabel,money(price)+(fixed?" · vaste prijs":""),DARK);
        if(total!=null)detail(card,fixed?"GESCHATTE TOTALE INKOOP":"TOTAAL BIJ GETOOND BOD (NIET GEGARANDEERD)",money(total),DARK);
        detail(card,"VOORZICHTIGE DOORVERKOOPINDICATIE",lo==null?"Onvoldoende betrouwbare gegevens":money(lo)+" – "+money(hi),GREEN);

        Double shopPrice=number(d,"shop_price"),shopDifference=number(d,"shop_difference");
        String shopName=d.optString("shop_name","");
        if(shopPrice != null && !shopName.isEmpty() && !shopName.equals("null")) {
            detail(card,"LAAGSTE GEVONDEN WEBSHOPPRIJS",money(shopPrice)+" · "+shopName,GREEN);
            if(shopDifference!=null) {
                detail(card,"VERSCHIL MET WEBSHOP (GEEN WINST)",money(shopDifference),shopDifference>=0?GREEN:RED);
            }
            String stock=d.optString("shop_availability","");
            String shopNote=d.optString("shop_note","");
            // De scanner vermeldt voorraadonzekerheid al in shop_note.
            if(!shopNote.isEmpty()) {add(card,text(shopNote,11,MUTED,false));spacer(card,8);}
            String shopUrl=d.optString("shop_url","");
            if(shopUrl.startsWith("https://")){
                Button shopButton=button("Bekijk gevonden webshopprijs ↗",0xFFE7F0EB,GREEN,()->openUrl(shopUrl));
                add(card,shopButton);spacer(card,11);
            }
        } else {
            add(card,text("Webshopvergelijking: "+d.optString("shop_note","geen passende winkelprijs gevonden"),11,MUTED,false));
            spacer(card,8);
        }

        Double lowProfit=number(d,"profit_low"), highProfit=number(d,"profit_high");
        if(lowProfit!=null)detail(card,fixed?"MOGELIJKE MARGE (EXCL. EVENTUELE VERKOOPKOSTEN)":"HYPOTHETISCHE MARGE BIJ GETOOND BOD",money(lowProfit)+" tot "+money(highProfit),lowProfit>=0?GREEN:RED);
        int count=d.optInt("comparison_count");
        add(card,text(count>0?"Vergelijkingsbasis: "+count+" vaste Marktplaats-vraagprijs"+(count==1?"":"en")+"; geen gerealiseerde verkopen.":"Geen voldoende vergelijkbare Marktplaats-vraagprijzen.",11,MUTED,false));
        spacer(card,6);
        String valuationNote=d.optString("resale_note","");
        if(!valuationNote.isEmpty()) add(card,text(valuationNote,11,MUTED,false));
        spacer(card,8);
        String footnote=d.optString("purchase_note",d.optString("price_note",""));
        if(!footnote.isEmpty()) add(card,text(footnote,11,MUTED,false));
        spacer(card,13);
        Button go=button("Bekijk originele advertentie  ↗",GREEN,Color.WHITE,()->openUrl(d.optString("url")));
        add(card,go);
        spacer(card,8);
        Button saveDeal=button("＋  Neem over naar Mijn sets",0xFFE8F1EA,GREEN,()->{
            activeTab="rendement";
            proofsets.setCalculator(false);
            draw();
            proofsets.edit(null,d);
        });
        add(card,saveDeal);
        add(listArea,card);spacer(listArea,13);
    }
    /** Laad alleen afbeeldingen die in beeld staan: geen tientallen downloads bij openen. */
    private void loadVisiblePhotos() {
        if(scrollRoot == null)return;
        Rect visible=new Rect();
        Iterator<Map.Entry<ImageView,String>> it=photosPending.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<ImageView,String> entry=it.next();
            ImageView image=entry.getKey();
            if(image.isAttachedToWindow() && image.getGlobalVisibleRect(visible)) {
                PhotoLoader.load(image,entry.getValue());
                it.remove();
            }
        }
    }
    private List<String> adImages(JSONObject d) {
        List<String> urls=new ArrayList<>();
        JSONArray all=d.optJSONArray("image_urls");
        if(all!=null) for(int i=0;i<Math.min(8,all.length());i++) {
            String url=all.optString(i,"");
            if(url.startsWith("https://") && !urls.contains(url))urls.add(url);
        }
        if(urls.isEmpty()) {
            String one=d.optString("image_url","");
            if(one.startsWith("https://"))urls.add(one);
        }
        return urls;
    }
    private void showCardPhoto(LinearLayout card,JSONObject d) {
        List<String> pictures=adImages(d);
        if(pictures.isEmpty())return;
        ImageView preview=new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackground(rounded(0xFFE8EEE9,12));
        preview.setClipToOutline(true);
        card.addView(preview,new LinearLayout.LayoutParams(-1,px(182)));
        photosPending.put(preview,pictures.get(0));
        preview.setContentDescription("Open advertentiefoto's");
        preview.setOnClickListener(v->openGallery(d));
        spacer(card,6);
        if(pictures.size()>1) {
            Button allPhotos=button("▧  Bekijk alle "+pictures.size()+" advertentiefoto's",0xFFE8EEE9,GREEN,()->openGallery(d));
            add(card,allPhotos);spacer(card,8);
        }
    }
    private void openGallery(JSONObject d) {
        List<String> pictures=adImages(d);
        if(pictures.isEmpty()){openUrl(d.optString("url"));return;}
        LinearLayout body=vertical();pad(body,12,14);
        ImageView full=new ImageView(this);
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.setBackground(rounded(0xFFE8EEE9,8));
        body.addView(full,new LinearLayout.LayoutParams(-1,px(330)));
        spacer(body,12);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
        Button previous=button("‹ Vorige",0xFFE8EEE9,GREEN,()->{});
        TextView counter=text("",13,DARK,true);counter.setGravity(Gravity.CENTER);
        Button next=button("Volgende ›",0xFFE8EEE9,GREEN,()->{});
        controls.addView(previous,new LinearLayout.LayoutParams(0,px(45),1));
        controls.addView(counter,new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(next,new LinearLayout.LayoutParams(0,px(45),1));
        add(body,controls);
        final int[] index={0};
        Runnable update=()->{
            counter.setText((index[0]+1)+" / "+pictures.size());
            PhotoLoader.load(full,pictures.get(index[0]));
            previous.setEnabled(index[0]>0);
            next.setEnabled(index[0]<pictures.size()-1);
        };
        previous.setOnClickListener(v->{if(index[0]>0){index[0]--;update.run();}});
        next.setOnClickListener(v->{if(index[0]<pictures.size()-1){index[0]++;update.run();}});
        new AlertDialog.Builder(this).setTitle("Advertentiefoto's")
            .setView(body)
            .setNegativeButton("Sluiten",null)
            .setPositiveButton("Bekijk advertentie",(dialog,which)->openUrl(d.optString("url")))
            .show();
        update.run();
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
    private void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !FeedClient.prefs(this).getBoolean("deal_notification_permission_asked", false)) {
            FeedClient.prefs(this).edit().putBoolean("deal_notification_permission_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41027);
        }
    }
    private void settings() {
        EditText edit=new EditText(this);edit.setText(FeedClient.url(this));
        edit.setSingleLine(false);edit.setMinLines(2);
        edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        edit.setHint("https://raw.githubusercontent.com/gebruikersnaam/Muntvinder/main/docs/feed.json");
        LinearLayout body=vertical();pad(body,18,12);
        CheckBox notifications=new CheckBox(this);
        notifications.setText("Meldingen voor nieuwe topdeals");
        notifications.setChecked(DealNotifications.enabled(this));
        CheckBox bids=new CheckBox(this);
        bids.setText("Ook interessante biedingen melden");
        bids.setChecked(DealNotifications.bidsEnabled(this));
        add(body,text("Nieuwe deals komen bovenaan. Bij een nieuwe topdeal kan Android maximaal één melding per 12 uur sturen; de eerste download geeft geen oude meldingen. De telefoon controleert ongeveer elke 30 minuten en kan controles uitstellen door batterijbesparing.",12,MUTED,false));
        spacer(body,10);add(body,notifications);add(body,bids);
        spacer(body,8);
        add(body,text("Gegevensbron (standaard automatisch ingesteld)",12,MUTED,true));
        spacer(body,6);add(body,edit);
        new AlertDialog.Builder(this).setTitle("Muntvinder-instellingen").setView(body)
            .setNegativeButton("Annuleren",null)
            .setPositiveButton("Opslaan",(dialog,which)->{
                try {
                    FeedClient.saveUrl(this,edit.getText().toString().trim());
                    FeedClient.prefs(this).edit()
                        .putBoolean(DealNotifications.KEY_ENABLED,notifications.isChecked())
                        .putBoolean(DealNotifications.KEY_BIDS,bids.isChecked()).apply();
                    if(notifications.isChecked() && !DealNotifications.notificationsAllowed(this)) {
                        Toast.makeText(this,"Sta meldingen toe in Android-instellingen als ze zijn geblokkeerd.",Toast.LENGTH_LONG).show();
                    }
                    ScanJobService.schedule(this);render();refreshOnline(true);
                } catch(IllegalArgumentException e) {
                    Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();
                }
            }).show();
    }
}
