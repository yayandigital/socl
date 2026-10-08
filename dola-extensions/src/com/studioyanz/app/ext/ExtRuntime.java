package com.studioyanz.app.ext;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Menempelkan mesin ekstensi ke WebView Dola (DolaWebActivity):
 * - content_scripts ekstensi aktif disuntikkan sejak document_start (addDocumentStartJavaScript),
 *   dengan cadangan evaluateJavascript bila fitur itu tak didukung WebView perangkat;
 * - file ekstensi dilayani di https://sy-ext.local/&lt;id&gt;/... (chrome.runtime.getURL);
 * - tombol 🧩 di toolbar untuk unggah ZIP/folder dan on/off; perubahan langsung diterapkan (reload).
 */
public final class ExtRuntime {
    private static final String TAG = "SYExt";

    private final ComponentActivity act;
    private final WebView web;
    private final ExtStore store;
    private final String token;
    private final boolean docStart;
    private final List<ScriptHandler> handlers = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<String> scripts = Collections.emptyList();
    private long applied = -1;
    private Button btn;
    private ActivityResultLauncher<String[]> zipPicker;
    private ActivityResultLauncher<Uri> folderPicker;
    private AlertDialog dialog;
    private boolean busy;

    /** Dipanggil dari DolaWebActivity.onCreate tepat sebelum web.loadUrl(...). */
    public static void attach(Activity a, WebView w) {
        try {
            if (a instanceof ComponentActivity && w != null) new ExtRuntime((ComponentActivity) a, w).start();
        } catch (Throwable t) {
            Log.e(TAG, "attach gagal", t);
        }
    }

    private ExtRuntime(ComponentActivity a, WebView w) {
        act = a;
        web = w;
        store = ExtStore.get(a);
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        token = s.toString();
        docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
    }

