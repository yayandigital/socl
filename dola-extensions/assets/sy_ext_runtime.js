/* Studio Yanz — runtime ekstensi untuk WebView Dola.
   Menjalankan content_scripts dari manifest.json (MV2/MV3) di halaman yang cocok,
   dengan API chrome.* dasar: runtime (id, getURL, getManifest, sendMessage, onMessage),
   storage (local/sync/session + onChanged), i18n. Disuntikkan oleh ExtRuntime.java. */
(function () {
  'use strict';
  var KEY = '__syx___SY_TOKEN__';
  if (window[KEY]) return;
  var TOKEN = '__SY_TOKEN__';
  var bridge = window.SYExtBridge;
  try { delete window.SYExtBridge; } catch (_) {}
  var exts = {};
  var ran = {};

  // ---------------------------------------------------------------- pencocokan URL
  function esc(s) { return s.replace(/[.+^${}()|[\]\\]/g, '\\$&'); }
  function matchPattern(p, url) {
    if (p === '<all_urls>') return /^(https?|file|ftp|wss?):/.test(url);
    var m = /^(\*|https?|file|ftp|wss?|urn):\/\/([^/]*)(\/.*)?$/.exec(p);
    if (!m) return false;
    var u;
    try { u = new URL(url); } catch (_) { return false; }
    var scheme = u.protocol.slice(0, -1);
    if (m[1] === '*') { if (scheme !== 'http' && scheme !== 'https') return false; }
    else if (m[1] !== scheme) return false;
    var host = m[2].replace(/:\d+$/, '');
    if (host !== '*' && host !== '') {
      if (host.indexOf('*.') === 0) {
        var base = host.slice(2);
        if (u.hostname !== base && u.hostname.slice(-base.length - 1) !== '.' + base) return false;
      } else if (u.hostname !== host) return false;
    }
    var path = m[3] || '/*';
    var re = new RegExp('^' + path.split('*').map(esc).join('.*') + '$');
    return re.test(u.pathname + u.search);
  }
  function glob(g, url) {
    var re = new RegExp('^' + g.split('').map(function (c) { return c === '*' ? '.*' : c === '?' ? '.' : esc(c); }).join('') + '$');
    return re.test(url);
  }
  function any(list, fn, url) { for (var i = 0; list && i < list.length; i++) if (fn(list[i], url)) return true; return false; }
  function applies(g, url) {
    if (window.top !== window && !g.all_frames) return false;
    if (!any(g.matches, matchPattern, url)) return false;
    if (any(g.exclude_matches, matchPattern, url)) return false;
    if (g.include_globs && g.include_globs.length && !any(g.include_globs, glob, url)) return false;
    if (any(g.exclude_globs, glob, url)) return false;
    return true;
  }

  // ---------------------------------------------------------------- penjadwalan run_at
  function whenRoot(fn) {
    if (document.documentElement) return fn();
    var mo = new MutationObserver(function () { if (document.documentElement) { mo.disconnect(); fn(); } });
    mo.observe(document, { childList: true });
  }
  function schedule(runAt, fn) {
    var st = document.readyState;
    if (runAt === 'document_start') return fn();
    if (runAt === 'document_end') {
      if (st !== 'loading') return fn();
      return document.addEventListener('DOMContentLoaded', fn, { once: true });
    }
    // document_idle
    if (st === 'complete') return fn();
    if (st === 'interactive') return setTimeout(fn, 0);
    document.addEventListener('DOMContentLoaded', function () { setTimeout(fn, 0); }, { once: true });
  }
  function addCss(text) {
    whenRoot(function () {
      var s = document.createElement('style');
      s.setAttribute('data-sy-ext', '');
      s.textContent = text;
      (document.head || document.documentElement).appendChild(s);
    });
  }

  // ---------------------------------------------------------------- API chrome.*
  function evt() {
    var ls = [];
    return {
      addListener: function (f) { if (typeof f === 'function' && ls.indexOf(f) < 0) ls.push(f); },
      removeListener: function (f) { var i = ls.indexOf(f); if (i >= 0) ls.splice(i, 1); },
      hasListener: function (f) { return ls.indexOf(f) >= 0; },
      hasListeners: function () { return ls.length > 0; },
      _fire: function () { var a = arguments, out = []; ls.slice().forEach(function (f) { try { out.push(f.apply(null, a)); } catch (e) { console.error(e); } }); return out; }
    };
  }
  function cbOrPromise(api, cb, work) {
    var p = new Promise(function (res, rej) { try { res(work()); } catch (e) { rej(e); } });
    if (typeof cb !== 'function') return p;
    p.then(function (v) { cb(v); }, function (e) {
      api.runtime.lastError = { message: String(e && e.message || e) };
      try { cb(); } finally { api.runtime.lastError = undefined; }
    });
  }

  function makeStorage(ext, api) {
    var memKey = 'sy-ext:' + ext.id;
    function load() {
      try { if (bridge) return JSON.parse(bridge.get(TOKEN, ext.id) || '{}'); } catch (_) {}
      try { return JSON.parse(localStorage.getItem(memKey) || '{}'); } catch (_) { return {}; }
    }
    function save(all) {
      var s = JSON.stringify(all);
      try { if (bridge) return bridge.set(TOKEN, ext.id, s); } catch (_) {}
      try { localStorage.setItem(memKey, s); } catch (_) {}
    }
    var onChanged = evt();
    var session = {};
    function area(name) {
      var isSession = name === 'session';
      function all() { if (isSession) return session; var a = load(); return a[name] || {}; }
      function put(obj) { if (isSession) { session = obj; return; } var a = load(); a[name] = obj; save(a); }
      function clone(v) { return v === undefined ? undefined : JSON.parse(JSON.stringify(v)); }
      var o = {
        get: function (keys, cb) {
          if (typeof keys === 'function') { cb = keys; keys = null; }
          return cbOrPromise(api, cb, function () {
            var d = all(), r = {};
            if (keys == null) return clone(d);
            if (typeof keys === 'string') keys = [keys];
            if (Array.isArray(keys)) keys.forEach(function (k) { if (k in d) r[k] = clone(d[k]); });
            else Object.keys(keys).forEach(function (k) { r[k] = k in d ? clone(d[k]) : keys[k]; });
            return r;
          });
        },
        set: function (items, cb) {
          return cbOrPromise(api, cb, function () {
            var d = all(), ch = {};
            Object.keys(items || {}).forEach(function (k) {
              var nv = clone(items[k]);
              ch[k] = { oldValue: d[k], newValue: nv };
              d[k] = nv;
            });
            put(d);
            onChanged._fire(ch, name); o.onChanged._fire(ch);
          });
        },
        remove: function (keys, cb) {
          return cbOrPromise(api, cb, function () {
            var d = all(), ch = {};
            (typeof keys === 'string' ? [keys] : keys || []).forEach(function (k) { if (k in d) { ch[k] = { oldValue: d[k] }; delete d[k]; } });
            put(d);
            onChanged._fire(ch, name); o.onChanged._fire(ch);
          });
        },
        clear: function (cb) {
          return cbOrPromise(api, cb, function () {
            var d = all(), ch = {};
            Object.keys(d).forEach(function (k) { ch[k] = { oldValue: d[k] }; });
            put({});
            onChanged._fire(ch, name); o.onChanged._fire(ch);
          });
        },
        getBytesInUse: function (keys, cb) {
          if (typeof keys === 'function') { cb = keys; keys = null; }
          return cbOrPromise(api, cb, function () { return JSON.stringify(all()).length; });
        },
        setAccessLevel: function (_o, cb) { return cbOrPromise(api, cb, function () {}); },
        onChanged: evt(),
        QUOTA_BYTES: 10485760
      };
      return o;
    }
    return { local: area('local'), sync: area('sync'), session: area('session'), managed: area('managed'), onChanged: onChanged };
  }

  function makeI18n(ext) {
    var msgs = ext.messages || {};
    var ui = (navigator.language || 'en').replace('-', '_');
    return {
      getMessage: function (name, subs) {
        var m = msgs[String(name).toLowerCase()];
        if (!m && name === '@@extension_id') return ext.id;
        if (!m && name === '@@ui_locale') return ui;
        if (!m) return '';
        if (subs != null && !Array.isArray(subs)) subs = [subs];
        subs = (subs || []).map(String);
        var ph = m.placeholders || {};
        var s = String(m.message || '').replace(/\$([A-Za-z0-9_@]+)\$/g, function (all, k) {
          var p = ph[k] || ph[k.toLowerCase()];
          if (!p) for (var key in ph) if (key.toLowerCase() === k.toLowerCase()) p = ph[key];
          return p ? String(p.content || '') : all;
        });
        return s.replace(/\$(\$|[1-9])/g, function (all, d) { return d === '$' ? '$' : (subs[+d - 1] || ''); });
      },
      getUILanguage: function () { return ui.replace('_', '-'); },
      getAcceptLanguages: function (cb) { var l = (navigator.languages || [navigator.language]).slice(); return cb ? cb(l) : Promise.resolve(l); },
      detectLanguage: function (_t, cb) { var r = { isReliable: false, languages: [] }; return cb ? cb(r) : Promise.resolve(r); }
    };
  }

  function makeApi(ext) {
    var api = {};
    var onMessage = evt();
    api.runtime = {
      id: ext.id,
      lastError: undefined,
      getURL: function (p) { return ext.base + String(p || '').replace(/^\/+/, ''); },
      getManifest: function () { return JSON.parse(JSON.stringify(ext.manifest)); },
      // Tanpa background: pesan diteruskan ke listener onMessage di halaman yang sama.
      sendMessage: function () {
        var a = Array.prototype.slice.call(arguments);
        var cb = typeof a[a.length - 1] === 'function' ? a.pop() : null;
        var msg = a.length > 1 && typeof a[0] === 'string' ? a[1] : a[0];
        return cbOrPromise(api, cb, function () {
          return new Promise(function (res) {
            var answered = false;
            var reply = function (v) { if (!answered) { answered = true; res(v); } };
            var outs = onMessage._fire(msg, { id: ext.id, url: location.href, origin: location.origin }, reply);
            var keep = false;
            outs.forEach(function (o) {
              if (o === true) keep = true;
              else if (o && typeof o.then === 'function') { keep = true; o.then(reply, function () { reply(); }); }
            });
            if (!keep) setTimeout(function () { reply(undefined); }, 0);
          });
        });
      },
      onMessage: onMessage,
      onMessageExternal: evt(),
      onConnect: evt(),
      onInstalled: evt(),
      onStartup: evt(),
      onSuspend: evt(),
      connect: function () {
        return { name: '', postMessage: function () {}, disconnect: function () {}, onMessage: evt(), onDisconnect: evt() };
      },
      getPlatformInfo: function (cb) { var r = { os: 'android', arch: 'arm64', nacl_arch: 'arm' }; return cb ? cb(r) : Promise.resolve(r); },
      getContexts: function (_f, cb) { return cb ? cb([]) : Promise.resolve([]); }
    };
    api.extension = { getURL: api.runtime.getURL, inIncognitoContext: false, lastError: undefined };
    api.storage = makeStorage(ext, api);
    api.i18n = makeI18n(ext);
    return api;
  }

  // ---------------------------------------------------------------- pendaftaran dari ExtRuntime
  var RT = {
    ext: function (def) { def.api = makeApi(def); exts[def.id] = def; },
    grp: function (g, fn) {
      var ext = exts[g.ext];
      var key = g.ext + '#' + g.grp;
      if (!ext || ran[key]) return;
      var url = location.href;
      if (!applies(g, url)) return;
      ran[key] = 1;
      (g.css || []).forEach(addCss);
      schedule(g.run_at, function () {
        try { fn.call(window, ext.api, ext.api); }
        catch (e) { console.error('[ekstensi ' + (ext.name || g.ext) + ']', e); }
      });
    }
  };
  try { Object.defineProperty(window, KEY, { value: RT, enumerable: false, configurable: false, writable: false }); }
  catch (_) { window[KEY] = RT; }
})();
