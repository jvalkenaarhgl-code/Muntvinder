package nl.muntvinder.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** One native Muntvinder screen for bookkeeping and calculator: no nested apps or WebViews. */
final class ProofsetPanel {
    private static final int GREEN=0xFF10392F, DARK=0xFF173B34, GOLD=0xFFC89A48,
        BG=0xFFF6F4EE, WHITE=Color.WHITE, MUTED=0xFF68796F, RED=0xFFAD403B, PALE=0xFFE7F0EA;
    private static final String PREF="muntvinder_native_ledger", DATA="items";
    static final int EXPORT=7211, IMPORT=7212;
    private final MainActivity activity;
    private final NumberFormat money=NumberFormat.getCurrencyInstance(new Locale("nl","NL"));
    private final List<JSONObject> items=new ArrayList<>();
    private String exportText;
    private Runnable refresh=()->{};
    ProofsetPanel(MainActivity a) { activity=a; load(); }

    private int px(int d){return (int)(d*activity.getResources().getDisplayMetrics().density+.5f);}
    private String today(){return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());}
    private GradientDrawable bg(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(px(radius));return d;
    }
    private TextView t(String value,int size,int color,boolean bold){
        TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setLineSpacing(px(2),1.0f);return v;
    }
    private LinearLayout col(){LinearLayout l=new LinearLayout(activity);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void pad(View v,int h,int vpad){v.setPadding(px(h),px(vpad),px(h),px(vpad));}
    private void add(LinearLayout p,View child){p.addView(child,new LinearLayout.LayoutParams(-1,-2));}
    private void gap(LinearLayout p,int h){View v=new View(activity);p.addView(v,new LinearLayout.LayoutParams(1,px(h)));}
    private Button button(String label,int fill,int fg,Runnable cb){
        Button v=new Button(activity);v.setText(label);v.setAllCaps(false);v.setTextSize(13);
        v.setTextColor(fg);v.setBackground(bg(fill,11));v.setMinHeight(px(48));
        v.setOnClickListener(w->cb.run());return v;
    }
    private LinearLayout card(){LinearLayout c=col();pad(c,16,17);c.setBackground(bg(WHITE,16));return c;}
    private void heading(LinearLayout body,String kicker,String title,String subtitle){
        LinearLayout top=col();pad(top,18,22);top.setBackgroundColor(GREEN);
        add(top,t(kicker,11,GOLD,true));gap(top,6);add(top,t(title,23,WHITE,true));gap(top,6);
        add(top,t(subtitle,12,0xFFD2E3D9,false));add(body,top);
    }
    private void inset(LinearLayout outer,View child,int bottom){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(px(16),px(12),px(16),px(bottom));outer.addView(child,lp);
    }
    private void load(){
        items.clear();
        try{JSONArray a=new JSONArray(activity.getSharedPreferences(PREF,0).getString(DATA,"[]"));
            for(int i=0;i<a.length();i++)if(a.optJSONObject(i)!=null)items.add(a.getJSONObject(i));
        }catch(Exception ignored){}
    }
    private void persist(){
        JSONArray a=new JSONArray();for(JSONObject x:items)a.put(x);
        boolean ok=activity.getSharedPreferences(PREF,0).edit().putString(DATA,a.toString()).commit();
        if(!ok)Toast.makeText(activity,"Opslaan is mislukt",Toast.LENGTH_LONG).show();
    }
    private static double n(JSONObject j,String k){
        if(!j.has(k)||j.isNull(k))return 0;
        Object raw=j.opt(k);
        String value=String.valueOf(raw).trim().replace("€","").replace(" ","");
        if(value.contains(","))value=value.replace(".","").replace(",",".");
        try{double amount=Double.parseDouble(value);return Double.isFinite(amount)?Math.max(0,amount):0;}
        catch(Exception ex){return 0;}
    }
    private static boolean hasPrice(JSONObject j){
        return j.has("sale")&&!j.isNull("sale")&&!j.optString("sale","").trim().isEmpty();
    }
    private static boolean sold(JSONObject j){return j.optString("status","active").equals("sold");}
    private static double paid(JSONObject j){return n(j,"buy")+n(j,"shipping")+n(j,"other");}
    private static double commission(JSONObject j){return (n(j,"fee")/100)*n(j,"sale");}
    private static double profit(JSONObject j){return n(j,"sale")-paid(j)-n(j,"selling")-commission(j);}
    private static double roi(JSONObject j){return paid(j)>0 ? profit(j)/paid(j)*100 : 0;}
    private static double margin(JSONObject j){return n(j,"sale")>0 ? profit(j)/n(j,"sale")*100 : 0;}
    private String eur(double value){return money.format(value);}
    private String name(JSONObject j){
        String x=j.optString("name","").trim();
        return x.isEmpty()?j.optString("type","Proofset")+" "+j.optString("year",""):x;
    }
    private String pct(double v){return String.format(new Locale("nl","NL"),"%.1f%%",v);}
    private void infoCard(LinearLayout host,String caption,String value,String note,int tone){
        LinearLayout c=card();add(c,t(caption,12,MUTED,true));gap(c,8);
        add(c,t(value,22,tone,true));gap(c,4);add(c,t(note,11,MUTED,false));inset(host,c,2);
    }

    void render(LinearLayout root,boolean calculator,Runnable redraw) {
        refresh=redraw;
        if(calculator){renderCalculator(root);return;}
        heading(root,"MIJN PROOFSETS","Aankopen & rendement","Houd je verzameling, verkoop en winst bij.");
        double invested=0,realized=0,forecast=0;int soldCount=0,unsold=0,unknown=0;
        for(JSONObject item:items){
            invested+=paid(item);
            if(sold(item)&&hasPrice(item)){realized+=profit(item);soldCount++;}
            else if(!sold(item)){unsold++;if(hasPrice(item))forecast+=profit(item);else unknown++;}
        }
        LinearLayout stats=col();gap(stats,5);
        infoCard(stats,"TOTAAL GEÏNVESTEERD",eur(invested),items.size()+" geregistreerde proofsets",DARK);
        LinearLayout both=row();
        LinearLayout realizedCard=card();add(realizedCard,t("Gerealiseerde winst",11,MUTED,true));gap(realizedCard,7);
        add(realizedCard,t(eur(realized),19,realized<0?RED:GREEN,true));gap(realizedCard,4);
        add(realizedCard,t(soldCount+" verkocht",11,MUTED,false));
        both.addView(realizedCard,new LinearLayout.LayoutParams(0,-2,1));
        View sp=new View(activity);both.addView(sp,new LinearLayout.LayoutParams(px(9),1));
        LinearLayout prospectCard=card();add(prospectCard,t("Mogelijke winst",11,MUTED,true));gap(prospectCard,7);
        add(prospectCard,t(eur(forecast),19,forecast<0?RED:GREEN,true));gap(prospectCard,4);
        add(prospectCard,t(unknown+" zonder vraagprijs",11,MUTED,false));
        both.addView(prospectCard,new LinearLayout.LayoutParams(0,-2,1));
        inset(stats,both,6);
        add(root,stats);

        LinearLayout controls=row();
        Button add=button("＋  Nieuwe proofset",GOLD,GREEN,()->edit(null,null));
        controls.addView(add,new LinearLayout.LayoutParams(0,px(48),1));
        View sp=new View(activity);controls.addView(sp,new LinearLayout.LayoutParams(px(8),1));
        Button backup=button("Back-up",PALE,GREEN,this::backupMenu);
        controls.addView(backup,new LinearLayout.LayoutParams(px(110),px(48)));
        inset(root,controls,4);

        LinearLayout title=col();pad(title,18,4);
        add(title,t("Mijn collectie",19,DARK,true));
        add(title,t(unsold+" nog te koop  •  "+soldCount+" verkocht",12,MUTED,false));
        add(root,title);
        if(items.isEmpty()){
            LinearLayout empty=card();add(empty,t("Nog geen proofsets toegevoegd",16,DARK,true));gap(empty,8);
            add(empty,t("Voeg een aankoop toe of registreer een aanbieding rechtstreeks vanuit de scanner.",13,MUTED,false));
            gap(empty,14);add(empty,button("＋ Eerste aankoop",GREEN,WHITE,()->edit(null,null)));
            inset(root,empty,14);
        }
        for(JSONObject item:new ArrayList<>(items)){
            LinearLayout c=card();
            LinearLayout rr=row();TextView label=t(name(item),16,DARK,true);
            rr.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            TextView chip=t(sold(item)?"VERKOCHT":"IN VOORRAAD",10,sold(item)?GREEN:0xFF926A25,true);
            pad(chip,7,6);chip.setBackground(bg(sold(item)?0xFFE1F1E7:0xFFFFEFCF,8));rr.addView(chip);
            add(c,rr);gap(c,5);
            add(c,t(item.optString("type","Proofset")+" · "+item.optString("year",""),12,MUTED,false));
            gap(c,12);
            add(c,t("Inkoop totaal  "+eur(paid(item)),13,DARK,true));gap(c,4);
            if(hasPrice(item)){
                add(c,t((sold(item)?"Verkocht voor ":"Verwachte verkoop ")+eur(n(item,"sale")),12,MUTED,false));
                gap(c,4);add(c,t((sold(item)?"Nettowinst ":"Mogelijke winst ")+eur(profit(item))+
                    "   ·   ROI "+pct(roi(item)),13,profit(item)>=0?GREEN:RED,true));
            } else add(c,t("Verkoopprijs nog onbekend",12,MUTED,false));
            gap(c,12);
            add(c,button("Bewerken / verkoop vastleggen",PALE,GREEN,()->edit(item,null)));
            inset(root,c,3);
        }
        gap(root,18);
        LinearLayout foot=col();pad(foot,18,5);
        add(foot,t("Mogelijke winst is een berekening op basis van je eigen verwachte verkoopprijzen, geen gegarandeerde opbrengst. Alleen verkochte sets tellen mee als gerealiseerde winst.",11,MUTED,false));
        add(root,foot);gap(root,30);
    }

    void edit(JSONObject existing,JSONObject offer){
        JSONObject record=existing;
        if(record==null){record=new JSONObject();
            try{record.put("status","active");record.put("type","Proofset");record.put("year",new Date().getYear()+1900);
                record.put("buyDate",today());record.put("days",30);record.put("target",20);
                if(offer!=null){
                    record.put("name",offer.optString("title",""));
                    JSONArray years=offer.optJSONArray("years");
                    if(years!=null&&years.length()==1){int y=years.optInt(0);if(y>=1900&&y<=2100)record.put("year",y);}
                    String kind=offer.optString("price_kind","");
                    if(kind.equals("FIXED")&&offer.optDouble("price",0)>0)record.put("buy",offer.optDouble("price"));
                    String description=offer.optString("title","").toLowerCase(Locale.ROOT);
                    if(description.contains("proofjaarset"))record.put("type","Proofjaarset");
                    record.put("note","Bron: "+offer.optString("source","")+" · "+offer.optString("url",""));
                }
            }catch(Exception ignored){}
        }
        JSONObject initial=record;
        ScrollView sc=new ScrollView(activity);sc.setFillViewport(false);
        LinearLayout form=col();pad(form,17,10);sc.addView(form);
        add(form,t("Verkoopprijs mag leeg blijven zolang de set niet verkocht is.",12,MUTED,false));gap(form,12);
        EditText year=field(form,"Jaartal *",initial.optString("year","2026"),true);
        Spinner type=spinner(form,"Type muntset",new String[]{"Proofset","Proofjaarset","Transitieset","Speciale set","Overig"},
            initial.optString("type","Proofset"));
        EditText name=field(form,"Naam / omschrijving",initial.optString("name",""),false);
        gap(form,5);add(form,t("INKOOP",11,GOLD,true));gap(form,6);
        EditText buy=field(form,"Koopprijs (€) *",raw(initial,"buy"),true);
        EditText shipping=field(form,"Verzendkosten aankoop (€)",raw(initial,"shipping"),true);
        EditText other=field(form,"Overige inkoopkosten (€)",raw(initial,"other"),true);
        gap(form,10);add(form,t("VERKOOP & RENDEMENT",11,GOLD,true));gap(form,6);
        Spinner status=spinner(form,"Status",new String[]{"Nog te koop","Verkocht"},sold(initial)?"Verkocht":"Nog te koop");
        EditText sale=field(form,"Verwachte of gerealiseerde verkoopprijs (€)",raw(initial,"sale"),true);
        EditText selling=field(form,"Verkoopkosten / verzending (€)",raw(initial,"selling"),true);
        EditText fee=field(form,"Platformcommissie (%)",raw(initial,"fee"),true);
        EditText days=field(form,"Verkoopduur in dagen (verwacht)",initial.optString("days","30"),true);
        EditText target=field(form,"Gewenste nettomarge (%)",initial.optString("target","20"),true);
        gap(form,10);add(form,t("DATUM & NOTITIES",11,GOLD,true));gap(form,6);
        EditText buyDate=field(form,"Aankoopdatum (jjjj-mm-dd)",initial.optString("buyDate",today()),false);
        EditText saleDate=field(form,"Verkoopdatum (als verkocht)",initial.optString("saleDate",today()),false);
        EditText note=field(form,"Notities / advertentielink",initial.optString("note",""),false);
        gap(form,12);add(form,t("Berekening: netto verkoopopbrengst minus inkoop, verzending, overige kosten en commissie.",11,MUTED,false));
        gap(form,15);
        final String[] err={""};
        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle(existing==null?"Nieuwe proofset":"Proofset bewerken")
            .setView(sc).setNegativeButton("Annuleren",null).setPositiveButton("Proofset opslaan",null);
        if(existing!=null)builder.setNeutralButton("Verwijderen",null);
        AlertDialog dlg=builder.create();dlg.setOnShowListener(v->{
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                try {
                    int y=Integer.parseInt(year.getText().toString().trim());
                    if(y<1900||y>2100)throw new IllegalArgumentException("Controleer het jaartal.");
                    double b=num(buy,true),sh=num(shipping,false),o=num(other,false),
                        s=num(sale,false),sell=num(selling,false),f=num(fee,false),
                        d=num(days,false),tg=num(target,false);
                    boolean known=!sale.getText().toString().trim().isEmpty();
                    if(b<=0)throw new IllegalArgumentException("Vul een koopprijs hoger dan €0 in.");
                    if(f>=100||tg>=100)throw new IllegalArgumentException("Percentages moeten lager zijn dan 100.");
                    if(d<1||d>36500)throw new IllegalArgumentException("Verkoopduur moet 1 tot 36.500 dagen zijn.");
                    boolean isSold=status.getSelectedItemPosition()==1;
                    if(isSold&&!known)throw new IllegalArgumentException("Vul bij 'Verkocht' de werkelijke verkoopprijs in.");
                    if(!validDate(buyDate.getText().toString()))throw new IllegalArgumentException("Aankoopdatum: gebruik jjjj-mm-dd.");
                    if(isSold&&!validDate(saleDate.getText().toString()))throw new IllegalArgumentException("Verkoopdatum: gebruik jjjj-mm-dd.");
                    JSONObject j=new JSONObject();
                    j.put("id",initial.optString("id",UUID.randomUUID().toString()));
                    j.put("year",y);j.put("type",type.getSelectedItem().toString());
                    j.put("name",name.getText().toString().trim());
                    j.put("buy",b);j.put("shipping",sh);j.put("other",o);
                    if(known)j.put("sale",s);else j.put("sale",JSONObject.NULL);
                    j.put("selling",sell);j.put("fee",f);j.put("days",(int)d);j.put("target",tg);
                    j.put("status",isSold?"sold":"active");j.put("buyDate",buyDate.getText().toString().trim());
                    j.put("saleDate",isSold?saleDate.getText().toString().trim():"");
                    j.put("note",note.getText().toString().trim());
                    j.put("createdAt",initial.optString("createdAt",new Date().toString()));
                    if(existing!=null){
                        for(int i=0;i<items.size();i++)if(items.get(i).optString("id").equals(existing.optString("id"))){items.set(i,j);break;}
                    }else items.add(0,j);
                    persist();dlg.dismiss();refresh.run();
                    Toast.makeText(activity,"Proofset opgeslagen",Toast.LENGTH_SHORT).show();
                }catch(IllegalArgumentException ex){Toast.makeText(activity,ex.getMessage(),Toast.LENGTH_LONG).show();}
                catch(Exception ex){Toast.makeText(activity,"Controleer je invoer.",Toast.LENGTH_LONG).show();}
            });
            if(existing!=null)dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(w->
                new AlertDialog.Builder(activity).setTitle("Proofset verwijderen?")
                    .setMessage(name(existing)+" definitief verwijderen?")
                    .setNegativeButton("Annuleren",null)
                    .setPositiveButton("Verwijderen",(d,which)->{
                        items.removeIf(x->x.optString("id").equals(existing.optString("id")));
                        persist();dlg.dismiss();refresh.run();
                    }).show());
        });
        dlg.show();
    }
    private String raw(JSONObject obj,String key){return obj.has(key)&&!obj.isNull(key)?obj.optString(key):"";}
    private boolean validDate(String date){
        if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))return false;
        try{SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);f.setLenient(false);return f.parse(date)!=null;}
        catch(Exception ex){return false;}
    }
    private double num(EditText input,boolean required){
        String raw=input.getText().toString().trim().replace("€","").replace(" ","");
        if(raw.isEmpty()){if(required)throw new IllegalArgumentException("Vul de verplichte bedragen in.");return 0;}
        if(raw.contains(","))raw=raw.replace(".","").replace(",",".");
        double v;
        try{v=Double.parseDouble(raw);}catch(Exception e){throw new IllegalArgumentException("Vul geldige getallen in.");}
        if(!Double.isFinite(v)||v<0||v>100000000)throw new IllegalArgumentException("Gebruik positieve, geldige bedragen.");
        return v;
    }
    private EditText field(LinearLayout parent,String label,String initial,boolean numeric){
        add(parent,t(label,12,MUTED,true));gap(parent,4);
        EditText e=new EditText(activity);e.setText(initial);e.setTextColor(DARK);e.setTextSize(15);
        e.setSingleLine(true);e.setBackground(bg(0xFFF4F6F3,10));pad(e,12,11);
        e.setInputType(numeric?(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED):
            InputType.TYPE_CLASS_TEXT);
        if(!numeric&&label.contains("Notities")){e.setSingleLine(false);e.setMinLines(2);}
        add(parent,e);gap(parent,12);return e;
    }
    private Spinner spinner(LinearLayout parent,String label,String[] options,String selected){
        add(parent,t(label,12,MUTED,true));gap(parent,4);
        Spinner spin=new Spinner(activity);spin.setBackground(bg(0xFFF4F6F3,10));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spin.setAdapter(adapter);for(int i=0;i<options.length;i++)if(options[i].equals(selected))spin.setSelection(i);
        add(parent,spin);gap(parent,14);return spin;
    }

    private void renderCalculator(LinearLayout root){
        heading(root,"WINSTCALCULATOR","Bereken je rendement","Probeer een verkoopprijs zonder een aankoop op te slaan.");
        LinearLayout c=card();
        add(c,t("Koop- en verkoopscenario",18,DARK,true));gap(c,12);
        EditText buy=field(c,"Koopprijs (€)","",true);
        EditText shipping=field(c,"Verzendkosten aankoop (€)","0",true);
        EditText other=field(c,"Overige inkoopkosten (€)","0",true);
        EditText sale=field(c,"Verwachte verkoopprijs (€)","",true);
        EditText selling=field(c,"Verkoopkosten (€)","0",true);
        EditText fee=field(c,"Commissie (%)","0",true);
        EditText days=field(c,"Verkoopduur (dagen)","30",true);
        EditText target=field(c,"Gewenste nettomarge (%)","20",true);
        inset(root,c,5);
        LinearLayout result=card();add(result,t("RESULTAAT",11,MUTED,true));gap(result,10);
        TextView profit=t("Vul een koopprijs in",22,GREEN,true);add(result,profit);gap(result,5);
        TextView otherStats=t("",13,MUTED,false);add(result,otherStats);gap(result,10);
        TextView advice=t("",12,MUTED,false);add(result,advice);
        inset(root,result,15);
        Runnable calculate=()->{
            try{
                double b=num(buy,false)+num(shipping,false)+num(other,false);
                if(b<=0){profit.setText("Vul een koopprijs in");otherStats.setText("");advice.setText("");return;}
                double f=num(fee,false)/100.0, fixed=num(selling,false), targetPct=num(target,false)/100.0;
                if(f>=1||targetPct>=1){profit.setText("Ongeldig percentage");otherStats.setText("");return;}
                double breakeven=(b+fixed)/(1-f);
                double targetSale=(1-f-targetPct)>0?(b+fixed)/(1-f-targetPct):Double.NaN;
                double s=num(sale,false), duration=Math.max(1,num(days,false));
                if(sale.getText().toString().trim().isEmpty()){
                    profit.setText("Verkoopprijs onbekend");
                    otherStats.setText("Totale inkoop: "+eur(b)+"\nBreak-even verkoopprijs: "+eur(breakeven));
                }else {
                    double p=s*(1-f)-b-fixed;
                    profit.setText("Nettowinst  "+eur(p));profit.setTextColor(p<0?RED:GREEN);
                    otherStats.setText("Rendement op inkoop: "+pct(p/b*100)+"\n"+
                      "Nettomarge op verkoop: "+pct(s>0?p/s*100:0)+"\n"+
                      "Winst per dag (bij "+(int)duration+" dagen): "+eur(p/duration)+"\n"+
                      "Break-even verkoopprijs: "+eur(breakeven));
                }
                advice.setText(Double.isFinite(targetSale)?"Minimale verkoopprijs voor "+pct(targetPct*100)+" nettomarge: "+eur(targetSale):
                    "Deze doelmarge is met de opgegeven commissie niet haalbaar.");
            }catch(Exception ex){profit.setText("Controleer de bedragen");otherStats.setText("");}
        };
        TextWatcher watcher=new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){calculate.run();}
            public void afterTextChanged(Editable e){}
        };
        for(EditText e:new EditText[]{buy,shipping,other,sale,selling,fee,days,target})e.addTextChangedListener(watcher);
        calculate.run();gap(root,20);
    }

    private void backupMenu(){
        new AlertDialog.Builder(activity).setTitle("Back-up van je proofsets")
          .setItems(new String[]{"Exporteer JSON-back-up","Importeer JSON-back-up"},(d,i)->{
              if(i==0)exportBackup();else importBackup();
          }).setNegativeButton("Sluiten",null).show();
    }
    private void exportBackup(){
        JSONArray array=new JSONArray();for(JSONObject j:items)array.put(j);
        JSONObject obj=new JSONObject();
        try{obj.put("app","Muntvinder");obj.put("version",1);obj.put("records",array);}catch(Exception ignored){}
        exportText=obj.toString();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,"muntvinder-proofsets-"+today()+".json");
        activity.startActivityForResult(intent,EXPORT);
    }
    private void importBackup(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        activity.startActivityForResult(intent,IMPORT);
    }
    void onFileResult(int request,int result,Intent data){
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return;
        try{
            Uri u=data.getData();
            if(request==EXPORT){
                try(OutputStream out=activity.getContentResolver().openOutputStream(u)){
                    if(out==null)throw new Exception("Bestand niet beschikbaar");
                    out.write(exportText.getBytes(StandardCharsets.UTF_8));}
                Toast.makeText(activity,"Back-up opgeslagen",Toast.LENGTH_LONG).show();
            }else if(request==IMPORT){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=activity.getContentResolver().openInputStream(u)){
                    if(in==null)throw new Exception("Kon bestand niet lezen");
                    byte[] buffer=new byte[8192];int count;
                    while((count=in.read(buffer))!=-1){
                        if(bytes.size()+count>4_000_000)throw new Exception("Bestand is te groot");
                        bytes.write(buffer,0,count);
                    }
                }
                JSONObject backup=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                String app=backup.optString("app","");
                if(!app.equals("Muntvinder")&&!app.equals("Proofset Rendement"))throw new Exception("Ongeldig back-upbestand");
                JSONArray records=backup.optJSONArray("records");
                if(records==null)throw new Exception("Geen records gevonden");
                Set<String> ids=new HashSet<>();for(JSONObject r:items)ids.add(r.optString("id"));
                int imported=0;
                for(int i=0;i<records.length()&&i<10000;i++){
                    JSONObject j=records.optJSONObject(i);
                    if(j==null)continue;
                    String id=j.optString("id","");
                    int year=j.optInt("year",0);
                    if(id.isEmpty()||year<1900||year>2100||ids.contains(id))continue;
                    if(n(j,"buy")<=0)continue;
                    items.add(j);ids.add(id);imported++;
                }
                persist();refresh.run();
                Toast.makeText(activity,imported+" proofsets geïmporteerd",Toast.LENGTH_LONG).show();
            }
        }catch(Exception e){Toast.makeText(activity,"Back-up mislukt: "+e.getMessage(),Toast.LENGTH_LONG).show();}
        finally{exportText=null;}
    }
}
