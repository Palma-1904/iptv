// Seniorenversion: nur die in config.js ausgewählten Sender, große Kacheln.
(function () {
  'use strict';

  var box = document.getElementById('channels');

  // Vergleichsschlüssel: ignoriert Länderpräfixe, Qualitätszusätze und Sonderzeichen.
  // "DE: Das Erste HD" -> "daserste"
  function key(s) {
    return IPTV.normalize(s)
      .replace(/^\s*(de|ger|germany|deutschland)\s*[:|\-]\s*/, '')
      .replace(/\b(full ?hd|fhd|uhd|hd|sd|4k|hevc|h\.?265|h\.?264|50fps|backup)\b/g, '')
      .replace(/[^a-z0-9]+/g, '');
  }

  function status(text, retry) {
    box.textContent = '';
    var p = document.createElement('p');
    p.className = 'status';
    p.textContent = text;
    box.appendChild(p);
    if (retry) {
      var b = document.createElement('button');
      b.className = 'retry';
      b.textContent = 'Nochmal versuchen';
      b.onclick = start;
      box.appendChild(b);
    }
  }

  function findEntry(index, wanted) {
    var k = key(wanted);
    return index.get(k) || null;
  }

  function start() {
    status('Sender werden geladen …');
    IPTV.load().then(show).catch(function (err) {
      console.error(err);
      status('Die Sender konnten nicht geladen werden. Bitte Internetverbindung prüfen.', true);
    });
  }

  var allEntries = [];

  function show(entries) {
    allEntries = entries;
    dataVersion++;
    // Index über Name, tvg-name und tvg-id; Live-Sender haben Vorrang, erster Treffer gewinnt.
    var index = new Map();
    var ordered = entries.filter(function (e) { return e.type === 'live'; })
      .concat(entries.filter(function (e) { return e.type !== 'live'; }));
    ordered.forEach(function (e) {
      [e.name, e.tvgName, e.tvgId].forEach(function (v) {
        var k = v && key(v);
        if (k && !index.has(k)) index.set(k, e);
      });
    });

    // Eigene Playlist des Geräts (aus dem Editor): alle Live-Sender in deren Reihenfolge.
    if (IPTV.hasDeviceList()) {
      return render(entries.filter(function (e) { return e.type === 'live'; })
        .map(function (e) { return { label: e.name, entry: e }; }));
    }

    var list = (IPTV.config.senioren || []).map(function (s) {
      var label = typeof s === 'string' ? s : s.label;
      var match = typeof s === 'string' ? s : (s.match || s.label);
      return { label: label, entry: findEntry(index, match) };
    });

    var missing = list.filter(function (x) { return !x.entry; }).map(function (x) { return x.label; });
    if (missing.length) console.warn('Nicht in der Playlist gefunden:', missing.join(', '));

    render(list.filter(function (x) { return x.entry; }));
  }

  // Player der App: ganze Playlist wie in der Hauptansicht (Gruppe › Live TV › Übersicht);
  // nur bei einer Senderauswahl aus config.js oben „Meine Sender“. Version ändert sich mit neuen
  // Daten (Hintergrund-Auffrischung) und stündlich.
  var seniorEntries = [];
  var seniorFound = [];
  var dataVersion = 0;
  var treeCache = null;
  IPTV.setTreeProvider(function () {
    if (!IPTV.buildTree) return null;   // alte senioren.html ohne js/tree.js: ältere Übergabe
    var version = 'sen:' + pageId + ':' + dataVersion + ':' + IPTV.epgStamp() + ':' + Math.floor(Date.now() / 3600000);
    if (treeCache && treeCache.version === version) return treeCache;
    var own = !IPTV.hasDeviceList() && seniorEntries.length;
    var t = IPTV.buildTree(allEntries, own
      ? { first: [{ n: 'Meine Sender', c: seniorEntries.map(function (e) { return IPTV.nativeLeaf(e); }) }] }
      : {});
    seniorEntries.forEach(function (e, i) {
      t.paths.set(e, own ? [0, i] : t.paths.get(seniorFound[i].entry));
    });
    treeCache = {
      version: version,
      build: function () { return { n: 'Übersicht', c: t.root }; },
      pathOf: function (e) { return t.paths.get(e) || null; },
      pathOfUrl: function (url) {
        // Zugangsdaten in der Adresse ignorieren (Playlist kann auf anderen Zugang umgestellt sein)
        var norm = function (u) { return String(u || '').replace(/^https?:\/\/[^/]+\/(live|movie|series)\/[^/]+\/[^/]+\//, '$1/'); };
        var hit = allEntries.find(function (e) { return norm(e.url) === norm(url); });
        return hit ? t.paths.get(hit) || null : null;
      }
    };
    return treeCache;
  });
  var pageId = Date.now();

  function render(found) {
    seniorEntries = [];
    seniorFound = [];
    if (!found.length) {
      status('Keine Sender gefunden.');
      if (appPlayer && !lastAuto) setTimeout(autoStart, 300);   // z. B. reine Serienliste
      return;
    }

    box.textContent = '';
    var frag = document.createDocumentFragment();
    // Für den Player der App: Sender mit dem hier angezeigten Namen
    var entries = found.map(function (x) { return Object.assign({}, x.entry, { name: x.label }); });
    seniorEntries = entries;
    seniorFound = found;
    IPTV.setLiveGroups(function () { return [{ name: 'Sender', items: entries }]; });
    found.forEach(function (x, i) {
      var a = document.createElement('a');
      a.className = 'channel';
      a.href = IPTV.playerHref(x.entry);
      IPTV.bindPlay(a, entries[i], entries);
      a.appendChild(IPTV.logoEl(x.entry.logo, 'logo'));
      var text = document.createElement('span');
      text.className = 'text';
      var name = document.createElement('span');
      name.className = 'name';
      name.textContent = x.label;
      text.appendChild(name);
      if (x.entry.tvgId) text.appendChild(IPTV.epgEl(x.entry.tvgId, 'now'));
      a.appendChild(text);
      frag.appendChild(a);
    });
    box.appendChild(frag);

    // Fernbedienung: Fokus auf den zuletzt gewählten Sender, sonst auf den ersten.
    var last = null;
    try { last = sessionStorage.getItem('iptv-senior-last'); } catch (err) { /* ignorieren */ }
    var hit = Array.prototype.find.call(box.querySelectorAll('.channel'), function (a) {
      return a.getAttribute('href') === last;
    });
    TV.focusFirst('.channel', hit);
    if (appPlayer && !lastAuto) setTimeout(autoStart, 300);
  }

  box.addEventListener('click', function (ev) {
    var a = ev.target.closest('.channel');
    if (!a) return;
    try { sessionStorage.setItem('iptv-senior-last', a.getAttribute('href')); } catch (err) { /* ignorieren */ }
  });

  // ---------- Fire-TV-App: Seniorenansicht nur als Player ----------
  // Die App startet sofort mit dem zuletzt gesehenen Sender (beim ersten Mal mit dem ersten).
  // Die Kacheln bleiben als Rückfall, falls der Start nicht klappt.
  var N = window.IPTVNative;
  var appPlayer = IPTV.platform === 'app' && N && typeof N.lastSeniorUrl === 'function' && typeof N.playPath === 'function';
  var lastAuto = 0;

  function autoStart() {
    if (!allEntries.length) return;
    // Schutz gegen Endlosschleife, wenn der Player sofort wieder zugeht
    if (Date.now() - lastAuto < 15000) {
      document.documentElement.classList.remove('appplayer');
      return;
    }
    lastAuto = Date.now();
    var tiles = box.querySelectorAll('.channel');
    var tp = IPTV.nativeTree();
    if (!tp) {   // ältere Übergabe ohne Baum: nur mit Sendern möglich
      if (tiles.length) tiles[0].click(); else document.documentElement.classList.remove('appplayer');
      return;
    }
    // Zuletzt gesehen (Sender, Film oder Folge), sonst erster Sender, sonst nur die Übersicht
    var path = tp.pathOfUrl(N.lastSeniorUrl());
    if (!path && tiles.length) path = tp.pathOf(tiles[0]._play.entry);
    IPTV.openNativePath(path || []);
  }

  if (appPlayer) {
    document.documentElement.classList.add('appplayer');
    // Die App ruft das beim erneuten Öffnen auf (z. B. nach der Home-Taste)
    IPTV.onAppResume = autoStart;
  }

  IPTV.watch(show);
  start();
})();