    private void start() {
        web.addJavascriptInterface(new Bridge(), "SYExtBridge");
        wrapClient();
        applyScripts();
        addToolbarButton();
        String key = "sy_ext_" + System.identityHashCode(this);
        zipPicker = act.getActivityResultRegistry().register(key + "_zip",
                new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) installAsync(uri, false); });
        folderPicker = act.getActivityResultRegistry().register(key + "_dir",
                new ActivityResultContracts.OpenDocumentTree(), uri -> { if (uri != null) installAsync(uri, true); });
        act.getLifecycle().addObserver((LifecycleEventObserver) (owner, ev) -> {
            if (ev == Lifecycle.Event.ON_RESUME) {
                // berubah dari layar Dola di aplikasi utama? terapkan & muat ulang
                if (applied != store.version()) { applyScripts(); web.reload(); }
            } else if (ev == Lifecycle.Event.ON_DESTROY) {
                if (zipPicker != null) zipPicker.unregister();
                if (folderPicker != null) folderPicker.unregister();
                if (dialog != null) dialog.dismiss();
            }
        });
    }

    // ------------------------------------------------------------------ skrip

    private void applyScripts() {
        applied = store.version();
        for (ScriptHandler h : handlers) {
            try { h.remove(); } catch (Throwable ignored) { }
        }
        handlers.clear();
        List<String> next = store.buildScripts(runtimeJs(), token);
        scripts = next.size() > 1 ? next : Collections.<String>emptyList();
        if (docStart) {
            java.util.Set<String> all = Collections.singleton("*");
            for (String js : scripts) {
                try { handlers.add(WebViewCompat.addDocumentStartJavaScript(web, js, all)); }
                catch (Throwable t) { Log.e(TAG, "addDocumentStartJavaScript", t); }
            }
        }
        updateButton();
    }

    /** Cadangan bila DOCUMENT_START_SCRIPT tidak ada: jalankan setelah halaman ter-commit/selesai. */
    private void injectFallback(WebView v) {
        if (docStart) return;
        for (String js : scripts) v.evaluateJavascript(js, null);
    }

    private String runtimeJs() {
        try {
            InputStream in = act.getAssets().open("sy_ext_runtime.js");
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                return bo.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    final class Bridge {
        @JavascriptInterface
        public String get(String t, String id) {
            return token.equals(t) ? store.storageGet(id) : "{}";
        }

        @JavascriptInterface
        public void set(String t, String id, String json) {
            if (token.equals(t)) store.storageSet(id, json);
        }
    }

    // ------------------------------------------------------------------ WebViewClient pembungkus

    private void wrapClient() {
        WebViewClient orig = null;
        try { orig = WebViewCompat.getWebViewClient(web); } catch (Throwable ignored) { }
        // Tanpa akses ke client asli (WebView sangat lama) jangan dibungkus: isi-prompt Dola tetap jalan.
        if (orig == null) return;
        web.setWebViewClient(new Client(orig));
    }

    private WebResourceResponse serve(Uri u) {
        if (u == null || !ExtStore.HOST.equals(u.getHost())) return null;
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-cache");
        List<String> seg = u.getPathSegments();
        File f = null;
        if (seg.size() >= 2) {
            StringBuilder p = new StringBuilder();
            for (int i = 1; i < seg.size(); i++) { if (i > 1) p.append('/'); p.append(seg.get(i)); }
            f = store.resource(seg.get(0), p.toString());
        }
        try {
            if (f == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new java.io.ByteArrayInputStream(new byte[0]));
            return new WebResourceResponse(ExtStore.mimeOf(f.getName()), "utf-8", 200, "OK", h, new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    final class Client extends WebViewClient {
        private final WebViewClient o;

        Client(WebViewClient orig) { o = orig; }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return o != null && o.shouldOverrideUrlLoading(v, r);
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return o != null && o.shouldOverrideUrlLoading(v, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            WebResourceResponse mine = serve(r.getUrl());
            if (mine != null) return mine;
            return o != null ? o.shouldInterceptRequest(v, r) : null;
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap fav) {
            if (o != null) o.onPageStarted(v, url, fav);
        }

        @Override
        public void onPageCommitVisible(WebView v, String url) {
            if (o != null) o.onPageCommitVisible(v, url);
            injectFallback(v);
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            if (o != null) o.onPageFinished(v, url);
            injectFallback(v);
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (o != null) o.onReceivedError(v, r, e);
        }

        @Override
        public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse resp) {
            if (o != null) o.onReceivedHttpError(v, r, resp);
        }

        @Override
        public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
            if (o != null) o.onReceivedSslError(v, h, e);
            else h.cancel();
        }

        @Override
        public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
            if (o != null) o.doUpdateVisitedHistory(v, url, reload);
        }

        @Override
        public boolean shouldOverrideKeyEvent(WebView v, KeyEvent e) {
            return o != null && o.shouldOverrideKeyEvent(v, e);
        }

    }

    // ------------------------------------------------------------------ UI: tombol 🧩 + dialog

    private int dp(int v) { return Math.round(v * act.getResources().getDisplayMetrics().density); }

    private void addToolbarButton() {
        try {
            ViewGroup root = (ViewGroup) web.getParent();
            View bar = root.getChildAt(0);
            if (!(bar instanceof LinearLayout)) return;
            btn = new Button(act);
            btn.setAllCaps(false);
            btn.setContentDescription("Ekstensi");
            btn.setOnClickListener(v -> showManager());
            ((LinearLayout) bar).addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            updateButton();
        } catch (Throwable t) {
            Log.e(TAG, "toolbar", t);
        }
    }

    private void updateButton() {
        if (btn == null) return;
        int n = store.enabledCount();
        btn.setText(n > 0 ? "🧩 " + n : "🧩");
    }

    private void showManager() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        Context c = act;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        JSONArray items = store.list();
        if (items.length() == 0) {
            TextView empty = new TextView(c);
            empty.setText("Belum ada ekstensi.\nUnggah file .zip / .crx / .xpi atau folder yang berisi manifest.json. Ekstensi langsung aktif di Dola.");
            empty.setPadding(0, dp(8), 0, dp(8));
            box.addView(empty);
        }
        for (int i = 0; i < items.length(); i++) {
            final JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            final String id = it.optString("id");
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            LinearLayout txt = new LinearLayout(c);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(c);
            name.setText(it.optString("name"));
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextSize(15);
            TextView sub = new TextView(c);
            StringBuilder s = new StringBuilder();
            if (!it.optString("version").isEmpty()) s.append("v").append(it.optString("version")).append(" · ");
            s.append("MV").append(it.optInt("manifestVersion", 2)).append(" · ")
                    .append(it.optInt("contentScripts")).append(" content script");
            JSONArray w = it.optJSONArray("warnings");
            if (w != null) for (int k = 0; k < w.length(); k++) s.append("\n⚠ ").append(w.optString(k));
            sub.setText(s);
            sub.setTextSize(12);
            sub.setAlpha(0.75f);
            txt.addView(name);
            txt.addView(sub);
            row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Switch sw = new Switch(c);
            sw.setChecked(it.optBoolean("enabled", true));
            sw.setEnabled(!it.optBoolean("broken"));
            sw.setOnCheckedChangeListener((b, on) -> store.setEnabled(id, on));
            row.addView(sw);

            Button del = new Button(c);
            del.setText("🗑");
            del.setContentDescription("Hapus");
            del.setOnClickListener(v -> new AlertDialog.Builder(act)
                    .setTitle("Hapus ekstensi?")
                    .setMessage(it.optString("name"))
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Hapus", (d, wch) -> { store.remove(id); showManager(); })
                    .show());
            row.addView(del, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(row);
        }
        TextView note = new TextView(c);
        note.setText("Yang jalan: content_scripts (JS/CSS), chrome.storage, chrome.i18n, chrome.runtime.getURL. "
                + "Background, popup, dan izin tab tidak tersedia di WebView.");
        note.setTextSize(11);
        note.setAlpha(0.6f);
        note.setPadding(0, dp(10), 0, 0);
        box.addView(note);

        ScrollView sv = new ScrollView(c);
        sv.addView(box);
        dialog = new AlertDialog.Builder(act)
                .setTitle("Ekstensi Dola")
                .setView(sv)
                .setPositiveButton("Unggah ZIP", (d, w) -> pickZip())
                .setNeutralButton("Unggah folder", (d, w) -> pickFolder())
                .setNegativeButton("Tutup", null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (applied != store.version()) { applyScripts(); web.reload(); }
        });
        dialog.show();
    }

    private void pickZip() {
        if (busy) return;
        try { zipPicker.launch(new String[]{"application/zip", "application/x-zip-compressed", "application/x-chrome-extension", "application/x-xpinstall", "application/octet-stream", "*/*"}); }
        catch (Exception e) { toast("Pemilih file tidak tersedia"); }
    }

    private void pickFolder() {
        if (busy) return;
        try { folderPicker.launch(null); }
        catch (Exception e) { toast("Pemilih folder tidak tersedia"); }
    }

    private void installAsync(Uri uri, boolean folder) {
        busy = true;
        toast("Memasang ekstensi…");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                JSONObject r;
                if (folder) {
                    r = store.installTree(act, uri);
                } else {
                    InputStream in = act.getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("File tidak bisa dibuka");
                    try { r = store.installArchive(in); } finally { in.close(); }
                }
                msg = "✓ " + r.optString("name") + " aktif";
                ok = true;
            } catch (Exception e) {
                msg = "Gagal: " + e.getMessage();
            }
            final String m = msg;
            final boolean success = ok;
            ui.post(() -> {
                busy = false;
                toast(m);
                if (success && !act.isFinishing()) {
                    applyScripts();
                    web.reload();
                    showManager();
                }
            });
        }, "sy-ext-install").start();
    }

    private void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_LONG).show(); }
}
