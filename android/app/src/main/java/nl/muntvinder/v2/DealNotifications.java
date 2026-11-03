package nl.muntvinder.v2;

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
import java.util.Locale;
import java.util.Set;

/**
 * Meld alleen een NIEUWE topdeal na een achtergrondfeed-update.
 * Dit zijn lokale Android-meldingen (geen Firebase/instant server-push).
 */
final class DealNotifications {
    static final String KEY_ENABLED = "deal_notifications_enabled";
    static final String KEY_BIDS = "deal_notifications_bids";
    private static final String KEY_SEEN = "deal_notifications_seen";
    private static final String KEY_BASELINED = "deal_notifications_baselined";
    private static final String KEY_LAST = "deal_notifications_last";
    private static final String CHANNEL_ID = "muntvinder_koopkansen";
    private static final long COOLDOWN_MS = 12L * 60 * 60 * 1000;
    private static final int ALERT_ID = 43104;

    private DealNotifications() {}

    static boolean enabled(Context ctx) {
        return FeedClient.prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    static boolean bidsEnabled(Context ctx) {
        return FeedClient.prefs(ctx).getBoolean(KEY_BIDS, true);
    }

    /**
     * De eerste geslaagde feed is alleen een uitgangssituatie: geen
     * meldingenstorm voor bestaande advertenties bij installatie.
     */
    static void onNewFeed(Context ctx, JSONObject feed) {
        JSONArray listings = feed.optJSONArray("deals");
        if (listings == null || listings.length() == 0 || feed.optBoolean("stale", false)) return;

        SharedPreferences prefs = FeedClient.prefs(ctx);
        Set<String> stored = prefs.getStringSet(KEY_SEEN, Collections.emptySet());
        LinkedHashSet<String> seen = new LinkedHashSet<>(stored == null ? Collections.emptySet() : stored);
        boolean hadBaseline = prefs.getBoolean(KEY_BASELINED, false);
        boolean allowed = enabled(ctx) && notificationsAllowed(ctx);
        boolean allowBids = bidsEnabled(ctx);
        boolean notCooling = System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0) >= COOLDOWN_MS;
        JSONObject bestNew = null;

        for (int i=0; i<listings.length(); i++) {
            JSONObject deal = listings.optJSONObject(i);
            if (deal == null) continue;
            String id = deal.optString("id", "");
            if (id.isEmpty()) continue;

            boolean isNew = !seen.contains(id);
            if (hadBaseline && allowed && notCooling && isNew
                    && deal.optBoolean("alert_candidate", false)
                    && deal.optInt("deal_position", 99) <= 3) {
                String kind=deal.optString("price_kind", "");
                boolean fixed = "FIXED".equals(kind);
                boolean bid = "MIN_BID".equals(kind) || "FAST_BID".equals(kind);
                if (fixed || (allowBids && bid)) {
                    // De feed is gerangschikt: eerste nieuwe geschikte deal is de beste.
                    if (bestNew == null) bestNew = deal;
                }
            }
            seen.add(id);
        }

        // Bewaar laatst bekende IDs; voorkom onbeperkt groeiende voorkeuren.
        while (seen.size() > 800) {
            String first = seen.iterator().next();
            seen.remove(first);
        }
        prefs.edit().putStringSet(KEY_SEEN, new HashSet<>(seen)).putBoolean(KEY_BASELINED, true).apply();

        if (bestNew != null && deliver(ctx, bestNew)) {
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
        }
    }

    static boolean notificationsAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager mgr=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return mgr != null && mgr.areNotificationsEnabled();
    }

    private static boolean deliver(Context ctx, JSONObject deal) {
        if (!notificationsAllowed(ctx)) return false;
        NotificationManager manager=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,"Nieuwe Muntvinder-koopkansen",NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Maximaal één melding per 12 uur bij nieuwe topdeals");
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(ctx,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap=PendingIntent.getActivity(ctx,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        boolean bid=!"FIXED".equals(deal.optString("price_kind"));
        String label=bid?"Nieuw interessant bod":"Nieuwe interessante koopkans";
        String title=deal.optString("title","Proofset");
        if (title.length()>90) title=title.substring(0,87)+"…";
        double low=deal.optDouble("profit_low",Double.NaN);
        String profit=Double.isFinite(low)
                ? String.format(Locale.forLanguageTag("nl-NL"),"Conservatieve indicatie: vanaf €%.2f marge",low)
                : "Bekijk de advertentie voor de berekende indicatie";
        String note=bid?" · Biedprijs, aankoop niet gegarandeerd.":" · Controleer staat en verzendkosten.";
        String body=title+"\n"+profit+note;

        Notification n = new Notification.Builder(ctx,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(label)
            .setContentText(title)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build();
        manager.notify(ALERT_ID,n);
        return true;
    }
}
