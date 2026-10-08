package com.studioyanz.app.ext;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Penyimpanan ekstensi Dola: unggah ZIP/CRX/XPI atau folder, baca manifest.json,
 * simpan di filesDir/sy_ext/&lt;id&gt;/, dan status on/off di index.json.
 */
public final class ExtStore {
    public static final String HOST = "sy-ext.local";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_FILES = 5000;
    private static final long MAX_TOTAL = 200L * 1024 * 1024;
    private static final long MAX_INLINE = 8L * 1024 * 1024;

    private static ExtStore instance;

    private final File root;
    private final File indexFile;
    private JSONArray index;
    private volatile long version = System.nanoTime();

    public static synchronized ExtStore get(Context ctx) {
        if (instance == null) instance = new ExtStore(ctx.getApplicationContext());
        return instance;
    }

    private ExtStore(Context ctx) {
        root = new File(ctx.getFilesDir(), "sy_ext");
        if (!root.exists()) root.mkdirs();
        indexFile = new File(root, "index.json");
        index = readIndex();
    }

    public long version() { return version; }

    private JSONArray readIndex() {
        try {
            if (indexFile.exists()) return new JSONArray(readText(indexFile));
        } catch (Exception ignored) {
        }
        return new JSONArray();
    }

    private void saveIndex() {
        try {
            writeText(indexFile, index.toString());
        } catch (IOException ignored) {
        }
        version = System.nanoTime();
    }

    private JSONObject entry(String id) {
        for (int i = 0; i < index.length(); i++) {
            JSONObject o = index.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    // ------------------------------------------------------------------ daftar / on-off / hapus

    public synchronized JSONArray list() {
        JSONArray out = new JSONArray();
        for (int i = 0; i < index.length(); i++) {
            JSONObject e = index.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id");
            File dir = new File(root, id);
            try {
                JSONObject m = readManifest(dir);
                JSONObject msgs = loadMessages(dir, m);
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("name", msg(m.optString("name", id), msgs));
                o.put("version", m.optString("version", ""));
                o.put("description", msg(m.optString("description", ""), msgs));
                o.put("manifestVersion", m.optInt("manifest_version", 2));
                o.put("enabled", e.optBoolean("enabled", true));
                o.put("installedAt", e.optLong("installedAt"));
                JSONArray cs = m.optJSONArray("content_scripts");
                o.put("contentScripts", cs == null ? 0 : cs.length());
                o.put("warnings", warnings(m));
                String icon = iconDataUrl(dir, m);
                if (icon != null) o.put("icon", icon);
                out.put(o);
            } catch (Exception ex) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("name", id);
                    o.put("enabled", false);
                    o.put("broken", true);
                    o.put("warnings", new JSONArray().put("Manifest rusak: " + ex.getMessage()));
                    out.put(o);
                } catch (JSONException ignored) {
                }
            }
        }
        return out;
    }

    public synchronized int enabledCount() {
        int n = 0;
        for (int i = 0; i < index.length(); i++) {
            JSONObject e = index.optJSONObject(i);
            if (e != null && e.optBoolean("enabled", true)) n++;
        }
        return n;
    }

    public synchronized boolean setEnabled(String id, boolean on) {
        JSONObject e = entry(id);
        if (e == null) return false;
        try {
            e.put("enabled", on);
        } catch (JSONException ignored) {
        }
        saveIndex();
        return true;
    }

    public synchronized boolean remove(String id) {
        JSONArray next = new JSONArray();
        boolean found = false;
        for (int i = 0; i < index.length(); i++) {
            JSONObject e = index.optJSONObject(i);
            if (e == null) continue;
            if (id.equals(e.optString("id"))) found = true;
            else next.put(e);
        }
        if (!found) return false;
        index = next;
        deleteTree(new File(root, id));
        new File(root, id + ".storage.json").delete();
        saveIndex();
        return true;
    }

    private static JSONArray warnings(JSONObject m) {
        JSONArray w = new JSONArray();
        JSONArray cs = m.optJSONArray("content_scripts");
        if (cs == null || cs.length() == 0) w.put("Tidak ada content_scripts — ekstensi ini tidak menyuntikkan apa pun ke halaman.");
        JSONObject bg = m.optJSONObject("background");
        if (bg != null) w.put("Background/service worker tidak didukung di WebView.");
        if (m.has("action") || m.has("browser_action") || m.has("page_action")) w.put("Popup toolbar tidak didukung.");
        return w;
    }

    // ------------------------------------------------------------------ pemasangan

