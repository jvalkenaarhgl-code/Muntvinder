package nl.muntvinder.v2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloadt publieke advertentiefoto's buiten de UI-thread, met een beperkte geheugencache. */
final class PhotoLoader {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getByteCount(); }
    };
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private PhotoLoader() {}

    static void load(ImageView target, String uri) {
        target.setTag(uri);
        target.setImageDrawable(null);
        if (uri == null || !uri.startsWith("https://")) return;
        Bitmap cached = CACHE.get(uri);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        WeakReference<ImageView> viewRef = new WeakReference<>(target);
        WORKERS.execute(() -> {
            Bitmap bitmap = download(uri);
            if (bitmap != null) CACHE.put(uri, bitmap);
            MAIN.post(() -> {
                ImageView view = viewRef.get();
                if (view != null && uri.equals(view.getTag())) {
                    if (bitmap != null) view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static Bitmap download(String address) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            if (!"https".equalsIgnoreCase(url.getProtocol())) return null;
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(9000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Muntvinder/2.4");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return null;
            String mime = connection.getContentType();
            if (mime != null && !mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return null;
            if (connection.getContentLength() > MAX_BYTES) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[8192];
                int got;
                while ((got = input.read(chunk)) != -1) {
                    if (out.size() + got > MAX_BYTES) return null;
                    out.write(chunk, 0, got);
                }
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            int sample = 1;
            while (opts.outWidth / sample > 1000 || opts.outHeight / sample > 1000) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Exception ignore) {
            // Advertenties verdwijnen, image-CDN kan onbereikbaar zijn: app blijft werken.
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
