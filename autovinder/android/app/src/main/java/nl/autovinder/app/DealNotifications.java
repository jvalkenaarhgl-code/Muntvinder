package nl.autovinder.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Locale;

final class DealNotifications {
    static final String KEY_ENABLED="notifications_enabled";
    private static final String KEY_SEEN="seen_deal_ids";
    private static final String KEY_BASELINED="initial_feed_observed";
    private static final String KEY_LAST="last_notification";
    private static final long COOLDOWN=12L*60*60*1000;
    private static final String CHANNEL="autovinder_kansen";
    private DealNotifications(){}

    static boolean enabled(Context c){return FeedClient.prefs(c).getBoolean(KEY_ENABLED,true);}
    static boolean allowed(Context ctx){
        if(Build.VERSION.SDK_INT>=33
           &&ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            return false;
        NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm!=null && nm.areNotificationsEnabled();
    }
    static void onNewFeed(Context ctx,JSONObject feed){
        JSONArray arr=feed.optJSONArray("deals");
        if(arr==null||arr.length()==0||feed.optBoolean("stale",false))return;
        SharedPreferences prefs=FeedClient.prefs(ctx);
        Set<String> old=prefs.getStringSet(KEY_SEEN,Collections.emptySet());
        LinkedHashSet<String> seen=new LinkedHashSet<>(old==null?Collections.emptySet():old);
        boolean baseline=prefs.getBoolean(KEY_BASELINED,false);
        boolean mayAlert=baseline && enabled(ctx) && allowed(ctx)
             &&System.currentTimeMillis()-prefs.getLong(KEY_LAST,0)>=COOLDOWN;
        JSONObject best=null;
        int eligibleChecked=0;
        for(int i=0;i<arr.length();i++){
            JSONObject d=arr.optJSONObject(i);
            if(d==null)continue;
            String id=d.optString("id","");
            if(id.isEmpty())continue;
            boolean fresh=!seen.contains(id);
            seen.add(id);
            if(!mayAlert||!fresh||eligibleChecked>=50||!MainActivity.matchesFilters(d,prefs))
                continue;
            eligibleChecked++;
            // Meldingen alleen met aannemelijke positieve netto marge, niet bij wilde aannames.
            double low=d.optDouble("resale_low",Double.NaN);
            double buy=d.optDouble("price",Double.NaN);
            if(!Double.isFinite(low)||!Double.isFinite(buy))continue;
            double costs=prefs.getInt("repair",250)+prefs.getInt("other_costs",175);
            if(low-buy-costs<=0)continue;
            if(d.optInt("comparables",0)<3 || d.optBoolean("high_risk",false))continue;
            if(!"FIXED".equals(d.optString("price_kind")))continue;
            best=d;break; // feed staat gesorteerd, eerste geschikte nieuwe aanbieding
        }
        while(seen.size()>1500)seen.remove(seen.iterator().next());
        prefs.edit().putStringSet(KEY_SEEN,new HashSet<>(seen))
             .putBoolean(KEY_BASELINED,true).apply();
        if(best!=null&&send(ctx,best,prefs))
            prefs.edit().putLong(KEY_LAST,System.currentTimeMillis()).apply();
    }
    private static boolean send(Context ctx,JSONObject d,SharedPreferences prefs){
        if(!allowed(ctx))return false;
        NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm==null)return false;
        NotificationChannel ch=new NotificationChannel(CHANNEL,"Nieuwe AutoVinder-deals",
            NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Nieuwe onderbouwde autokoopkansen, maximaal één melding per twaalf uur");
        nm.createNotificationChannel(ch);
        Intent intent=new Intent(ctx,MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap=PendingIntent.getActivity(ctx,0,intent,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        double profit=d.optDouble("resale_low")-d.optDouble("price")
                     -prefs.getInt("repair",250)-prefs.getInt("other_costs",175);
        String body=d.optString("title","Auto")+"\n"+
            String.format(Locale.forLanguageTag("nl-NL"),"Voorzichtige mogelijke marge vanaf €%.0f, exclusief onvoorziene reparaties",profit);
        Notification note=new Notification.Builder(ctx,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Nieuwe interessante autodeal")
            .setContentText(d.optString("title","Auto"))
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(tap).setAutoCancel(true).build();
        nm.notify(51128,note);
        return true;
    }
}
