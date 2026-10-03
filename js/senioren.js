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

  function show(entries) {
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

  function render(found) {
    if (!found.length) return status('Keine Sender gefunden.');

    box.textContent = '';
    var frag = document.createDocumentFragment();
    found.forEach(function (x) {
      var a = document.createElement('a');
      a.className = 'channel';
      a.href = IPTV.playerHref(x.entry);
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
  }

  box.addEventListener('click', function (ev) {
    var a = ev.target.closest('.channel');
    if (!a) return;
    try { sessionStorage.setItem('iptv-senior-last', a.getAttribute('href')); } catch (err) { /* ignorieren */ }
  });

  IPTV.watch(show);
  start();
})();
