package nl.muntvinder.v2;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Enige ingang tot de online feed. Geen ingebouwde geheimen of nepkoopjes. */
final class FeedClient {
    static final String PREF = "muntvinder_v2";
    static final String KEY_URL = "feed_url";
    static final String KEY_JSON = "feed_json";
    static final String KEY_ERROR = "last_error";
    static final String KEY_SYNC = "last_sync";

    private FeedClient() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static String url(Context ctx) {
        String custom = prefs(ctx).getString(KEY_URL, "");
        return custom == null || custom.isEmpty() ? BuildConfig.DEFAULT_FEED_URL : custom;
    }

    static void saveUrl(Context ctx, String url) {
        if (!url.startsWith("https://") || !url.endsWith(".json"))
            throw new IllegalArgumentException("Gebruik een volledige HTTPS-link naar feed.json");
        prefs(ctx).edit().putString(KEY_URL, url.trim()).apply();
    }

    static String cached(Context ctx) { return prefs(ctx).getString(KEY_JSON, ""); }

    static JSONObject fetch(Context ctx) throws Exception {
        String uri = url(ctx);
        if (uri.isEmpty()) throw new Exception("Vul eerst de online feed-URL in bij Instellingen.");
        HttpURLConnection connection = (HttpURLConnection)new URL(uri).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200)
                throw new Exception("HTTP " + connection.getResponseCode() + " bij ophalen van gegevensfeed");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (out.size() + n > 2_000_000) throw new Exception("Feed te groot");
                    out.write(buffer, 0, n);
                }
            }
            JSONObject obj = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            if (!obj.has("deals") || obj.optJSONArray("deals") == null)
                throw new Exception("Onverwacht feedformaat; eerdere aanbiedingen blijven bewaard");
            prefs(ctx).edit().putString(KEY_JSON, obj.toString()).putLong(KEY_SYNC, System.currentTimeMillis())
                    .remove(KEY_ERROR).apply();
            // Ook bij een periodieke achtergrondcontrole nieuwe topdeals melden.
            DealNotifications.onNewFeed(ctx, obj);
            return obj;
        } finally { connection.disconnect(); }
    }

    static void saveError(Context ctx, String error) {
        prefs(ctx).edit().putString(KEY_ERROR, error).apply();
    }
}
