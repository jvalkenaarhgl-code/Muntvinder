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
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lokale meldingen op basis van nieuwe advertenties uit de periodiek opgehaalde feed.
 * Een sterke webshopkorting wordt nadrukkelijk NIET als doorverkoopwinst gepresenteerd.
 * Dit is geen instant serverpush; Android kan de periodieke check uitstellen.
 */
final class DealNotifications {
    static final String KEY_ENABLED = "deal_notifications_enabled";
    static final String KEY_BIDS = "deal_notifications_bids";
    private static final String KEY_SEEN = "deal_notifications_seen";
    private static final String KEY_BASELINED = "deal_notifications_baselined";
    private static final String KEY_LAST = "deal_notifications_last";
    private static final String KEY_LAST_CHECK = "deal_notifications_checked";
    private static final String KEY_OUTCOME = "deal_notifications_outcome";
    private static final String KEY_PENDING = "deal_notifications_pending";
    private static final String KEY_PENDING_AT = "deal_notifications_pending_at";
    private static final String CHANNEL_ID = "muntvinder_koopkansen";
    private static final long COOLDOWN_MS = 12L * 60 * 60 * 1000;
    private static final long MAX_PENDING_MS = 24L * 60 * 60 * 1000;
    private static final int ALERT_ID = 43104;
    private static final int TEST_ID = 43105;

    private DealNotifications() {}

    static boolean enabled(Context ctx) {
        return FeedClient.prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    static boolean bidsEnabled(Context ctx) {
        return FeedClient.prefs(ctx).getBoolean(KEY_BIDS, true);
    }

    private static NotificationManager manager(Context ctx) {
        return (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(Context ctx) {
        NotificationManager mgr = manager(ctx);
        if (mgr == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Nieuwe Muntvinder-koopkansen", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Alleen nieuwe interessante proofsetaanbiedingen, maximaal één per 12 uur");
            mgr.createNotificationChannel(channel);
        }
    }

    static boolean notificationsAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager mgr = manager(ctx);
        if (mgr == null || !mgr.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = mgr.getNotificationChannel(CHANNEL_ID);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
                return false;
        }
        return true;
    }

    private static boolean fixedPrice(JSONObject deal) {
        return "FIXED".equals(deal.optString("price_kind",""));
    }

    /**
     * De serverfeed kan nul alert_candidates bevatten ondanks grote prijsverschillen:
     * dan werkte de vorige APK nooit. Herken voorwaardelijk een duidelijke,
     * vaste-vraagprijs korting, zonder die als bewezen winst te presenteren.
     */
    private static boolean strongWebshopGap(JSONObject deal) {
        if (!"Marktplaats".equals(deal.optString("source","")) || !fixedPrice(deal)) return false;
        if (deal.has("profit_low") && !deal.isNull("profit_low")) {
            // Een bekende negatieve conservatieve marge is géén sterk signaal.
            return false;
        }
        double buy = deal.optDouble("purchase_total", 0);
        double shop = deal.optDouble("shop_price", 0);
        if (!Double.isFinite(buy) || !Double.isFinite(shop) || buy<=0 || shop<=0) return false;
        return shop - buy >= 15 && buy <= 0.65 * shop;
    }

    private static boolean worthNotifying(JSONObject deal, boolean allowBids) {
        int rank = deal.optInt("deal_position", 99);
        if (rank < 1 || rank > 3) return false;
        if (strongWebshopGap(deal)) return true;
        if (!deal.optBoolean("alert_candidate", false)) return false;
        if (!Double.isFinite(deal.optDouble("profit_low", Double.NaN)) ||
            deal.optDouble("profit_low", 0) <= 0) return false;
        String kind = deal.optString("price_kind", "");
        return fixedPrice(deal) || (allowBids && ("MIN_BID".equals(kind) || "FAST_BID".equals(kind)));
    }

    private static void record(SharedPreferences prefs, String outcome) {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).putString(KEY_OUTCOME, outcome).apply();
    }

