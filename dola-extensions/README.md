# Studio Yanz — Ekstensi untuk browser Dola

Tambalan untuk APK `studio-yanz-0.3.1-arm64`: unggah ekstensi (ZIP / CRX / XPI / folder berisi
`manifest.json`), langsung aktif di WebView Dola, dengan tombol on/off dan hapus.

## Isi
- `src/com/studioyanz/app/ext/ExtStore.java`: pasang/simpan ekstensi di `filesDir/sy_ext/`, baca manifest (MV2/MV3, komentar, `_locales`), status on/off, storage.
- `src/com/studioyanz/app/ext/ExtRuntime.java`: dipasang ke `DolaWebActivity`. Menyuntikkan `content_scripts` lewat `addDocumentStartJavaScript`, melayani file ekstensi di `https://sy-ext.local/<id>/…`, dan menambah tombol 🧩 di toolbar (unggah, on/off, hapus; perubahan langsung diterapkan dengan reload).
- `src/com/studioyanz/app/ext/ExtensionsPlugin.java`: plugin Capacitor `SYExt` untuk kartu "Ekstensi" di layar Dola aplikasi utama.
- `assets/sy_ext_runtime.js`: runtime JS (pencocokan `matches`/`exclude_matches`/globs, `run_at`, `all_frames`, CSS, API `chrome.*`/`browser.*`).
- `web/js/dola.js`, `web/css/app.css`: UI kartu Ekstensi.
- `smali-patches/patch_smali.py`: dua kait, yaitu `registerPlugin(ExtensionsPlugin)` di `MainActivity` dan `ExtRuntime.attach(this, web)` sebelum `loadUrl` di `DolaWebActivity`.
- `build.sh` dan `repack.py`: rakit ulang, zipalign, dan tanda tangan.

## Yang didukung
`content_scripts` (js + css, `matches`, `exclude_matches`, `include_globs`, `exclude_globs`, `run_at`, `all_frames`),
`chrome.storage.local/sync/session` (+ `onChanged`), `chrome.i18n`, `chrome.runtime.getURL/getManifest/id`,
`chrome.runtime.sendMessage`/`onMessage` (antar skrip di halaman yang sama).

Tidak didukung di Android WebView: background / service worker, popup toolbar, `tabs`, `webRequest`, `scripting`.

## Build
```
SY_KEYSTORE=studio-yanz-release.jks SY_KEYSTORE_PASSWORD=... TOOLS=/opt/tools \
  ./build.sh studio-yanz-0.3.1-arm64-v8a-release-b5.apk studio-yanz-0.3.1-arm64-ekstensi.apk
```
Keystore dan APK tidak disimpan di repo ini (repo publik).
