// Hauptversion: Tabs, aufklappbare Gruppen, Suche.
(function () {
  'use strict';

  var MAX_RESULTS = 300;
  var TYPE_LABEL = { live: 'Live', movie: 'Film', series: 'Serie' };

  var content = document.getElementById('content');
  var search = document.getElementById('search');
  var tabs = Array.prototype.slice.call(document.querySelectorAll('.tab'));

  var all = [];
  var tabCounts = {};
  var byType = { live: [], movie: [], series: [] };
  var activeType = 'live';

  function status(text, retry) {
    content.textContent = '';
    var p = document.createElement('p');
    p.className = 'status';
    p.textContent = text;
    content.appendChild(p);
    if (retry) {
      var b = document.createElement('button');
      b.className = 'retry';
      b.textContent = 'Erneut versuchen';
      b.onclick = start;
      content.appendChild(b);
    }
  }

  function itemEl(e, showMeta, list) {
    var a = document.createElement('a');
    a.className = 'item';
    a.href = IPTV.playerHref(e);
    IPTV.bindPlay(a, e, list);
    a.appendChild(IPTV.logoEl(e.logo, 'logo'));
    var text = document.createElement('span');
    text.className = 'text';
    var name = document.createElement('span');
    name.className = 'name';
    name.textContent = e.name;
    text.appendChild(name);
    if (showMeta) {
      var meta = document.createElement('span');
      meta.className = 'meta';
      meta.textContent = TYPE_LABEL[e.type] + ' · ' + e.group;
      text.appendChild(meta);
    }
    if (e.tvgId && e.type === 'live') text.appendChild(IPTV.epgEl(e.tvgId, 'meta now'));
    a.appendChild(text);
    if (e.type === 'movie') { a._fav = e; a.appendChild(favEl(e)); }
    if (e.type === 'series') a._fav = e;
    return a;
  }

  // ---------- Sprachfassungen (vom Editor: x-work = gleiches Werk, x-lang = Sprache) ----------

  // Alte index.html aus dem Zwischenspeicher (ohne js/tree.js) nach einem Update: einmal neu laden
  if (!IPTV.lib) {
    var reloaded = null;
    try { reloaded = sessionStorage.getItem('iptv-reload'); sessionStorage.setItem('iptv-reload', '1'); } catch (err) { /* ignorieren */ }
    if (!reloaded) location.reload();
    return;
  }
  var lib = IPTV.lib;   // gemeinsame Hilfen (js/tree.js)
  var LANG_NAMES = lib.LANG_NAMES, langRank = lib.langRank, langName = lib.langName, byLang = lib.byLang;
  var units = lib.units;

  function unitEl(u, showMeta, list) {
    var a = itemEl(u.entry, showMeta, list);
    if (!u.variants || u.variants.length < 2) return a;
    a._play = null; // erst Sprache wählen
    var langs = document.createElement('span');
    langs.className = 'meta';
    langs.textContent = u.variants.map(function (v) { return v.lang || '?'; }).join(' · ');
    a.querySelector('.text').appendChild(langs);
    a.addEventListener('click', function (ev) {
      ev.preventDefault();
      chooseLang(u.entry.name, u.variants, a);
    });
    return a;
  }

  // Sprachauswahl als Dialog: große Knöpfe, Deutsch oben, Fernbedienung bleibt im Dialog.
  function chooseLang(title, variants, returnFocus) {
    var ov = document.createElement('div');
    ov.className = 'lang-overlay';
    ov.setAttribute('role', 'dialog');
    ov.setAttribute('aria-modal', 'true');
    var box = document.createElement('div');
    box.className = 'lang-box';
    var h = document.createElement('h2');
    h.textContent = title;
    var p = document.createElement('p');
    p.textContent = 'Sprache wählen';
    box.appendChild(h);
    box.appendChild(p);
    variants.forEach(function (v) {
      var a = document.createElement('a');
      a.className = 'lang-btn';
      a.href = IPTV.playerHref(v);
      IPTV.bindPlay(a, v);
      a.textContent = langName(v.lang);
      box.appendChild(a);
    });
    var cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'lang-cancel';
    cancel.textContent = 'Abbrechen';
    box.appendChild(cancel);
    ov.appendChild(box);

    function close() {
      ov.remove();
      document.removeEventListener('keydown', onKey);
      if (returnFocus) returnFocus.focus();
    }
    function onKey(ev) {
      if (ev.key === 'Escape' || ev.key === 'Backspace') { ev.preventDefault(); close(); }
    }
    cancel.addEventListener('click', close);
    ov.addEventListener('click', function (ev) { if (ev.target === ov) close(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(ov);
    box.querySelector('.lang-btn').focus();
  }

  var seriesLangs = lib.seriesLangs, groupsOf = lib.groupsOf;

  function renderType(type) {
    content.textContent = '';
    var list = byType[type];
    if (!list.length) return status('Keine Einträge in dieser Kategorie.');

    var frag = document.createDocumentFragment();
    if (type !== 'live') frag.appendChild(favHint());
    if (type === 'movie') {
      var favUnits = favMovies();
      if (favUnits.length) frag.appendChild(favGroup(favUnits));
    }
    var groups = groupsOf(list);
    if (type === 'series') groups = favSeriesFirst(groups);
    groups.forEach(function (items, group) {
      // <details>/<summary>: Auf-/Zuklappen erledigt der Browser ohne JS.
      var d = document.createElement('details');
      d.className = 'group';
      var s = document.createElement('summary');
      var title = document.createElement('span');
      title.className = 'gname';
      title.textContent = group;
      if (type === 'series') { s._fav = items[0]; s.appendChild(favEl(items[0])); }
      var langs = type === 'series' ? seriesLangs(items) : [];
      var count = document.createElement('span');
      count.className = 'gcount';
      count.textContent = langs.length > 1
        ? items.filter(function (e) { return e.lang === langs[0]; }).length + ' · ' + langs.length + ' Sprachen'
        : type === 'movie' ? units(items).length : items.length;
      s.appendChild(title);
      s.appendChild(count);
      d.appendChild(s);
      var box = document.createElement('div');
      box.className = 'list';
      d.appendChild(box);
      // Einträge erst beim ersten Öffnen erzeugen – hält große Playlists schnell.
      d.fill = function () {
        if (box.firstChild) return;
        if (langs.length > 1) return fillSeries(d, box, items, langs);
        var f = document.createDocumentFragment();
        units(items).forEach(function (u) { f.appendChild(unitEl(u, false, type === 'live' ? items : null)); });
        box.appendChild(f);
      };
      d.addEventListener('toggle', function () { if (d.open) { d.fill(); closeOthers(d); } });
      frag.appendChild(d);
    });
    content.appendChild(frag);
  }

  // Fernseher: nur eine Gruppe offen, damit man nicht an allen Sendern vorbeiblättern muss
  function closeOthers(d) {
    if (!IPTV.isTv) return;
    content.querySelectorAll('details[open]').forEach(function (o) {
      if (o !== d && !o.classList.contains('favgroup')) o.open = false;
    });
  }

  function fillSeries(d, box, items, langs) {
    var bar = document.createElement('div');
    bar.className = 'langbar';
    var current = langs[0];
    function draw() {
      box.textContent = '';
      var f = document.createDocumentFragment();
      var episodes = items.filter(function (e) { return e.lang === current; });
      episodes.forEach(function (e) { f.appendChild(itemEl(e, false, episodes)); });
      box.appendChild(f);
      Array.prototype.forEach.call(bar.children, function (b) {
        b.setAttribute('aria-pressed', String(b.dataset.lang === current));
      });
    }
    langs.forEach(function (l) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'langsel';
      b.dataset.lang = l;
      b.textContent = langName(l);
      b.addEventListener('click', function () { current = l; draw(); });
      bar.appendChild(b);
    });
    d.insertBefore(bar, box);
    draw();
  }

  function renderSearch(q) {
    content.textContent = '';
    var terms = IPTV.normalize(q).split(/\s+/).filter(Boolean);
    var hits = [];
    var total = 0;
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      var ok = true;
      for (var t = 0; t < terms.length; t++) {
        if (e.search.indexOf(terms[t]) < 0) { ok = false; break; }
      }
      if (ok) { total++; if (hits.length < MAX_RESULTS) hits.push(e); }
    }
    var info = document.createElement('p');
    info.className = 'status small';
    info.textContent = total === 0 ? 'Keine Treffer.'
      : total > MAX_RESULTS ? total + ' Treffer – die ersten ' + MAX_RESULTS + ' werden angezeigt.'
      : total + ' Treffer';
    content.appendChild(info);
    var box = document.createElement('div');
    box.className = 'list';
    var liveHits = hits.filter(function (e) { return e.type === 'live'; });
    units(hits).forEach(function (u) { box.appendChild(unitEl(u, true, u.entry.type === 'live' ? liveHits : null)); });
    content.appendChild(box);
  }

  function render() {
    var q = search.value.trim();
    document.body.classList.toggle('searching', q.length > 0);
    if (q.length >= 2) renderSearch(q);
    else if (activeType === 'epg') renderEpg();
    else renderType(activeType);
  }

  // ---------- Programmübersicht (EPG) ----------

  // Ein Eintrag je Sender mit Programmdaten; bei Dubletten (HEVC/HD) die normale Fassung.
  // Reihenfolge wie im Editor festgelegt, übrige Sender danach wie in der Playlist.
  function epgChannels() {
    var best = new Map();
    var order = [];
    byType.live.forEach(function (e) {
      if (!e.tvgId || !IPTV.programmes(e.tvgId).length) return;
      var cur = best.get(e.tvgId);
      if (!cur) { best.set(e.tvgId, e); order.push(e.tvgId); }
      else if (IPTV.isHevc(cur) && !IPTV.isHevc(e)) best.set(e.tvgId, e);
    });
    var rank = new Map();
    IPTV.epgOrder().forEach(function (id, i) { rank.set(id, i); });
    var pos = function (id, i) { return rank.has(id) ? rank.get(id) : rank.size + i; };
    order = order.map(function (id, i) { return { id: id, p: pos(id, i) }; })
      .sort(function (a, b) { return a.p - b.p; })
      .map(function (x) { return x.id; });
    return order.map(function (id) { return best.get(id); });
  }

  function dayLabel(sec) {
    var d = new Date(sec * 1000);
    var today = new Date();
    var tomorrow = new Date(Date.now() + 86400000);
    if (d.toDateString() === today.toDateString()) return 'Heute';
    if (d.toDateString() === tomorrow.toDateString()) return 'Morgen';
    return d.toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'numeric' });
  }

  function progressEl(id) {
    var bar = document.createElement('span');
    bar.className = 'prog';
    bar.setAttribute('data-prog', id);
    var fill = document.createElement('span');
    bar.appendChild(fill);
    updateProgress(bar);
    return bar;
  }

  function updateProgress(bar) {
    var nn = IPTV.nowNext(bar.getAttribute('data-prog'));
    var pct = 0;
    if (nn) pct = Math.min(100, Math.max(0, (Date.now() / 1000 - nn.now[0]) / (nn.now[1] - nn.now[0]) * 100));
    bar.firstChild.style.width = pct.toFixed(1) + '%';
    bar.hidden = !nn;
  }
  setInterval(function () { document.querySelectorAll('[data-prog]').forEach(updateProgress); }, 60000);

  function renderEpg() {
    content.textContent = '';
    var chans = epgChannels();
    if (!chans.length) return status('Für diese Liste gibt es keine Programmdaten.');
    var frag = document.createDocumentFragment();
    chans.forEach(function (e) {
      var d = document.createElement('details');
      d.className = 'group epgch';
      var s = document.createElement('summary');
      s.appendChild(IPTV.logoEl(e.logo, 'logo'));
      var text = document.createElement('span');
      text.className = 'gname text';
      var name = document.createElement('span');
      name.className = 'name';
      name.textContent = e.name;
      text.appendChild(name);
      text.appendChild(IPTV.epgEl(e.tvgId, 'meta now'));
      text.appendChild(progressEl(e.tvgId));
      s.appendChild(text);
      d.appendChild(s);
      var box = document.createElement('div');
      box.className = 'schedule';
      d.appendChild(box);
      d.fill = function () {
        if (box.firstChild) return;
        var watch = itemEl(e, false, chans);
        watch.classList.add('watch');
        watch.querySelector('.name').textContent = '▶ Jetzt ansehen';
        box.appendChild(watch);
        var lastDay = '';
        var now = Date.now() / 1000;
        IPTV.programmes(e.tvgId).forEach(function (p) {
          var day = dayLabel(p[0]);
          if (day !== lastDay) {
            var h = document.createElement('div');
            h.className = 'day';
            h.textContent = day;
            box.appendChild(h);
            lastDay = day;
          }
          var row = document.createElement('div');
          row.className = 'prow' + (p[0] <= now && p[1] > now ? ' live' : '');
          var t = document.createElement('span');
          t.className = 'ptime';
          t.textContent = IPTV.hhmm(p[0]);
          var ti = document.createElement('span');
          ti.className = 'ptitle';
          ti.textContent = p[2];
          row.appendChild(t);
          row.appendChild(ti);
          box.appendChild(row);
        });
      };
      d.addEventListener('toggle', function () { if (d.open) d.fill(); });
      frag.appendChild(d);
    });
    content.appendChild(frag);
  }

  // ---------- Favoriten (Filme und Serien, je Gerät gespeichert) ----------

  var FAV_STORE = 'iptv-fav';
  var favs = { movie: [], series: [] };
  try {
    var savedFavs = JSON.parse(localStorage.getItem(FAV_STORE));
    if (savedFavs) favs = { movie: savedFavs.movie || [], series: savedFavs.series || [] };
  } catch (err) { /* privat-Modus */ }

  // Film: gleiches Werk in allen Sprachen; Serie: Gruppe = Serientitel
  function favKey(e) { return e.type === 'series' ? e.group : (e.work || e.name); }
  function isFav(e) { return !!favs[e.type] && favs[e.type].indexOf(favKey(e)) >= 0; }

  function favEl(e) {
    var star = document.createElement('span');
    star.className = 'fav' + (isFav(e) ? ' on' : '');
    star.textContent = isFav(e) ? '★' : '☆';
    star.title = 'Favorit an/aus';
    star.setAttribute('role', 'button');
    star.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      toggleFav(e);
    });
    return star;
  }

  function favHint() {
    var p = document.createElement('p');
    p.className = 'status small favhint';
    p.textContent = IPTV.isTv ? 'Tipp: OK-Taste lange drücken = Favorit ★ an/aus'
      : 'Tipp: ☆ antippen = Favorit, ★ antippen = wieder entfernen';
    return p;
  }

  function favMovies() {
    var byKey = new Map();
    units(byType.movie).forEach(function (u) { byKey.set(favKey(u.entry), u); });
    return favs.movie.map(function (k) { return byKey.get(k); }).filter(Boolean);
  }

  function favGroup(favUnits) {
    var d = document.createElement('details');
    d.className = 'group favgroup';
    var s = document.createElement('summary');
    var title = document.createElement('span');
    title.className = 'gname';
    title.textContent = '★ Meine Favoriten';
    var count = document.createElement('span');
    count.className = 'gcount';
    count.textContent = favUnits.length;
    s.appendChild(title);
    s.appendChild(count);
    d.appendChild(s);
    var box = document.createElement('div');
    box.className = 'list';
    favUnits.forEach(function (u) { box.appendChild(unitEl(u, true)); });
    d.appendChild(box);
    d.fill = function () {};
    d.open = true;
    return d;
  }

  function favSeriesFirst(groups) {
    var out = new Map();
    favs.series.forEach(function (k) { if (groups.has(k)) out.set(k, groups.get(k)); });
    groups.forEach(function (items, k) { if (!out.has(k)) out.set(k, items); });
    return out;
  }

  function toggleFav(e) {
    var l = favs[e.type];
    if (!l) return;
    var k = favKey(e);
    var i = l.indexOf(k);
    if (i >= 0) l.splice(i, 1); else l.unshift(k);
    try { localStorage.setItem(FAV_STORE, JSON.stringify(favs)); } catch (err) { /* privat-Modus */ }
    toast(i >= 0 ? '☆ Aus den Favoriten entfernt' : '★ Zu den Favoriten hinzugefügt');
    // Neu zeichnen, offene Gruppen und Fokus behalten
    var focusUrl = document.activeElement && document.activeElement._fav ? e.url : null;
    refresh(all);
    var target = null;
    if (focusUrl) {
      target = Array.prototype.find.call(content.querySelectorAll('.item, summary'), function (n) {
        return n._fav && (n._fav.url === focusUrl || (e.type === 'series' && n.tagName === 'SUMMARY' && favKey(n._fav) === k));
      });
    }
    if (target && window.TV) TV.focusFirst(null, target);
  }

  // Der Player der App meldet beim Schließen geänderte Favoriten: { movie: {Schlüssel: true|false}, series: {…} }
  window.IPTV.applyNativeFavs = function (changes) {
    var n = 0;
    ['movie', 'series'].forEach(function (type) {
      var c = (changes && changes[type]) || {};
      Object.keys(c).forEach(function (k) {
        var i = favs[type].indexOf(k);
        if (c[k] && i < 0) { favs[type].unshift(k); n++; }
        if (!c[k] && i >= 0) { favs[type].splice(i, 1); n++; }
      });
    });
    if (!n) return;
    try { localStorage.setItem(FAV_STORE, JSON.stringify(favs)); } catch (err) { /* privat-Modus */ }
    refresh(all);
  };

  var toastTimer;
  function toast(text) {
    var t = document.getElementById('toast');
    if (!t) {
      t = document.createElement('div');
      t.id = 'toast';
      t.className = 'toast';
      document.body.appendChild(t);
    }
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, 2500);
  }

  // Fernbedienung: OK kurz = öffnen, OK lange (0,7 s) = Favorit an/aus
  if (IPTV.isTv) {
    var press = null;
    document.addEventListener('keydown', function (ev) {
      if (ev.key !== 'Enter') return;
      var el = document.activeElement;
      if (!el || !el._fav) return;
      ev.preventDefault();
      if (press) return;
      press = { el: el, done: false };
      press.timer = setTimeout(function () { press.done = true; toggleFav(el._fav); }, 700);
    }, true);
    document.addEventListener('keyup', function (ev) {
      if (ev.key !== 'Enter' || !press) return;
      var p = press;
      press = null;
      clearTimeout(p.timer);
      if (!p.done) p.el.click();
    }, true);
  }

  function setTab(type) {
    activeType = type;
    tabs.forEach(function (b) { b.setAttribute('aria-selected', String(b.dataset.type === type)); });
    try { localStorage.setItem('iptv-tab', type); } catch (err) { /* privat-Modus */ }
    if (search.value) search.value = '';
    render();
    window.scrollTo(0, 0);
  }

  tabs.forEach(function (b) {
    b.addEventListener('click', function () { setTab(b.dataset.type); });
  });

  var timer;
  search.addEventListener('input', function () {
    clearTimeout(timer);
    timer = setTimeout(render, 180);
  });
  search.addEventListener('keydown', function (ev) {
    if (ev.key === 'Enter') search.blur(); // Tastatur auf dem iPad schließen
  });

  function apply(entries) {
    all = entries;
    dataVersion++;
    byType = { live: [], movie: [], series: [] };
    entries.forEach(function (e) { byType[e.type].push(e); });
    // Zählen wie man es erwartet: Filme (nicht Sprachfassungen), Serien (nicht Folgen).
    var counts = {
      live: byType.live.length,
      movie: units(byType.movie).length,
      series: groupsOf(byType.series).size,
      epg: epgChannels().length
    };
    tabCounts = counts;
    tabs.forEach(function (b) {
      b.querySelector('.count').textContent = counts[b.dataset.type];
      b.hidden = b.dataset.type === 'epg' && !counts.epg; // ohne EPG-Daten kein Programm-Reiter
    });
  }

  // Neue Playlist im Hintergrund: Ansicht neu zeichnen, offene Gruppen bleiben offen.
  function refresh(entries) {
    var open = new Set();
    content.querySelectorAll('details[open] .gname').forEach(function (n) { open.add(n.textContent); });
    var y = window.scrollY;
    // Fokus merken (Fernbedienung), damit er nach dem Neuzeichnen nicht an den Seitenanfang springt
    var a = document.activeElement;
    var focusUrl = a && a._play ? a._play.entry.url : null;
    var focusGroup = a && a.tagName === 'SUMMARY' ? a.querySelector('.gname').textContent : null;
    apply(entries);
    render();
    content.querySelectorAll('details').forEach(function (d) {
      if (open.has(d.querySelector('.gname').textContent)) { d.open = true; d.fill(); }
    });
    window.scrollTo(0, y);
    if (!IPTV.isTv || (!focusUrl && !focusGroup)) return;
    var hit = Array.prototype.find.call(content.querySelectorAll('.item, summary'), function (n) {
      return focusUrl ? n._play && n._play.entry.url === focusUrl
        : n.tagName === 'SUMMARY' && n.querySelector('.gname').textContent === focusGroup;
    });
    if (hit) TV.focusFirst(null, hit);
  }

  // Zuletzt geöffneten Eintrag merken, damit der Fokus nach "Zurück" aus dem Player dort steht.
  content.addEventListener('click', function (ev) {
    var a = ev.target.closest('.item');
    if (!a) return;
    var d = a.closest('details');
    try {
      sessionStorage.setItem('iptv-last', JSON.stringify({
        href: a.getAttribute('href'),
        group: d ? d.querySelector('.gname').textContent : null
      }));
    } catch (err) { /* ignorieren */ }
  });

  function restoreFocus() {
    if (!IPTV.isTv) return;
    var last = null;
    try { last = JSON.parse(sessionStorage.getItem('iptv-last')); } catch (err) { /* ignorieren */ }
    if (last && last.group) {
      content.querySelectorAll('details').forEach(function (d) {
        if (d.querySelector('.gname').textContent === last.group) { d.open = true; d.fill(); }
      });
      var hit = Array.prototype.find.call(content.querySelectorAll('.item'), function (a) {
        return a.getAttribute('href') === last.href;
      });
      if (hit) return TV.focusFirst(null, hit);
    }
    TV.focusFirst('.tab[aria-selected="true"]');
  }

  function start() {
    status('Playlist wird geladen …');
    IPTV.load().then(function (entries) {
      apply(entries);
      var saved = null;
      try { saved = localStorage.getItem('iptv-tab'); } catch (err) { /* ignorieren */ }
      // Gespeicherten Tab nehmen, sonst den ersten mit Inhalt.
      var type = saved && tabCounts[saved] ? saved
        : ['live', 'movie', 'series'].filter(function (t) { return byType[t].length; })[0] || 'live';
      setTab(type);
      restoreFocus();
    }).catch(function (err) {
      status('Playlist konnte nicht geladen werden: ' + err.message, true);
    });
  }

  // Player der App: ganze Playlist als Baum (js/tree.js), mit Favoriten dieses Geräts
  var pageId = Date.now();   // neu geladene Seite = neuer Baum (die App merkt sich den alten)
  var dataVersion = 0;
  var tree = null;
  IPTV.setTreeProvider(function () {
    var version = pageId + ':' + dataVersion + ':' + Math.floor(Date.now() / 3600000) + ':' + favs.movie.length + ':' + favs.series.length;
    if (tree && tree.version === version) return tree;
    var t = IPTV.buildTree(all, {
      favKey: favKey, isFav: isFav, favMovies: favMovies, favSeriesFirst: favSeriesFirst,
      isFavSeries: function (name) { return favs.series.indexOf(name) >= 0; }
    });
    tree = {
      version: version,
      build: function () { return { n: 'Übersicht', c: t.root }; },
      pathOf: function (e) { return t.paths.get(e) || null; }
    };
    return tree;
  });

  // Ältere App-Versionen: nur die Live-Gruppen
  IPTV.setLiveGroups(function () {
    var out = [];
    groupsOf(byType.live).forEach(function (items, name) { out.push({ name: name, items: items }); });
    return out;
  });

  IPTV.watch(refresh);
  start();
})();
