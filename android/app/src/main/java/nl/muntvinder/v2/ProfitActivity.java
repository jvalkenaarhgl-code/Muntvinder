package nl.muntvinder.v2;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Proofset Rendement as a second screen inside Muntvinder.
 * A different WebView origin from the separate APK: import an exported JSON backup.
 */
public final class ProfitActivity extends Activity {
    private static final int SAVE_FILE = 4701, OPEN_FILE = 4702;
    private WebView webView;
    private String pendingExport;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(246,247,245));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(246,247,245));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"file".equalsIgnoreCase(request.getUrl().getScheme());
            }
            @Override public void onPageFinished(WebView view, String url) {
                String payload = getIntent().getStringExtra("deal_payload");
                if (payload != null && !payload.isEmpty()) {
                    view.evaluateJavascript("window.combiPrefill && window.combiPrefill(" +
                        JSONObject.quote(payload) + ");", null);
                    getIntent().removeExtra("deal_payload");
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new FileBridge(), "NativeBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private final class FileBridge {
        @JavascriptInterface public void openScanner() {
            runOnUiThread(() -> finish());
        }
        @JavascriptInterface public void saveFile(String filename, String mimeType, String content) {
            runOnUiThread(() -> {
                pendingExport = content;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mimeType == null ? "application/json" : mimeType.split(";")[0]);
                intent.putExtra(Intent.EXTRA_TITLE, filename);
                startActivityForResult(intent, SAVE_FILE);
            });
        }
        @JavascriptInterface public void openFile() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, OPEN_FILE);
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExport = null; return;
        }
        try {
            Uri uri = data.getData();
            if (requestCode == SAVE_FILE && pendingExport != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new java.io.IOException("Cannot write");
                    out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                }
                jsToast("Back-up opgeslagen.");
            } else if (requestCode == OPEN_FILE) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("Cannot read");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (out.size() + n > 4_000_000) throw new java.io.IOException("File too large");
                        out.write(buffer, 0, n);
                    }
                    String content = out.toString(StandardCharsets.UTF_8.name());
                    webView.evaluateJavascript("window.receiveBackup(" + JSONObject.quote(content) + ")", null);
                }
            }
        } catch (Exception ex) {
            jsToast("Importeren of exporteren mislukt.");
        } finally { pendingExport = null; }
    }

    private void jsToast(String msg) {
        if (webView == null) return;
        webView.evaluateJavascript("(function(){var t=document.getElementById('toast');if(t){t.textContent=" +
            JSONObject.quote(msg) + ";t.classList.add('show');setTimeout(function(){t.classList.remove('show')},3000)}})()", null);
    }

    @Override public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript("window.appBack ? window.appBack() : false", result -> {
            if (!"true".equals(result)) ProfitActivity.super.onBackPressed();
        });
    }

    @Override protected void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