    /**
     * Bij de allereerste download worden bestaande resultaten niet als NIEUW gemeld.
     * Als een nieuw signaal tijdens de afkoeltijd binnenkomt, bewaren we maximaal
     * één pending ID en leveren dat later af zolang het nog in de actuele top-3 staat.
     */
    static void onNewFeed(Context ctx, JSONObject feed) {
        JSONArray listings = feed.optJSONArray("deals");
        SharedPreferences prefs = FeedClient.prefs(ctx);
        if (listings == null || listings.length() == 0) {
            record(prefs, "Geen bruikbare advertenties in de laatste feed");
            return;
        }
        if (feed.optBoolean("stale", false)) {
            record(prefs, "Bronscan is verouderd — meldingen overgeslagen");
            return;
        }

        Set<String> stored = prefs.getStringSet(KEY_SEEN, Collections.emptySet());
        LinkedHashSet<String> seen = new LinkedHashSet<>(stored == null ? Collections.emptySet() : stored);
        boolean baseline = prefs.getBoolean(KEY_BASELINED, false);
        String pending = prefs.getString(KEY_PENDING, "");
        long pendingAt = prefs.getLong(KEY_PENDING_AT, 0);
        if (System.currentTimeMillis() - pendingAt > MAX_PENDING_MS) pending = "";
        JSONObject candidate = null;
        boolean pendingIsCurrent = false;

        for (int i=0; i<listings.length(); i++) {
            JSONObject deal = listings.optJSONObject(i);
            if (deal == null) continue;
            String id = deal.optString("id", "");
            if (id.isEmpty()) continue;
            boolean isNew = !seen.contains(id);
            if (baseline && worthNotifying(deal, bidsEnabled(ctx)) &&
                    (isNew || id.equals(pending))) {
                if (candidate == null) candidate = deal;
                if (id.equals(pending)) pendingIsCurrent = true;
            }
            seen.add(id);
        }
        while (seen.size() > 1000) seen.remove(seen.iterator().next());
        SharedPreferences.Editor editor = prefs.edit()
            .putStringSet(KEY_SEEN, new HashSet<>(seen))
            .putBoolean(KEY_BASELINED, true);
        if (!pendingIsCurrent) {
            editor.remove(KEY_PENDING).remove(KEY_PENDING_AT);
        }
        editor.apply();

        if (!baseline) {
            record(prefs, "Eerste scan verwerkt — oude advertenties niet gemeld");
            return;
        }
        if (candidate == null) {
            record(prefs, "Geen nieuwe meldingswaardige top-3 aanbiedingen ("+listings.length()+" gecontroleerd)");
            return;
        }
        String id = candidate.optString("id","");
        if (!enabled(ctx)) {
            record(prefs, "Nieuwe kans gezien, maar dealmeldingen staan uit in de app");
            return;
        }
        ensureChannel(ctx);
        if (!notificationsAllowed(ctx)) {
            record(prefs, "Nieuwe kans gezien, maar Android blokkeert de meldingen");
            return;
        }
        long last = prefs.getLong(KEY_LAST,0);
        if (System.currentTimeMillis() - last < COOLDOWN_MS) {
            prefs.edit().putString(KEY_PENDING,id).putLong(KEY_PENDING_AT,System.currentTimeMillis()).apply();
            record(prefs, "Interessante deal gevonden; maximaal één melding per 12 uur");
            return;
        }
        if (deliver(ctx,candidate)) {
            prefs.edit().putLong(KEY_LAST,System.currentTimeMillis())
                .remove(KEY_PENDING).remove(KEY_PENDING_AT).apply();
            record(prefs, "Nieuwe koopkans als melding aangeboden aan Android");
        } else {
            record(prefs, "Een melding kon niet worden aangeboden aan Android");
        }
    }

