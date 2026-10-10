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

  // ---------- Browser (iPad, iPhone, Mac): links Kategorien, rechts Inhalt ----------
  // Fernseher und App behalten die aufklappbaren Gruppen (Fernbedienung).
  var SPLIT = !IPTV.isTv && !window.IPTVNative;
  document.body.classList.toggle('split', SPLIT);
  // Höhe der Kopfzeile für die mitlaufende Kategorienspalte
  function measureHeader() {
    var top = document.querySelector('.top');
    if (top) document.documentElement.style.setProperty('--hdr', (top.offsetHeight + 8) + 'px');
  }
  if (SPLIT) { window.addEventListener('resize', measureHeader); setTimeout(measureHeader, 0); }
  var catSel = {};          // gewählte Kategorie je Reiter
  var openSeries = null;    // geöffnete Serie (Name) im rechten Bereich
  try { catSel = JSON.parse(localStorage.getItem('iptv-cat') || '{}') || {}; } catch (err) { /* privat-Modus */ }

  function recentIdx(u, recent) {
    var best = 99;
    (u.variants || [u.entry]).forEach(function (v) { var i = recent.indexOf(v); if (i >= 0 && i < best) best = i; });
    return best;
  }

  // Kategorien eines Reiters: [{ name, count, items|units|series }]
  function catsOf(type) {
    var out = [];
    var recent = recentOf(type).slice(0, 12);
    if (type === 'live') {
      if (recent.length) out.push({ name: '🕘 Zuletzt gesehen', count: recent.length, items: recent });
      groupsOf(byType.live).forEach(function (items, name) { out.push({ name: name, count: items.length, items: items }); });
    } else if (type === 'movie') {
      if (recent.length) {
        var ru = [], seenW = {};
        units(byType.movie).forEach(function (u) {
          (u.variants || [u.entry]).forEach(function (v) { if (recent.indexOf(v) >= 0 && !seenW[favKey(u.entry)]) { seenW[favKey(u.entry)] = 1; ru.push(u); } });
        });
        ru.sort(function (a, b) { return recentIdx(a, recent) - recentIdx(b, recent); });
        out.push({ name: '🕘 Zuletzt gesehen', count: ru.length, units: ru });
      }
      var fu = favMovies();
      if (fu.length) out.push({ name: '★ Meine Favoriten', count: fu.length, units: fu });
      groupsOf(byType.movie).forEach(function (items, name) {
        var u = units(items);
        out.push({ name: name, count: u.length, units: u });
      });
    } else {
      // Serien: Kategorie aus dem Editor (x-cat), darin die Serien (group-title)
      var byCat = new Map();
      var favSeries = new Map();
      groupsOf(byType.series).forEach(function (eps, name) {
        var cat = eps[0].cat || 'Serien';
        if (!byCat.has(cat)) byCat.set(cat, new Map());
        byCat.get(cat).set(name, eps);
        if (favs.series.indexOf(name) >= 0) favSeries.set(name, eps);
      });
      if (recent.length) {
        var rs = new Map(), all = groupsOf(byType.series);
        recent.forEach(function (e) { if (!rs.has(e.group) && all.has(e.group)) rs.set(e.group, all.get(e.group)); });
        if (rs.size) out.push({ name: '🕘 Zuletzt gesehen', count: rs.size, series: rs });
      }
      if (favSeries.size) out.push({ name: '★ Meine Favoriten', count: favSeries.size, series: favSeries });
      byCat.forEach(function (m, cat) { out.push({ name: cat, count: m.size, series: m }); });
    }
    return out;
  }

  function saveCat() {
    try { localStorage.setItem('iptv-cat', JSON.stringify(catSel)); } catch (err) { /* privat-Modus */ }
  }

  // ---------- Zuletzt gesehen und Schnell-Leiste (Outplayer & Co. öffnen eine eigene App) ----------
  var RECENT_KEY = 'iptv-recent', LAST_KEY = 'iptv-lastplay';
  var byUrl = new Map();
  function readJson(store, key, def) {
    try { return JSON.parse(store.getItem(key) || 'null') || def; } catch (err) { return def; }
  }
  function writeJson(store, key, v) {
    try { store.setItem(key, JSON.stringify(v)); } catch (err) { /* privat-Modus */ }
  }
  function remember(e, list) {
    var r = readJson(localStorage, RECENT_KEY, []).filter(function (x) { return x.u !== e.url; });
    r.unshift({ u: e.url, t: e.type });
    writeJson(localStorage, RECENT_KEY, r.slice(0, 36));
    writeJson(localStorage, LAST_KEY, {
      u: e.url, t: Date.now(),
      l: (list || [e]).length <= 600 ? (list || [e]).map(function (x) { return x.url; }) : [e.url]
    });
  }
  function recentOf(type) {
    return readJson(localStorage, RECENT_KEY, []).filter(function (x) { return x.t === type; })
      .map(function (x) { return byUrl.get(x.u); }).filter(Boolean);
  }
  // Jeder Start eines Senders/Films/einer Folge im Browser: merken (Zuletzt gesehen, Schnell-Leiste)
  if (SPLIT) {
    document.addEventListener('click', function (ev) {
      var a = ev.target.closest ? ev.target.closest('a') : null;
      if (!a || !a._play || ev.target.closest('.fav')) return;
      remember(a._play.entry, a._play.list);
      checkPlayerApp(a.getAttribute('href'));
      setTimeout(render, 500);   // beim Zurückkommen ist die Leiste schon da
    }, true);
  }
  function play(e, list) {
    remember(e, list);
    var href = IPTV.playerHref(e);
    checkPlayerApp(href);
    location.href = href;
    setTimeout(render, 500);
  }

  // iPad/iPhone: Ist Outplayer installiert? Eine Webseite darf das nicht direkt fragen – aber geht die App auf,
  // verschwindet die Seite in den Hintergrund. Bleibt sie 4 s vorne, fehlt Outplayer wohl → App Store anbieten.
  // Einmal erfolgreich geöffnet: auf diesem Gerät nie wieder prüfen.
  var OUTPLAYER_STORE = 'https://apps.apple.com/de/app/outplayer/id1449923287';
  function checkPlayerApp(href) {
    if (!/^outplayer:/i.test(href || '')) return;
    try { if (localStorage.getItem('iptv-outplayer-ok')) return; } catch (err) { /* egal */ }
    var left = false;
    function gone() {
      if (document.visibilityState !== 'hidden') return;
      left = true;
      try { localStorage.setItem('iptv-outplayer-ok', '1'); } catch (err) { /* egal */ }
    }
    document.addEventListener('visibilitychange', gone);
    window.addEventListener('pagehide', gone);
    setTimeout(function () {
      document.removeEventListener('visibilitychange', gone);
      window.removeEventListener('pagehide', gone);
      if (!left && document.visibilityState === 'visible') missingPlayer();
    }, 4000);
  }
  function missingPlayer() {
    if (document.querySelector('.lang-overlay.noplayer')) return;
    var ov = document.createElement('div');
    ov.className = 'lang-overlay noplayer';
    ov.setAttribute('role', 'dialog');
    ov.setAttribute('aria-modal', 'true');
    var box = document.createElement('div');
    box.className = 'lang-box';
    var h = document.createElement('h2');
    h.textContent = 'Startet nichts?';
    var p = document.createElement('p');
    p.textContent = 'Zum Abspielen braucht dieses Gerät die kostenlose App „Outplayer“. Einmal installieren, dann zurück hierher und den Sender noch einmal antippen.';
    var store = document.createElement('a');
    store.className = 'lang-btn';
    store.href = OUTPLAYER_STORE;
    store.textContent = 'Outplayer im App Store laden';
    var have = document.createElement('button');
    have.type = 'button';
    have.className = 'lang-cancel';
    have.textContent = 'Outplayer ist schon installiert';
    var close = document.createElement('button');
    close.type = 'button';
    close.className = 'lang-cancel';
    close.textContent = 'Schließen';
    box.appendChild(h);
    box.appendChild(p);
    box.appendChild(store);
    box.appendChild(have);
    box.appendChild(close);
    ov.appendChild(box);
    function shut() { ov.remove(); }
    close.addEventListener('click', shut);
    have.addEventListener('click', function () {
      try { localStorage.setItem('iptv-outplayer-ok', '1'); } catch (err) { /* egal */ }
      shut();
    });
    ov.addEventListener('click', function (ev) { if (ev.target === ov) shut(); });
    document.body.appendChild(ov);
  }
  // Leiste oben: „Zuletzt: ARD  ◀ · ▶ nochmal · ▶“ – mit einem Tipp weiterzappen
  function quickBar() {
    var last = readJson(localStorage, LAST_KEY, null);
    if (!last || Date.now() - last.t > 12 * 3600 * 1000) return null;
    var e = byUrl.get(last.u);
    if (!e) return null;
    var list = (last.l || []).map(function (u) { return byUrl.get(u); }).filter(Boolean);
    var i = list.indexOf(e);
    var bar = document.createElement('div');
    bar.className = 'quick';
    var t = document.createElement('span');
    t.className = 'qname';
    t.innerHTML = 'Zuletzt: <b></b>';
    t.querySelector('b').textContent = e.name;
    bar.appendChild(t);
    function btn(label, cls, target) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'qbtn ' + cls;
      b.textContent = label;
      b.addEventListener('click', function () { play(target, list.length ? list : [target]); });
      bar.appendChild(b);
    }
    var live = e.type === 'live' && list.length > 1 && i >= 0;
    if (live) btn('◀', 'prev', list[(i - 1 + list.length) % list.length]);
    btn(e.type === 'live' ? '▶ nochmal' : '▶ weiter', 'again', e);
    if (live) btn('▶▶', 'next', list[(i + 1) % list.length]);
    else if (e.type === 'series' && i >= 0 && i + 1 < list.length) btn('nächste Folge ▶', 'next', list[i + 1]);
    var x = document.createElement('button');
    x.type = 'button';
    x.className = 'qbtn qclose';
    x.textContent = '✕';
    x.title = 'Leiste ausblenden';
    x.addEventListener('click', function () { localStorage.removeItem(LAST_KEY); render(); });
    bar.appendChild(x);
    // die ersten Male: wie man aus Outplayer zurückkommt
    var hints = +(localStorage.getItem('iptv-backhint') || 0);
    if (IPTV.platform === 'ios' && hints < 5) {
      var h = document.createElement('div');
      h.className = 'qhint';
      h.textContent = 'Zurück aus Outplayer: oben links auf „◀“ tippen – die Liste bleibt, wo sie war.';
      bar.appendChild(h);
      try { localStorage.setItem('iptv-backhint', String(hints + 1)); } catch (err) { /* egal */ }
    }
    return bar;
  }

  function renderSplit(type) {
    content.textContent = '';
    var cats = catsOf(type);
    if (!cats.length) return status('Keine Einträge in dieser Kategorie.');
    // gewählte Kategorie, sonst die erste echte (nicht „Zuletzt gesehen“) – und merken, damit die Ansicht
    // nach dem Starten eines Senders nicht wegspringt
    var cur = cats.filter(function (c) { return c.name === catSel[type]; })[0]
      || cats.filter(function (c) { return c.name.indexOf('🕘') !== 0; })[0] || cats[0];
    if (catSel[type] !== cur.name) { catSel[type] = cur.name; saveCat(); }
    var qb = quickBar();
    if (qb) content.appendChild(qb);
    // Kategorienspalte unter der Leiste mitlaufen lassen
    setTimeout(function () {
      document.documentElement.style.setProperty('--qh', qb && qb.isConnected ? (qb.offsetHeight + 12) + 'px' : '0px');
    }, 0);
    var wrap = document.createElement('div');
    wrap.className = 'splitwrap';
    var side = document.createElement('nav');
    side.className = 'cats';
    cats.forEach(function (c) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'cat' + (c === cur ? ' sel' : '');
      var n = document.createElement('span');
      n.className = 'cname';
      n.textContent = c.name;
      var k = document.createElement('span');
      k.className = 'ccount';
      k.textContent = c.count;
      b.appendChild(n);
      b.appendChild(k);
      b.addEventListener('click', function () {
        catSel[type] = c.name;
        openSeries = null;
        saveCat();
        render();
        var pane = content.querySelector('.pane');
        if (pane) pane.scrollIntoView({ block: 'start', behavior: 'smooth' });
      });
      side.appendChild(b);
    });
    var pane = document.createElement('section');
    pane.className = 'pane';
    var h = document.createElement('h2');
    h.className = 'panehead';
    h.textContent = cur.name;
    pane.appendChild(h);
    if (type === 'live') pane.appendChild(liveList(cur.items));
    else if (type === 'movie') pane.appendChild(movieGrid(cur.units));
    else if (openSeries && cur.series.has(openSeries)) pane.appendChild(seriesDetail(openSeries, cur.series.get(openSeries)));
    else pane.appendChild(seriesGrid(cur.series));
    wrap.appendChild(side);
    wrap.appendChild(pane);
    content.appendChild(wrap);
    // gewählte Kategorie in der (waagerechten) Leiste sichtbar machen
    var sel = side.querySelector('.sel');
    if (sel && sel.scrollIntoView && side.scrollWidth > side.clientWidth) sel.scrollIntoView({ inline: 'center', block: 'nearest' });
  }

  // Sender mit „jetzt“, „danach“ und Fortschritt
  function liveList(items) {
    var box = document.createElement('div');
    box.className = 'list live';
    items.forEach(function (e) {
      var a = itemEl(e, false, items);
      if (e.tvgId) {
        var nn = IPTV.nowNext(e.tvgId);
        var text = a.querySelector('.text');
        if (nn && nn.next) {
          var nx = document.createElement('span');
          nx.className = 'meta next';
          nx.textContent = 'danach ' + IPTV.hhmm(nn.next[0]) + '  ' + nn.next[2];
          text.appendChild(nx);
        }
        text.appendChild(progressEl(e.tvgId));
      }
      box.appendChild(a);
    });
    return box;
  }

  // Große Gruppen (z. B. 6000 Filme) stückweise zeigen
  var PAGE = 200;
  function paged(list, make) {
    var wrap = document.createElement('div');
    var box = document.createElement('div');
    box.className = 'tiles';
    wrap.appendChild(box);
    var shown = 0;
    var more = document.createElement('button');
    more.type = 'button';
    more.className = 'retry more';
    function step() {
      var f = document.createDocumentFragment();
      list.slice(shown, shown + PAGE).forEach(function (x) { f.appendChild(make(x)); });
      box.appendChild(f);
      shown = Math.min(list.length, shown + PAGE);
      more.hidden = shown >= list.length;
      more.textContent = 'Weitere ' + Math.min(PAGE, list.length - shown) + ' anzeigen (noch ' + (list.length - shown) + ')';
    }
    more.addEventListener('click', step);
    wrap.appendChild(more);
    step();
    return wrap;
  }

  function movieGrid(us) {
    return paged(us, function (u) {
      var a = unitEl(u, false);
      a.classList.add('tile');
      return a;
    });
  }

  function seriesGrid(series) {
    var arr = [];
    series.forEach(function (eps, name) { arr.push([name, eps]); });
    return paged(arr, function (x) {
      var name = x[0], eps = x[1];
      var b = document.createElement('a');
      b.href = '#';
      b.className = 'item tile';
      b._fav = eps[0];
      b.appendChild(IPTV.logoEl(eps[0].logo, 'logo'));
      var text = document.createElement('span');
      text.className = 'text';
      var n = document.createElement('span');
      n.className = 'name';
      n.textContent = name;
      text.appendChild(n);
      var langs = seriesLangs(eps);
      var m = document.createElement('span');
      m.className = 'meta';
      m.textContent = (langs.length > 1 ? eps.filter(function (e) { return e.lang === langs[0]; }).length : eps.length)
        + ' Folgen' + (langs.length > 1 ? ' · ' + langs.join(' ') : '');
      text.appendChild(m);
      b.appendChild(text);
      b.appendChild(favEl(eps[0]));
      b.addEventListener('click', function (ev) {
        ev.preventDefault();
        openSeries = name;
        render();
        window.scrollTo(0, 0);
      });
      return b;
    });
  }

  function seriesDetail(name, eps) {
    var box = document.createElement('div');
    box.className = 'seriesdetail';
    var back = document.createElement('button');
    back.type = 'button';
    back.className = 'back';
    back.textContent = '‹ Alle Serien';
    back.addEventListener('click', function () { openSeries = null; render(); });
    var head = document.createElement('div');
    head.className = 'shead';
    head.appendChild(back);
    var t = document.createElement('strong');
    t.textContent = name;
    head.appendChild(t);
    head.appendChild(favEl(eps[0]));
    box.appendChild(head);
    var langs = seriesLangs(eps);
    var list = document.createElement('div');
    var current = langs[0];
    var bar = document.createElement('div');
    bar.className = 'langbar';
    function draw() {
      list.textContent = '';
      var shown = langs.length > 1 ? eps.filter(function (e) { return e.lang === current; }) : eps;
      var season = null, grid = null;
      shown.forEach(function (e) {
        var m = /\bS(\d{1,3})E\d/i.exec(e.name);
        var sn = m ? +m[1] : 0;
        if (!grid || sn !== season) {   // Staffel-Überschrift
          season = sn;
          if (sn) {
            var h = document.createElement('div');
            h.className = 'day';
            h.textContent = 'Staffel ' + sn;
            list.appendChild(h);
          }
          grid = document.createElement('div');
          grid.className = 'list';
          list.appendChild(grid);
        }
        var a = itemEl(e, false, shown);
        var label = e.name.indexOf(name) === 0 ? e.name.slice(name.length).replace(/^[\s–:-]+/, '') : e.name;
        a.querySelector('.name').textContent = label || e.name;
        grid.appendChild(a);
      });
      Array.prototype.forEach.call(bar.children, function (b) {
        b.setAttribute('aria-pressed', String(b.dataset.lang === current));
      });
    }
    if (langs.length > 1) {
      langs.forEach(function (l) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'langsel';
        b.dataset.lang = l;
        b.textContent = langName(l);
        b.addEventListener('click', function () { current = l; draw(); });
        bar.appendChild(b);
      });
      box.appendChild(bar);
    }
    box.appendChild(list);
    draw();
    return box;
  }

  function render() {
    var q = search.value.trim();
    document.body.classList.toggle('searching', q.length > 0);
    if (q.length >= 2) renderSearch(q);
    else if (activeType === 'epg') renderEpg();
    else if (SPLIT) renderSplit(activeType);
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
    // Film-Kennung früher mit Zugang ("abc123:movie:42"), jetzt ohne ("movie:42")
    favs.movie = favs.movie.map(function (k) { return String(k).replace(/^[a-z0-9]+:(movie|series):/, '$1:'); });
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
    openSeries = null;
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
    byUrl = new Map();
    entries.forEach(function (e) { byType[e.type].push(e); byUrl.set(e.url, e); });
    // Zählen wie man es erwartet: Filme (nicht Sprachfassungen), Serien (nicht Folgen).
    var counts = {
      live: byType.live.length,
      movie: units(byType.movie).length,
      series: groupsOf(byType.series).size,
      epg: epgChannels().length
    };
    if (SPLIT) counts.epg = 0;   // Browser: Programm steht bei jedem Sender, kein eigener Reiter
    tabCounts = counts;
    tabs.forEach(function (b) {
      b.querySelector('.count').textContent = counts[b.dataset.type];
      b.hidden = b.dataset.type === 'epg' && !counts.epg; // ohne EPG-Daten kein Programm-Reiter
    });
    if (activeType === 'epg' && !counts.epg) activeType = 'live';
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
    var version = pageId + ':' + dataVersion + ':' + IPTV.epgStamp() + ':' + Math.floor(Date.now() / 3600000) + ':' + favs.movie.length + ':' + favs.series.length;
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
