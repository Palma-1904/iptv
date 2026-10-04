// Gemeinsame Logik: M3U laden, parsen, klassifizieren, Player-Links bauen.
(function () {
  'use strict';

  var CFG = window.IPTV_CONFIG || {};

  // Für Suche/Vergleich: klein, ohne Akzente.
  function normalize(s) {
    return String(s || '')
      .toLowerCase()
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .trim();
  }

  // Findet das Komma, das Attribute vom Titel trennt (Kommas in "..." ignorieren).
  function titleComma(line) {
    var inQuote = false;
    for (var i = 0; i < line.length; i++) {
      var c = line[i];
      if (c === '"') inQuote = !inQuote;
      else if (c === ',' && !inQuote) return i;
    }
    return -1;
  }

  function parseExtinf(line) {
    var comma = titleComma(line);
    var head = comma >= 0 ? line.slice(0, comma) : line;
    var name = comma >= 0 ? line.slice(comma + 1).trim() : '';
    var attrs = {};
    var re = /([\w-]+)="([^"]*)"/g;
    var m;
    while ((m = re.exec(head))) attrs[m[1].toLowerCase()] = m[2].trim();
    return {
      name: name || attrs['tvg-name'] || 'Unbenannt',
      group: attrs['group-title'] || '',
      logo: attrs['tvg-logo'] || '',
      tvgId: attrs['tvg-id'] || '',
      tvgName: attrs['tvg-name'] || '',
      work: attrs['x-work'] || '',               // gleiche Kennung = gleicher Film/Serie in anderer Sprache
      lang: (attrs['x-lang'] || '').toUpperCase(),
      typeHint: normalize(attrs['tvg-type'] || attrs['type'] || '')
    };
  }

  var SERIES_NAME = /\bs\d{1,2}\s*e\d{1,3}\b|\b\d{1,2}x\d{2,3}\b/i;
  var SERIES_GROUP = /(^|[^a-z])(series?|serien|staffel|season|tv[ -]?shows?)([^a-z]|$)/;
  var MOVIE_GROUP = /(^|[^a-z])(vod|movies?|filme?|kino|cinema)([^a-z]|$)/;
  var VOD_EXT = /\.(mp4|mkv|avi|mov|m4v|wmv)(\?|$)/;

  function classify(e) {
    var h = e.typeHint;
    if (h === 'live' || h === 'movie' || h === 'series') return h;
    if (h === 'film' || h === 'vod') return 'movie';
    if (h === 'serie' || h === 'serien') return 'series';

    var g = normalize(e.group);
    var u = e.url.toLowerCase();
    if (u.indexOf('/series/') >= 0 || SERIES_GROUP.test(g) || SERIES_NAME.test(e.name)) return 'series';
    if (u.indexOf('/movie/') >= 0 || MOVIE_GROUP.test(g) || VOD_EXT.test(u)) return 'movie';
    return 'live';
  }

  function parse(text) {
    var lines = text.split(/\r?\n/);
    var out = [];
    var cur = null;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (!line) continue;
      if (line.indexOf('#EXTINF') === 0) {
        cur = parseExtinf(line);
      } else if (line.indexOf('#EXTGRP:') === 0) {
        if (cur && !cur.group) cur.group = line.slice(8).trim();
      } else if (line[0] !== '#') {
        if (!/^https?:\/\//i.test(line)) { cur = null; continue; }
        var e = cur || { name: line, group: '', logo: '', tvgId: '', tvgName: '', typeHint: '' };
        e.url = line;
        e.group = e.group || 'Sonstige';
        e.type = classify(e);
        e.search = normalize(e.name + ' ' + e.group);
        out.push(e);
        cur = null;
      }
    }
    buildAlternatives(out);
    return out;
  }

  // Browser (Mac, Fire TV/Silk) zeigen bei HEVC-Streams nur Ton. Gibt es denselben Sender in
  // normaler Qualität in der Liste (gleiche tvg-id oder gleicher Name ohne "HEVC"), wird der genommen.
  var HEVC = /\b(HEVC|H\.?265)\b/i;
  var altMap = new Map();
  function channelKey(n) {
    return normalize(n).replace(/\b(hevc|h\.?265|uhd|fhd|hd|sd|4k|raw|50fps|60fps)\b/g, '').replace(/[^a-z0-9]+/g, '');
  }
  function buildAlternatives(entries) {
    altMap = new Map();
    if (!usesBrowserPlayer()) return;
    var byId = new Map();
    var byName = new Map();
    entries.forEach(function (e) {
      if (e.type !== 'live' || HEVC.test(e.name)) return;
      if (e.tvgId && !byId.has(e.tvgId)) byId.set(e.tvgId, e);
      var k = channelKey(e.name);
      if (k && !byName.has(k)) byName.set(k, e);
    });
    entries.forEach(function (e) {
      if (e.type !== 'live' || !HEVC.test(e.name)) return;
      var alt = (e.tvgId && byId.get(e.tvgId)) || byName.get(channelKey(e.name));
      if (alt) altMap.set(e, alt);
    });
  }

  // Einrichtungs-Link vom Editor: "#liste=BENUTZER/GISTID/name" oder "#m3u=<adresse>".
  // Wird auf dem Gerät gespeichert und aus der Adresszeile entfernt. "#liste=" ohne Wert setzt zurück.
  var STORE = 'iptv-m3u';
  (function readSetupLink() {
    var h = new URLSearchParams(location.hash.slice(1));
    if (!h.has('liste') && !h.has('m3u')) return;
    var url = null;
    var ref = h.get('liste');
    if (ref) {
      var p = ref.split('/');
      if (p.length === 3 && p.every(function (x) { return /^[\w.-]+$/.test(x); })) {
        url = 'https://gist.githubusercontent.com/' + p[0] + '/' + p[1] + '/raw/' + p[2] + '.m3u';
      }
    } else if (h.get('m3u') && /^https?:\/\//i.test(h.get('m3u'))) {
      url = h.get('m3u');
    } else if (/^lokal\/[\w-]+\.m3u$/.test(h.get('m3u') || '')) {
      url = new URL(h.get('m3u'), location.href).href; // Kurzform für Tests im WLAN
    }
    try {
      if (url) localStorage.setItem(STORE, url);
      else localStorage.removeItem(STORE);
    } catch (err) { /* privat-Modus: gilt nur für diesen Aufruf */ }
    window.__setupUrl = url;
  })();

  // Einrichtungs-Link in der Adresse stehen lassen: Ein Symbol auf dem iPhone-/iPad-Home-Bildschirm
  // hat eigenen Speicher und kennt nur die Adresse, mit der es angelegt wurde.
  (function keepSetupInAddress() {
    var url = window.__setupUrl;
    if (url === undefined) {
      try { url = localStorage.getItem(STORE); } catch (err) { url = null; }
    }
    var hash = '';
    if (url) {
      var g = /^https:\/\/gist\.githubusercontent\.com\/([\w.-]+)\/([\w.-]+)\/raw\/([\w.-]+)\.m3u$/.exec(url);
      hash = g ? 'liste=' + g[1] + '/' + g[2] + '/' + g[3] : 'm3u=' + encodeURIComponent(url);
      hash = '#' + hash + '&ansicht=' + (/senioren\.html$/.test(location.pathname) ? 'senioren' : 'komplett');
    }
    if (location.hash !== hash) history.replaceState(null, '', location.pathname + location.search + hash);
  })();

  function playlistUrl() {
    var q = new URLSearchParams(location.search).get('m3u');
    var stored = null;
    try { stored = localStorage.getItem(STORE); } catch (err) { /* ignorieren */ }
    return q || window.__setupUrl || stored || CFG.m3uUrl || 'liste.m3u';
  }

  // Hat dieses Gerät eine eigene Playlist (Einrichtungs-Link oder ?m3u=)?
  function hasDeviceList() {
    return playlistUrl() !== (CFG.m3uUrl || 'liste.m3u');
  }

  // ---------- EPG (vom Editor erzeugt: <liste>.epg.json neben <liste>.m3u) ----------

  var epg = null;
  var epgOrder = []; // Senderreihenfolge im Reiter „Programm“ (aus dem Editor)

  function loadEpg() {
    var u = playlistUrl();
    // EPG gibt es nur zu Listen aus dem Editor (nicht zur Beispielliste im Repo).
    if (!hasDeviceList() || !/\.m3u8?(\?|$)/i.test(u)) return Promise.resolve(null);
    return fetch(u.replace(/\.m3u8?(?=\?|$)/i, '.epg.json'), { cache: 'no-cache' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        epg = d && d.channels ? d.channels : null;
        epgOrder = d && d.order ? d.order : [];
        return epg;
      })
      .catch(function () { return null; });
  }

  function nowNext(id) {
    var progs = epg && id ? epg[id] : null;
    if (!progs) return null;
    var t = Date.now() / 1000;
    for (var i = 0; i < progs.length; i++) {
      if (progs[i][0] <= t && progs[i][1] > t) return { now: progs[i], next: progs[i + 1] || null };
    }
    return null;
  }

  function hhmm(sec) {
    var d = new Date(sec * 1000);
    return (d.getHours() < 10 ? '0' : '') + d.getHours() + ':' + (d.getMinutes() < 10 ? '0' : '') + d.getMinutes();
  }

  // Sendungen eines Senders, die noch laufen oder kommen: [[start, ende, titel], …]
  function programmes(id) {
    var progs = epg && id ? epg[id] : null;
    if (!progs) return [];
    var t = Date.now() / 1000;
    return progs.filter(function (p) { return p[1] > t; });
  }

  function epgText(id) {
    var nn = nowNext(id);
    return nn ? nn.now[2] + ' · bis ' + hhmm(nn.now[1]) : '';
  }

  // Element zeigt laufende Sendung und aktualisiert sich jede Minute.
  function epgEl(id, cls) {
    var span = document.createElement('span');
    span.className = cls;
    span.setAttribute('data-tvg', id);
    span.textContent = epgText(id);
    return span;
  }

  function refreshEpgEls() {
    document.querySelectorAll('[data-tvg]').forEach(function (el) {
      el.textContent = epgText(el.getAttribute('data-tvg'));
    });
  }
  setInterval(refreshEpgEls, 60000);

  var lastText = null;
  var lastLoad = 0;

  function fetchText() {
    return fetch(playlistUrl(), { cache: 'no-cache' }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status + ' beim Laden der Playlist');
      return r.text();
    }).then(function (text) {
      if (text.indexOf('#EXT') < 0 && text.indexOf('http') < 0) {
        throw new Error('Datei ist keine gültige M3U-Playlist');
      }
      lastLoad = Date.now();
      return text;
    });
  }

  function load() {
    return Promise.all([fetchText(), loadEpg()]).then(function (res) {
      lastText = res[0];
      return parse(res[0]);
    });
  }

  // Holt die Playlist erneut, wenn die App wieder in den Vordergrund kommt
  // (z. B. vom Home-Bildschirm), und meldet nur echte Änderungen.
  var REFRESH_AFTER = 5 * 60 * 1000;
  function watch(onChange) {
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState !== 'visible') return;
      if (Date.now() - lastLoad < REFRESH_AFTER) return;
      Promise.all([fetchText(), loadEpg()]).then(function (res) {
        if (res[0] === lastText) return refreshEpgEls();
        lastText = res[0];
        onChange(parse(res[0]));
      }).catch(function (err) {
        console.warn('Aktualisierung fehlgeschlagen, alte Liste bleibt:', err.message);
      });
    });
  }

  // Gerät erkennen. "?tv=1" erzwingt den TV-Modus zum Testen, "?tv=0" schaltet ihn ab.
  var forcedTv = new URLSearchParams(location.search).get('tv');
  try {
    if (forcedTv === '1') localStorage.setItem('iptv-tv', '1');
    if (forcedTv === '0') localStorage.removeItem('iptv-tv');
    forcedTv = localStorage.getItem('iptv-tv') === '1';
  } catch (err) { forcedTv = forcedTv === '1'; }

  var ua = navigator.userAgent;
  var platform =
    /IPTVApp/.test(ua) ? 'app' :                                   // eigene Fire-TV-App
    forcedTv || /AFT[A-Z0-9]|Android ?TV|GoogleTV|SMART-TV|SmartTV|BRAVIA/i.test(ua) ? 'tv' :
    /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ? 'ios' :
    /Android/i.test(ua) ? 'android' :
    /Macintosh/.test(ua) ? 'mac' :                                // Mac (iPad oben schon erkannt)
    'web';
  var isTv = platform === 'tv' || platform === 'app';
  if (isTv) document.documentElement.classList.add('tv');

  // Android-Intent, z. B. für VLC: intent://host/pfad#Intent;scheme=http;type=video/*;package=…;end
  function intentUrl(url) {
    var m = /^(https?):\/\/(.*)$/i.exec(url);
    if (!m) return url;
    var pkg = CFG.androidPackage ? 'package=' + CFG.androidPackage + ';' : '';
    return 'intent://' + m[2] + '#Intent;scheme=' + m[1].toLowerCase() + ';type=video/*;' + pkg + 'end';
  }

  function playerTemplate() {
    var p = CFG.player || '{url}';
    return typeof p === 'string' ? p : (p[platform] || p.web || '{url}');
  }

  // Spielt dieses Gerät im eingebauten Browser-Player (player.html) ab?
  function usesBrowserPlayer() {
    return playerTemplate().indexOf('player.html') === 0;
  }

  function playerHref(entry) {
    entry = altMap.get(entry) || entry;
    var t = playerTemplate();
    var url = entry.url;
    return t
      .replace('{urlEncoded}', encodeURIComponent(url))
      .replace('{nameEncoded}', encodeURIComponent(entry.name || ''))
      .replace('{idEncoded}', encodeURIComponent(entry.tvgId || ''))
      .replace('{intent}', intentUrl(url))
      .replace('{url}', url);
  }

  // Logo-Bild; versteckt sich bei Ladefehler. http-Logos werden auf https-Seiten oft blockiert.
  function logoEl(src, cls) {
    var img = document.createElement('img');
    img.className = cls;
    img.alt = '';
    img.loading = 'lazy';
    img.decoding = 'async';
    img.referrerPolicy = 'no-referrer';
    if (src) {
      img.onerror = function () { img.classList.add('empty'); };
      img.src = src;
    } else {
      img.classList.add('empty');
    }
    return img;
  }

  // ---------- Eigene Fire-TV-App: Wiedergabe im eingebauten Player der App ----------
  // Die App stellt window.IPTVNative bereit. Links merken sich Eintrag und Liste (bindPlay),
  // damit der Player innerhalb der Gruppe umschalten kann.
  var NATIVE = platform === 'app' && !!window.IPTVNative && typeof window.IPTVNative.play === 'function';

  function absUrl(u) {
    try { return u ? new URL(u, location.href).href : ''; } catch (err) { return ''; }
  }

  function nativeItem(e) {
    return {
      name: e.name, url: e.url, logo: absUrl(e.logo), tvgId: e.tvgId || '', type: e.type, group: e.group,
      epg: e.tvgId ? programmes(e.tvgId).slice(0, 12) : []
    };
  }

  // Alle Live-Gruppen für die Senderliste im Player (setzt app.js bzw. senioren.js)
  var liveGroups = function () { return []; };

  // Neuere App: ganze Playlist als Baum (Live TV / Filme / Serien / Suche), nur bei Änderung übertragen.
  // Liefert app.js bzw. senioren.js: { version, build(): Baum, pathOf(entry): [Indizes] }
  var treeProvider = null;

  function nativeLeaf(e, label, title) {
    var o = { n: label || e.name, u: e.url, t: e.type };
    if (title && title !== o.n) o.ti = title;
    if (e.logo) o.l = absUrl(e.logo);
    if (e.tvgId) o.id = e.tvgId;
    if (e.type === 'live' && e.tvgId) o.e = programmes(e.tvgId).slice(0, 12);
    return o;
  }

  function playNativeTree(entry) {
    var N = window.IPTVNative;
    if (!treeProvider || typeof N.setTree !== 'function') return false;
    var tp = treeProvider();
    var path = tp && tp.pathOf(entry);
    if (!path) return false;
    if (!N.hasTree(tp.version)) N.setTree(tp.version, JSON.stringify(tp.build()));
    N.playPath(JSON.stringify({ path: path, view: /senioren/.test(location.pathname) ? 'senioren' : 'komplett' }));
    return true;
  }

  function playNative(entry, list) {
    if (playNativeTree(entry)) return;
    if (!list || list.indexOf(entry) < 0) list = [entry];
    var groups = [];
    var group = 0;
    if (entry.type === 'live') {
      groups = liveGroups().filter(function (g) { return g.items.length; });
      // Die übergebene Liste ist eine der Gruppen? Sonst (Suche, Programm) als eigene Gruppe vorne.
      group = -1;
      groups.forEach(function (g, i) {
        if (group < 0 && g.items.length === list.length && g.items.indexOf(entry) >= 0 && g.items[0] === list[0]) group = i;
      });
      if (group < 0) {
        groups.unshift({ name: entry.group || 'Auswahl', items: list });
        group = 0;
      }
    } else {
      groups = [{ name: entry.group || '', items: list }];
    }
    window.IPTVNative.play(JSON.stringify({
      // "items"/"index" für ältere App-Versionen
      index: list.indexOf(entry),
      items: list.map(nativeItem),
      group: group,
      groups: groups.map(function (g) { return { name: g.name, items: g.items.map(nativeItem) }; }),
      view: /senioren/.test(location.pathname) ? 'senioren' : 'komplett'
    }));
  }

  function bindPlay(a, entry, list) {
    a._play = { entry: entry, list: list || [entry] };
    return a;
  }

  if (NATIVE) {
    document.addEventListener('click', function (ev) {
      var a = ev.target.closest ? ev.target.closest('a') : null;
      if (!a || !a._play || ev.target.closest('.fav')) return;
      ev.preventDefault();
      playNative(a._play.entry, a._play.list);
    }, true);
  }

  // Die App meldet beim Schließen des Players den zuletzt gesehenen Sender -> Fokus dorthin.
  // Liegt er in einer anderen (geschlossenen) Gruppe, wird diese geöffnet.
  function nativeReturned(url) {
    var find = function () {
      return Array.prototype.find.call(document.querySelectorAll('a'), function (a) {
        return a._play && a._play.entry.url === url && a.getClientRects().length;
      });
    };
    var hit = find();
    if (!hit) {
      Array.prototype.some.call(document.querySelectorAll('details.group'), function (d) {
        if (!d.fill) return false;
        var was = d.open;
        d.open = true;
        d.fill();
        hit = find();
        if (!hit) d.open = was;
        return !!hit;
      });
    }
    if (hit && window.TV) window.TV.focusFirst(null, hit);
  }

  // Zurück-Taste der App: Dialog schließen, sonst offene Gruppe zuklappen.
  // Liefert true, wenn etwas geschlossen wurde (dann bleibt die App auf der Seite).
  function handleBack() {
    var modal = document.querySelector('[aria-modal="true"]:not([hidden])');
    if (modal) {
      (document.activeElement || document.body).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      return true;
    }
    var a = document.activeElement;
    var d = a && a.closest ? a.closest('details[open]') : null;
    if (!d) d = document.querySelector('details.group[open]');
    if (!d) return false;
    d.open = false;
    var s = d.querySelector('summary');
    if (s && window.TV) window.TV.focusFirst(null, s);
    return true;
  }

  window.IPTV = {
    config: CFG,
    normalize: normalize,
    parse: parse,
    load: load,
    watch: watch,
    hasDeviceList: hasDeviceList,
    loadEpg: loadEpg,
    nowNext: nowNext,
    programmes: programmes,
    epgOrder: function () { return epgOrder; },
    isHevc: function (e) { return HEVC.test(e.name || ''); },
    hhmm: hhmm,
    epgEl: epgEl,
    bindPlay: bindPlay,
    nativeReturned: nativeReturned,
    handleBack: handleBack,
    setLiveGroups: function (fn) { liveGroups = fn; },
    setTreeProvider: function (fn) { treeProvider = fn; },
    nativeTree: function () { return treeProvider ? treeProvider() : null; },   // zum Testen
    nativeLeaf: nativeLeaf,
    platform: platform,
    isTv: isTv,
    playerHref: playerHref,
    intentUrl: intentUrl,
    logoEl: logoEl
  };
})();
