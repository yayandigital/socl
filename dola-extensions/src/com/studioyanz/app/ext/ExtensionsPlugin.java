package com.studioyanz.app.ext;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Plugin "SYExt" untuk layar Dola di aplikasi utama: daftar, unggah ZIP/folder, on/off, hapus. */
@CapacitorPlugin(name = "SYExt")
public class ExtensionsPlugin extends Plugin {

    private ExtStore store() { return ExtStore.get(getContext()); }

    @PluginMethod
    public void list(PluginCall call) {
        call.resolve(listResult());
    }

    private JSObject listResult() {
        JSObject r = new JSObject();
        try {
            r.put("items", new JSArray(store().list().toString()));
        } catch (Exception e) {
            r.put("items", new JSArray());
        }
        r.put("documentStart", WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        return r;
    }

    @PluginMethod
    public void setEnabled(PluginCall call) {
        String id = call.getString("id", "");
        Boolean on = call.getBoolean("enabled", true);
        if (!store().setEnabled(id, on != null && on)) { call.reject("Ekstensi tidak ditemukan"); return; }
        call.resolve(listResult());
    }

    @PluginMethod
    public void remove(PluginCall call) {
        if (!store().remove(call.getString("id", ""))) { call.reject("Ekstensi tidak ditemukan"); return; }
        call.resolve(listResult());
    }

    @PluginMethod
    public void pickZip(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(call, i, "onZipPicked");
    }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(call, i, "onFolderPicked");
    }

    @ActivityCallback
    private void onZipPicked(PluginCall call, ActivityResult res) {
        handlePick(call, res, false);
    }

    @ActivityCallback
    private void onFolderPicked(PluginCall call, ActivityResult res) {
        handlePick(call, res, true);
    }

    private void handlePick(final PluginCall call, ActivityResult res, final boolean folder) {
        if (call == null) return;
        final Uri uri = res.getResultCode() == Activity.RESULT_OK && res.getData() != null ? res.getData().getData() : null;
        if (uri == null) {
            call.reject("Dibatalkan", "CANCELLED");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject r;
                if (folder) {
                    r = store().installTree(getContext(), uri);
                } else {
                    InputStream in = getContext().getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("File tidak bisa dibuka");
                    try { r = store().installArchive(in); } finally { in.close(); }
                }
                JSObject out = listResult();
                out.put("installed", JSObject.fromJSONObject(r));
                call.resolve(out);
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? "Gagal memasang ekstensi" : e.getMessage());
            } finally {
                try { getBridge().releaseCall(call); } catch (Throwable ignored) { }
            }
        }, "sy-ext-install").start();
    }
}