    /** Handmatige diagnostische melding om permissies en het kanaal te controleren. */
    static String sendTest(Context ctx) {
        ensureChannel(ctx);
        if (!notificationsAllowed(ctx))
            return "Android blokkeert meldingen. Controleer appmeldingen en het kanaal Nieuwe Muntvinder-koopkansen.";
        NotificationManager mgr = manager(ctx);
        if (mgr == null) return "Android-meldingsdienst niet beschikbaar";
        try {
            Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Muntvinder testmelding")
                .setContentText("Meldingen zijn ingeschakeld. Echte deals volgen alleen bij nieuwe koopkansen.")
                .setStyle(new Notification.BigTextStyle().bigText(
                    "Dit is een test, geen gevonden deal. De scanner kijkt op de achtergrond naar nieuwe aanbiedingen."))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build();
            mgr.notify(TEST_ID,n);
            return "Testmelding naar Android verstuurd. Controleer je meldingenpaneel.";
        } catch(Exception ex) {
            return "Testmelding mislukt: "+ex.getClass().getSimpleName();
        }
    }

    private static PendingIntent openApp(Context ctx) {
        Intent open = new Intent(ctx,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean deliver(Context ctx, JSONObject deal) {
        ensureChannel(ctx);
        if (!notificationsAllowed(ctx)) return false;
        NotificationManager mgr = manager(ctx);
        if (mgr == null) return false;
        try {
            String title = deal.optString("title","Proofset");
            if (title.length()>85) title=title.substring(0,82)+"…";
            String label;
            String details;
            if (strongWebshopGap(deal)) {
                label = "Nieuw opvallend prijsverschil";
                double asking=deal.optDouble("purchase_total",0);
                double shop=deal.optDouble("shop_price",0);
                details=String.format(Locale.forLanguageTag("nl-NL"),
                    "Vraagprijs incl. bekende kosten €%.2f, webshop €%.2f. Geen bewezen doorverkoopwinst.",
                    asking, shop);
            } else {
                boolean bid = !fixedPrice(deal);
                label = bid ? "Interessant bod op een proofset" : "Nieuwe interessante koopkans";
                double low = deal.optDouble("profit_low", Double.NaN);
                details = Double.isFinite(low)
                    ? String.format(Locale.forLanguageTag("nl-NL"),
                        "Conservatieve geschatte marge €%.2f excl. verkoopkosten; controleer de advertentie.",low)
                    : "Bekijk de indicatie en controleer zelf de advertentie.";
                if (bid) details += " De biedprijs is niet definitief.";
            }
            Notification n = new Notification.Builder(ctx,CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(label)
                .setContentText(title)
                .setStyle(new Notification.BigTextStyle().bigText(title+"\n"+details))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .build();
            mgr.notify(ALERT_ID,n);
            return true;
        }catch(Exception ex){return false;}
    }

    static String diagnostic(Context ctx) {
        SharedPreferences prefs=FeedClient.prefs(ctx);
        String permission = !enabled(ctx) ? "Uit in Muntvinder" :
            notificationsAllowed(ctx) ? "Android-meldingen toegestaan" : "Android-meldingen geblokkeerd";
        SimpleDateFormat f=new SimpleDateFormat("dd-MM HH:mm",new Locale("nl","NL"));
        long sync=prefs.getLong(FeedClient.KEY_SYNC,0);
        long checked=prefs.getLong(KEY_LAST_CHECK,0);
        long notified=prefs.getLong(KEY_LAST,0);
        String sourceError=prefs.getString(FeedClient.KEY_ERROR,"");
        return permission+"\n"+
            "Laatste feed-download: "+(sync>0?f.format(new Date(sync)):"nog niet gelukt")+"\n"+
            "Laatste meldingscontrole: "+(checked>0?f.format(new Date(checked)):"nog niet uitgevoerd")+"\n"+
            "Laatste echte melding: "+(notified>0?f.format(new Date(notified)):"nog geen")+"\n"+
            "Resultaat: "+prefs.getString(KEY_OUTCOME,"geen controlegegevens")+
            (sourceError!=null&&!sourceError.isEmpty()?"\nDownloadfout: "+sourceError:"");
    }
}
