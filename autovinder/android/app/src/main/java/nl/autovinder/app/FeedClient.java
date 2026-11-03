package nl.autovinder.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class FeedClient {
    static final String PREF = "autovinder_v1";
    static final String KEY_JSON = "feed_json";
    static final String KEY_ERROR = "last_error";
    private FeedClient() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    static String url() {return BuildConfig.DEFAULT_FEED_URL;}
    static String cached(Context ctx) {return prefs(ctx).getString(KEY_JSON,"");}
    static JSONObject feed(Context ctx) {
        try {return new JSONObject(cached(ctx));}
        catch (Exception e){return new JSONObject();}
    }
    static JSONObject fetch(Context ctx) throws Exception {
        if (url().isEmpty())throw new Exception("Scannerfeed ontbreekt");
        HttpURLConnection conn=(HttpURLConnection)new URL(url()).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(19000);
        conn.setRequestProperty("Accept","application/json");
        conn.setRequestProperty("Cache-Control","no-cache");
        try {
            if(conn.getResponseCode()!=200)throw new Exception("HTTP "+conn.getResponseCode());
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream stream=conn.getInputStream()){
                byte[] block=new byte[8192];int n;
                while((n=stream.read(block))!=-1){
                    if(buffer.size()+n>8_000_000)throw new Exception("Feed te groot");
                    buffer.write(block,0,n);
                }
            }
            JSONObject json=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            if(json.optJSONArray("deals")==null)
                throw new Exception("Onjuist feedformaat, eerdere gegevens blijven behouden");
            prefs(ctx).edit().putString(KEY_JSON,json.toString())
                .putLong("last_sync",System.currentTimeMillis()).remove(KEY_ERROR).apply();
            DealNotifications.onNewFeed(ctx,json);
            return json;
        } finally {conn.disconnect();}
    }
    static void saveError(Context ctx,String err) {
        prefs(ctx).edit().putString(KEY_ERROR,err).apply();
    }
}
