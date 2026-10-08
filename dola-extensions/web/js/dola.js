/* Studio Yanz Android — Dola (WebView + ekstensi unggahan pengguna).
   Membuka dola.com di WebView dalam aplikasi memakai login akun pengguna sendiri.
   Pengguna mengetik prompt, melampirkan gambar referensi lewat tombol unggah Dola,
   mengirim, lalu mengunduh hasil normal. Tidak ada penghapus watermark / pelompat
   limit / rotasi akun / pemalsu fingerprint.
   Jembatan native: plugin Capacitor "DolaWeb" (lihat android/.../DolaWebPlugin.java).
   Ekstensi: plugin "SYExt" (com.studioyanz.app.ext) — unggah ZIP/CRX/XPI atau folder berisi
   manifest.json, on/off per ekstensi; content_scripts disuntikkan ke WebView Dola.
   Di pratinjau web (tanpa app native), tombol menjelaskan bahwa fitur berjalan di APK. */
(function () {
  'use strict';
  let C = {};
  const PKEY = 'sy.dola.profiles';
  const uid = () => Math.random().toString(36).slice(2, 9);
  let promptDraft = ''; // tetap ada saat layar dirender ulang

  function profiles() {
    try { const a = JSON.parse(localStorage.getItem(PKEY)); if (Array.isArray(a) && a.length) return a; } catch (_) {}
    const def = [{ id: uid(), name: 'Dola Utama' }]; saveProfiles(def); return def;
  }
  function saveProfiles(a) { try { localStorage.setItem(PKEY, JSON.stringify(a)); } catch (_) {} }
  const hasNative = () => !!(window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.DolaWeb);

  // ---------- ekstensi (plugin native "SYExt") ----------
  const EXT = { items: [], loaded: false, busy: false, docStart: true };
  const extPlugin = () => window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.SYExt;
  if (window.I18N && window.I18N.EN) Object.assign(window.I18N.EN, {
    'Ekstensi': 'Extensions',
    'Unggah ekstensi Chrome/Firefox (.zip, .crx, .xpi) atau folder berisi manifest.json. Langsung aktif di browser Dola.': 'Upload a Chrome/Firefox extension (.zip, .crx, .xpi) or a folder containing manifest.json. It runs in the Dola browser right away.',
    'Unggah ZIP': 'Upload ZIP', 'Unggah folder': 'Upload folder', 'Belum ada ekstensi.': 'No extensions yet.',
    'Memasang ekstensi…': 'Installing extension…', 'aktif di Dola': 'is active in Dola', 'Gagal memasang': 'Install failed',
    'Hapus ekstensi?': 'Remove extension?', 'Hapus': 'Remove', 'Aktif': 'On', 'Mati': 'Off', 'Dihapus': 'Removed',
    'Yang jalan: content_scripts (JS/CSS), chrome.storage, chrome.i18n, chrome.runtime.getURL. Background, popup, dan izin tab tidak tersedia di WebView.': 'Supported: content_scripts (JS/CSS), chrome.storage, chrome.i18n, chrome.runtime.getURL. Background, popups and tab permissions are not available in WebView.',
    'WebView perangkat ini lama: skrip document_start dijalankan setelah halaman dimuat. Perbarui Android System WebView.': 'This device\'s WebView is old: document_start scripts run after the page loads. Update Android System WebView.',
    'Ekstensi berjalan di APK Android.': 'Extensions run in the Android APK.'
  });

  function extListHtml() {
    const t = C.t, esc = C.esc;
    if (!extPlugin()) return `<p class="note" style="margin:0">${t('Ekstensi berjalan di APK Android.')}</p>`;
    if (!EXT.loaded) return `<p class="note" style="margin:0">…</p>`;
    const warn = EXT.docStart ? '' : `<p class="note" style="margin:0 0 8px;color:var(--warn)">${t('WebView perangkat ini lama: skrip document_start dijalankan setelah halaman dimuat. Perbarui Android System WebView.')}</p>`;
    if (!EXT.items.length) return warn + `<p class="note" style="margin:0">${t('Belum ada ekstensi.')}</p>`;
    return warn + `<div class="list">${EXT.items.map((x) => `<div class="ext-row${x.enabled ? '' : ' off'}">
        <div class="ext-ic">${x.icon ? `<img src="${esc(x.icon)}" alt="">` : '🧩'}</div>
        <div class="tx grow"><div class="t1">${esc(x.name)}</div>
          <div class="t2">${x.version ? 'v' + esc(x.version) + ' · ' : ''}MV${esc(x.manifestVersion || 2)} · ${esc(x.contentScripts || 0)} content script</div>
          ${(x.warnings || []).map((w) => `<div class="ext-warn">⚠ ${esc(w)}</div>`).join('')}</div>
        <label class="ext-tg" aria-label="${x.enabled ? t('Aktif') : t('Mati')}"><input type="checkbox" data-ext-tg="${esc(x.id)}" ${x.enabled ? 'checked' : ''} ${x.broken ? 'disabled' : ''}><i></i></label>
        <button class="btn sm" data-ext-del="${esc(x.id)}" aria-label="${t('Hapus')}">🗑</button>
      </div>`).join('')}</div>`;
  }

  function extCardHtml() {
    const t = C.t;
    return `<div class="card" style="margin-top:14px">
        <div class="row" style="gap:8px;margin-bottom:6px"><b class="grow">🧩 ${t('Ekstensi')}</b>${EXT.items.length ? `<span class="badge-soft">${EXT.items.filter((x) => x.enabled).length}/${EXT.items.length}</span>` : ''}</div>
        <p class="note" style="margin:0 0 10px">${t('Unggah ekstensi Chrome/Firefox (.zip, .crx, .xpi) atau folder berisi manifest.json. Langsung aktif di browser Dola.')}</p>
        <div class="row" style="gap:8px;margin-bottom:12px">
          <button class="btn sm grow" data-ext-zip ${EXT.busy ? 'disabled' : ''}>${EXT.busy ? t('Memasang ekstensi…') : '📦 ' + t('Unggah ZIP')}</button>
          <button class="btn sm grow" data-ext-dir ${EXT.busy ? 'disabled' : ''}>📁 ${t('Unggah folder')}</button>
        </div>
        <div id="dola-ext-list">${extListHtml()}</div>
        <p class="note" style="margin-top:10px">${t('Yang jalan: content_scripts (JS/CSS), chrome.storage, chrome.i18n, chrome.runtime.getURL. Background, popup, dan izin tab tidak tersedia di WebView.')}</p>
      </div>`;
  }

  function extRefresh() {
    const card = document.getElementById('dola-ext');
    if (card) { card.innerHTML = extCardHtml(); extBind(card); }
  }
  function extApply(r) { if (r && Array.isArray(r.items)) { EXT.items = r.items; EXT.docStart = r.documentStart !== false; } EXT.loaded = true; extRefresh(); }
  async function extLoad() {
    const P = extPlugin(); if (!P) return;
    try { extApply(await P.list()); } catch (_) { EXT.loaded = true; extRefresh(); }
  }
  async function extInstall(folder) {
    const P = extPlugin(); if (!P || EXT.busy) return;
    EXT.busy = true; extRefresh();
    try {
      const r = await (folder ? P.pickFolder() : P.pickZip());
      extApply(r);
      if (r && r.installed) C.toast('✓ ' + r.installed.name + ' ' + C.t('aktif di Dola'));
    } catch (e) {
      const msg = (e && e.message) || '';
      if (!/Dibatalkan|CANCELLED/i.test(msg + (e && e.code || ''))) C.toast(C.t('Gagal memasang') + (msg ? ': ' + msg : ''));
    } finally { EXT.busy = false; extRefresh(); }
  }
  function extBind(root) {
    const P = extPlugin();
    const z = root.querySelector('[data-ext-zip]'); if (z) z.onclick = () => P ? extInstall(false) : C.toast(C.t('Ekstensi berjalan di APK Android.'));
    const d = root.querySelector('[data-ext-dir]'); if (d) d.onclick = () => P ? extInstall(true) : C.toast(C.t('Ekstensi berjalan di APK Android.'));
    root.querySelectorAll('[data-ext-tg]').forEach((cb) => cb.onchange = async () => {
      try { extApply(await P.setEnabled({ id: cb.dataset.extTg, enabled: cb.checked })); }
      catch (_) { cb.checked = !cb.checked; }
    });
    root.querySelectorAll('[data-ext-del]').forEach((b) => b.onclick = () => {
      const x = EXT.items.find((i) => i.id === b.dataset.extDel); if (!x) return;
      const t = C.t;
      C.openSheet(`<h3>${t('Hapus ekstensi?')}</h3><p class="note">${C.esc(x.name)}</p>
        <div class="row" style="gap:8px;margin-top:12px"><button class="btn grow" id="xd-no">${t('Batal')}</button><button class="btn danger grow" id="xd-yes">${t('Hapus')}</button></div>`);
      const s = document.getElementById('sheet');
      s.querySelector('#xd-no').onclick = () => C.closeSheet();
      s.querySelector('#xd-yes').onclick = async () => {
        C.closeSheet();
        try { extApply(await P.remove({ id: x.id })); C.toast(t('Dihapus')); } catch (_) {}
      };
    });
  }

  function html() {
    const t = C.t, esc = C.esc;
    const list = profiles();
    return `<div class="screen-head"><h1>Dola</h1><p>${t('Login pakai akunmu sendiri di WebView aplikasi. Hasil normal diunduh seperti biasa.')}</p></div>
      <div class="list">${list.map((p, i) => `<div class="card" style="padding:12px"><div class="row">
        <div class="ava" style="width:44px;height:44px;border-radius:12px;background:linear-gradient(135deg,hsl(${i * 70 + 30},60%,55%),hsl(${i * 70},60%,40%))">${esc(p.name.slice(0, 2).toUpperCase())}</div>
        <div class="tx grow"><div class="t1">${esc(p.name)}</div><div class="t2">${t('Sesi login terpisah')}</div></div>
        <button class="btn sm" data-dola-open="${p.id}">${t('Buka')}</button>
        <button class="btn sm" data-dola-menu="${p.id}" aria-label="Menu">⋯</button>
      </div></div>`).join('')}</div>
      <button class="btn block" style="margin-top:10px" data-dola-add>${ICON('add', 18)} ${t('Tambah akun')}</button>
      <div class="card" style="margin-top:14px">
        <label class="field" style="margin:0"><span>${t('Prompt')}</span><textarea id="dola-prompt" placeholder="${t('Tulis prompt untuk Dola…')}">${esc(promptDraft)}</textarea></label>
        <button class="btn primary block" style="margin-top:10px" data-dola-compose>${ICON('dola', 20)} ${t('Buka Dola dengan prompt ini')}</button>
        <p class="note" style="margin-top:10px">${t('Lampirkan gambar referensi lewat tombol unggah di Dola. Pilih rasio di antarmuka Dola.')}</p>
        <p class="note" style="margin-top:6px">${t('Login "Lanjutkan dengan Google" diblokir Google di dalam WebView — gunakan login email/nomor di Dola.')}</p>
      </div>
      <div id="dola-ext">${extCardHtml()}</div>
      ${hasNative() ? '' : `<div class="card" style="margin-top:12px;border-style:dashed"><p class="note" style="margin:0">${t('Catatan: Dola berjalan penuh di APK Android (WebView). Di pratinjau ini tombol hanya contoh.')}</p></div>`}`;
  }

  function activeFirst() { return profiles()[0]; }

  async function openDola(profileId, prompt) {
    const p = profiles().find((x) => x.id === profileId) || activeFirst();
    if (hasNative()) {
      try { await window.Capacitor.Plugins.DolaWeb.open({ profileId: p.id, profileName: p.name, url: 'https://dola.com/', prompt: prompt || '' }); }
      catch (e) { C.toast(C.t('Gagal membuka Dola')); }
    } else {
      C.toast(C.t('Dola terbuka di aplikasi Android (WebView). Ini pratinjau web.'));
    }
  }

  function bind() {
    const view = document.getElementById('view');
    view.querySelectorAll('[data-dola-open]').forEach((b) => b.onclick = () => openDola(b.dataset.dolaOpen, ''));
    view.querySelectorAll('[data-dola-menu]').forEach((b) => b.onclick = () => profileMenu(b.dataset.dolaMenu));
    const add = view.querySelector('[data-dola-add]'); if (add) add.onclick = addProfile;
    const ta = view.querySelector('#dola-prompt'); if (ta) ta.oninput = () => { promptDraft = ta.value; };
    const comp = view.querySelector('[data-dola-compose]'); if (comp) comp.onclick = () => {
      const pr = (view.querySelector('#dola-prompt').value || '').trim();
      openDola(activeFirst().id, pr);
    };
    const ext = view.querySelector('#dola-ext'); if (ext) { extBind(ext); extLoad(); }
  }

  function addProfile() {
    const t = C.t;
    C.openSheet(`<h3>${t('Akun Dola baru')}</h3>
      <label class="field"><span>${t('Nama akun')}</span><input id="dp-name" placeholder="${t('mis. Dola Cadangan')}"></label>
      <button class="btn primary block" id="dp-save">${t('Simpan')}</button>`);
    const s = document.getElementById('sheet');
    s.querySelector('#dp-save').onclick = () => {
      const name = (s.querySelector('#dp-name').value || '').trim(); if (!name) { C.toast(t('Isi nama akun')); return; }
      const a = profiles(); a.push({ id: uid(), name }); saveProfiles(a); C.closeSheet(); C.toast(t('Tersimpan')); C.rerender();
    };
  }
  function profileMenu(id) {
    const t = C.t; const a = profiles(); const p = a.find((x) => x.id === id); if (!p) return;
    C.openSheet(`<h3>${C.esc(p.name)}</h3>
      <label class="field"><span>${t('Ganti nama')}</span><input id="dm-name" value="${C.esc(p.name)}"></label>
      <div class="row" style="gap:8px"><button class="btn danger" id="dm-del">${t('Hapus akun')}</button><button class="btn primary grow" id="dm-save">${t('Simpan')}</button></div>
      <p class="note" style="margin-top:10px">${t('Menghapus akun juga menghapus sesi login WebView-nya di perangkat.')}</p>`);
    const s = document.getElementById('sheet');
    s.querySelector('#dm-save').onclick = () => { const nm = (s.querySelector('#dm-name').value || '').trim(); if (!nm) return; p.name = nm; saveProfiles(a); C.closeSheet(); C.toast(t('Tersimpan')); C.rerender(); };
    s.querySelector('#dm-del').onclick = () => {
      if (a.length <= 1) { C.toast(t('Minimal satu akun')); return; }
      saveProfiles(a.filter((x) => x.id !== id));
      if (hasNative()) { try { window.Capacitor.Plugins.DolaWeb.clearSession({ profileId: id }); } catch (_) {} }
      C.closeSheet(); C.toast(t('Dihapus')); C.rerender();
    };
  }

  window.Dola = { boot: (ctx) => { C = ctx; }, html, bind };
})();