    /** Pasang dari ZIP / CRX / XPI. Ekstensi yang sama (nama/ID sama) akan ditimpa dan langsung aktif. */
    public JSONObject installArchive(InputStream raw) throws Exception {
        File stage = newStage();
        File tmpZip = new File(root, ".upload-" + System.nanoTime() + ".zip");
        try {
            copyArchiveStrippingCrx(raw, tmpZip);
            ZipFile zf = new ZipFile(tmpZip);
            try {
                int files = 0;
                long total = 0;
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String rel = safeRel(ze.getName());
                    if (rel == null) continue;
                    File out = new File(stage, rel);
                    if (ze.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }
                    if (++files > MAX_FILES) throw new IOException("Terlalu banyak file dalam arsip");
                    out.getParentFile().mkdirs();
                    InputStream in = zf.getInputStream(ze);
                    try {
                        total += copyLimited(in, out, MAX_TOTAL - total);
                    } finally {
                        in.close();
                    }
                }
            } finally {
                zf.close();
            }
            return finishInstall(stage);
        } finally {
            tmpZip.delete();
            deleteTree(stage);
        }
    }

    /** Pasang dari folder (Storage Access Framework, ACTION_OPEN_DOCUMENT_TREE). */
    public JSONObject installTree(Context ctx, Uri tree) throws Exception {
        File stage = newStage();
        try {
            ContentResolver cr = ctx.getContentResolver();
            String docId = DocumentsContract.getTreeDocumentId(tree);
            long[] budget = {MAX_TOTAL, MAX_FILES};
            copyTree(cr, tree, docId, stage, budget, 0);
            return finishInstall(stage);
        } finally {
            deleteTree(stage);
        }
    }

    private void copyTree(ContentResolver cr, Uri tree, String parentDocId, File dest, long[] budget, int depth) throws IOException {
        if (depth > 24) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
        Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null || name.isEmpty() || name.equals(".git") || name.contains("/") || name.equals("..")) continue;
                File out = new File(dest, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    out.mkdirs();
                    copyTree(cr, tree, id, out, budget, depth + 1);
                } else {
                    if (--budget[1] < 0) throw new IOException("Terlalu banyak file dalam folder");
                    InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    if (in == null) continue;
                    try {
                        budget[0] -= copyLimited(in, out, budget[0]);
                    } finally {
                        in.close();
                    }
                }
            }
        } finally {
            c.close();
        }
    }

    private JSONObject finishInstall(File stage) throws Exception {
        File base = findManifestDir(stage, 0);
        if (base == null) throw new IOException("manifest.json tidak ditemukan");
        JSONObject m = readManifest(base);
        if (m.optString("name", "").trim().isEmpty()) throw new IOException("manifest.json tidak punya \"name\"");
        JSONObject msgs = loadMessages(base, m);
        String name = msg(m.optString("name"), msgs);
        String identity = identityOf(m, name);
        String id = makeId(name, identity);
        synchronized (this) {
            File dest = new File(root, id);
            deleteTree(dest);
            if (!base.renameTo(dest)) {
                copyDir(base, dest);
            }
            JSONObject e = entry(id);
            if (e == null) {
                e = new JSONObject();
                e.put("id", id);
                e.put("installedAt", System.currentTimeMillis());
                index.put(e);
            }
            e.put("enabled", true);
            e.put("updatedAt", System.currentTimeMillis());
            saveIndex();
        }
        JSONObject r = new JSONObject();
        r.put("id", id);
        r.put("name", name);
        r.put("version", m.optString("version", ""));
        r.put("warnings", warnings(m));
        return r;
    }

    private static String identityOf(JSONObject m, String name) {
        JSONObject bss = m.optJSONObject("browser_specific_settings");
        if (bss == null) bss = m.optJSONObject("applications");
        if (bss != null && bss.optJSONObject("gecko") != null) {
            String gid = bss.optJSONObject("gecko").optString("id", "");
            if (!gid.isEmpty()) return gid;
        }
        if (!m.optString("key", "").isEmpty()) return m.optString("key");
        return name;
    }

    private static String makeId(String name, String identity) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 24) slug = slug.substring(0, 24);
        if (slug.isEmpty()) slug = "ext";
        CRC32 crc = new CRC32();
        crc.update(identity.getBytes(UTF8));
        return slug + "-" + Long.toHexString(crc.getValue());
    }

    private static File findManifestDir(File dir, int depth) {
        if (new File(dir, "manifest.json").isFile()) return dir;
        if (depth >= 4) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory() && !k.getName().equals("__MACOSX")) {
                File f = findManifestDir(k, depth + 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    private File newStage() {
        File s = new File(root, ".stage-" + System.nanoTime());
        s.mkdirs();
        return s;
    }

    /** CRX2/CRX3 = header + ZIP. Header dibuang supaya bisa dibaca sebagai ZIP biasa. */
    private static void copyArchiveStrippingCrx(InputStream raw, File out) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw, 64 * 1024);
        in.mark(16);
        byte[] h = new byte[16];
        int n = readFully(in, h, 16);
        in.reset();
        if (n >= 12 && h[0] == 'C' && h[1] == 'r' && h[2] == '2' && h[3] == '4') {
            int ver = le32(h, 4);
            long skip;
            if (ver == 3) skip = 12L + (le32(h, 8) & 0xffffffffL);
            else skip = 16L + (le32(h, 8) & 0xffffffffL) + (le32(h, 12) & 0xffffffffL);
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) throw new IOException("Header CRX rusak");
                skip -= s;
            }
        }
        copyLimited(in, out, MAX_TOTAL);
    }

    // ------------------------------------------------------------------ manifest & i18n

    static JSONObject readManifest(File dir) throws IOException, JSONException {
        return new JSONObject(stripJsonComments(readText(new File(dir, "manifest.json"))));
    }

    /** Chrome mengizinkan komentar di manifest.json. */
    static String stripJsonComments(String s) {
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
        StringBuilder b = new StringBuilder(s.length());
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                b.append(c);
                if (c == '\\' && i + 1 < s.length()) b.append(s.charAt(++i));
                else if (c == '"') str = false;
            } else if (c == '"') {
                str = true;
                b.append(c);
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                b.append('\n');
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? s.length() : e + 1;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** messages.json untuk bahasa perangkat, jatuh ke default_locale lalu "en". Kunci dijadikan huruf kecil. */
    static JSONObject loadMessages(File dir, JSONObject m) {
        List<String> cands = new ArrayList<>();
        Locale l = Locale.getDefault();
        String lang = l.getLanguage();
        if ("in".equals(lang)) lang = "id";
        if (!l.getCountry().isEmpty()) cands.add(lang + "_" + l.getCountry());
        cands.add(lang);
        String def = m.optString("default_locale", "");
        if (!def.isEmpty()) cands.add(def);
        cands.add("en");
        cands.add("en_US");
        JSONObject out = new JSONObject();
        for (int i = cands.size() - 1; i >= 0; i--) {
            File f = new File(dir, "_locales/" + cands.get(i) + "/messages.json");
            if (!f.isFile()) continue;
            try {
                JSONObject o = new JSONObject(stripJsonComments(readText(f)));
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    out.put(k.toLowerCase(Locale.ROOT), o.get(k));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static String msg(String s, JSONObject msgs) {
        if (s != null && s.startsWith("__MSG_") && s.endsWith("__")) {
            String k = s.substring(6, s.length() - 2).toLowerCase(Locale.ROOT);
            JSONObject o = msgs.optJSONObject(k);
            if (o != null) return o.optString("message", s);
        }
        return s;
    }

    private static String iconDataUrl(File dir, JSONObject m) {
        String path = null;
        JSONObject icons = m.optJSONObject("icons");
        if (icons != null) {
            int best = -1;
            Iterator<String> it = icons.keys();
            while (it.hasNext()) {
                String k = it.next();
                int sz;
                try {
                    sz = Integer.parseInt(k);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (best < 0 || Math.abs(sz - 48) < Math.abs(best - 48)) {
                    best = sz;
                    path = icons.optString(k);
                }
            }
        }
        if (path == null) return null;
        File f = resolveIn(dir, path);
        if (f == null || !f.isFile() || f.length() > 128 * 1024) return null;
        try {
            return "data:" + mimeOf(f.getName()) + ";base64," + Base64.encodeToString(readBytes(f), Base64.NO_WRAP);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ penyuntikan

    /**
     * Daftar skrip JS untuk WebView Dola: [0] = runtime inti, lalu satu skrip per ekstensi aktif
     * (definisi ekstensi + grup content_scripts-nya). Tiap skrip berdiri sendiri, jadi error sintaks
     * di satu ekstensi tidak mematikan ekstensi lain.
     */
    public synchronized List<String> buildScripts(String runtimeJs, String token) {
        List<String> out = new ArrayList<>();
        out.add(runtimeJs.replace("__SY_TOKEN__", token));
        String rtRef = "window['__syx_" + token + "']";
        for (int i = 0; i < index.length(); i++) {
            JSONObject e = index.optJSONObject(i);
            if (e == null || !e.optBoolean("enabled", true)) continue;
            String id = e.optString("id");
            File dir = new File(root, id);
            try {
                JSONObject m = readManifest(dir);
                JSONArray cs = m.optJSONArray("content_scripts");
                if (cs == null || cs.length() == 0) continue;
                JSONObject msgs = loadMessages(dir, m);
                JSONObject def = new JSONObject();
                def.put("id", id);
                def.put("name", msg(m.optString("name", id), msgs));
                def.put("base", "https://" + HOST + "/" + id + "/");
                def.put("manifest", m);
                def.put("messages", msgs);
                StringBuilder js = new StringBuilder();
                js.append("(function(){var R=").append(rtRef).append(";if(!R)return;R.ext(")
                        .append(jsonForScript(def.toString())).append(");\n");
                for (int g = 0; g < cs.length(); g++) {
                    JSONObject grp = cs.optJSONObject(g);
                    if (grp == null) continue;
                    JSONObject meta = new JSONObject();
                    meta.put("ext", id);
                    meta.put("grp", g);
                    copyKey(grp, meta, "matches");
                    copyKey(grp, meta, "exclude_matches");
                    copyKey(grp, meta, "include_globs");
                    copyKey(grp, meta, "exclude_globs");
                    meta.put("run_at", grp.optString("run_at", "document_idle"));
                    meta.put("all_frames", grp.optBoolean("all_frames", false));
                    JSONArray css = new JSONArray();
                    JSONArray cssList = grp.optJSONArray("css");
                    if (cssList != null) {
                        for (int k = 0; k < cssList.length(); k++) {
                            File f = resolveIn(dir, cssList.optString(k));
                            if (f != null && f.isFile() && f.length() < MAX_INLINE) css.put(readText(f));
                        }
                    }
                    meta.put("css", css);
                    // Semua file js satu grup digabung dalam satu fungsi supaya const/function
                    // dari file awal tetap terlihat oleh file berikutnya (seperti di Chrome).
                    js.append("R.grp(").append(jsonForScript(meta.toString())).append(",function(chrome,browser){");
                    JSONArray jsList = grp.optJSONArray("js");
                    if (jsList != null) {
                        for (int k = 0; k < jsList.length(); k++) {
                            File f = resolveIn(dir, jsList.optString(k));
                            if (f == null || !f.isFile() || f.length() > MAX_INLINE) continue;
                            js.append("\n").append(readText(f)).append("\n;");
                        }
                    }
                    js.append("\n});\n");
                }
                js.append("})();");
                out.add(js.toString());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void copyKey(JSONObject from, JSONObject to, String k) throws JSONException {
        JSONArray a = from.optJSONArray(k);
        if (a != null) to.put(k, a);
    }

    /** JSON aman diletakkan di dalam &lt;script&gt; / string JS. */
    private static String jsonForScript(String json) {
        return json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029").replace("</", "<\\/");
    }

    // ------------------------------------------------------------------ resource & storage

    /** File ekstensi untuk https://sy-ext.local/&lt;id&gt;/&lt;path&gt; (chrome.runtime.getURL). Null bila tidak boleh. */
    public synchronized File resource(String id, String path) {
        JSONObject e = entry(id);
        if (e == null || !e.optBoolean("enabled", true)) return null;
        File f = resolveIn(new File(root, id), path);
        return f != null && f.isFile() ? f : null;
    }

    public synchronized String storageGet(String id) {
        if (entry(id) == null) return "{}";
        File f = new File(root, id + ".storage.json");
        try {
            return f.isFile() ? readText(f) : "{}";
        } catch (IOException e) {
            return "{}";
        }
    }

    public synchronized void storageSet(String id, String json) {
        if (entry(id) == null || json == null || json.length() > 10 * 1024 * 1024) return;
        try {
            new JSONObject(json);
            writeText(new File(root, id + ".storage.json"), json);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ util file

    static File resolveIn(File dir, String rel) {
        String r = safeRel(rel);
        if (r == null) return null;
        try {
            File f = new File(dir, r).getCanonicalFile();
            String base = dir.getCanonicalPath() + File.separator;
            return f.getPath().startsWith(base) ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Normalisasi path relatif; tolak path absolut dan "..". */
    static String safeRel(String p) {
        if (p == null) return null;
        p = p.replace('\\', '/');
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        StringBuilder b = new StringBuilder();
        for (String part : p.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            if (b.length() > 0) b.append('/');
            b.append(part);
        }
        return b.length() == 0 ? null : b.toString();
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".wasm")) return "application/wasm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static int readFully(InputStream in, byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) break;
            got += r;
        }
        return got;
    }

    private static long copyLimited(InputStream in, File out, long limit) throws IOException {
        OutputStream os = new FileOutputStream(out);
        long total = 0;
        try {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > limit) throw new IOException("Ukuran ekstensi melebihi batas 200 MB");
                os.write(buf, 0, r);
            }
        } finally {
            os.close();
        }
        return total;
    }

    static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    static String readText(File f) throws IOException {
        String s = new String(readBytes(f), UTF8);
        return s.length() > 0 && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    private static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            os.write(s.getBytes(UTF8));
        } finally {
            os.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }

    private static void copyDir(File from, File to) throws IOException {
        to.mkdirs();
        File[] kids = from.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File d = new File(to, k.getName());
            if (k.isDirectory()) copyDir(k, d);
            else {
                InputStream in = new FileInputStream(k);
                try {
                    copyLimited(in, d, Long.MAX_VALUE);
                } finally {
                    in.close();
                }
            }
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
