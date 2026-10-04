// Gemeinsame Hilfen für Haupt- und Seniorenansicht: Sprachfassungen, Gruppen und der Baum
// für den Player der Fire-TV-App (Live TV › Gruppen › Sender, Filme › Gruppen › Film (› Fassung),
// Serien › Serie (› Sprache) › Folgen, Suche). Pfad = Indizes bis zum Eintrag.
(function () {
  'use strict';

  // ---------- Sprachfassungen (vom Editor: x-work = gleiches Werk, x-lang = Sprache) ----------

  var LANG_NAMES = {
    DE: 'Deutsch', AT: 'Deutsch (AT)', CH: 'Deutsch (CH)', MULTI: 'Mehrsprachig', EN: 'Englisch',
    US: 'Englisch (US)', UK: 'Englisch (UK)', FR: 'Französisch', QFR: 'Französisch (CA)', ES: 'Spanisch',
    LAT: 'Spanisch (Lateinam.)', IT: 'Italienisch', NL: 'Niederländisch', PL: 'Polnisch', TR: 'Türkisch',
    GR: 'Griechisch', PT: 'Portugiesisch', BR: 'Portugiesisch (BR)', RU: 'Russisch', SE: 'Schwedisch',
    NO: 'Norwegisch', DK: 'Dänisch', FI: 'Finnisch', AL: 'Albanisch', EX: 'Ex-Jugoslawisch',
    EXYU: 'Ex-Jugoslawisch', BG: 'Bulgarisch', HU: 'Ungarisch', RO: 'Rumänisch', CZ: 'Tschechisch',
    IN: 'Indisch', AR: 'Arabisch', IR: 'Persisch', NF: 'Netflix (mehrsprachig)'
  };
  var PREFERRED = ['DE', 'AT', 'CH', 'MULTI'];

  // Fassungen aus dem Editor: "DE", "DE 4K", "DE 4K HDR" (normale Qualität zuerst)
  var QUALITY_RANK = { '': 0, '4K': 1, 'HDR': 2, '4K HDR': 3 };
  function langParts(l) {
    var p = String(l || '').split(' ');
    return { lang: p[0], q: p.slice(1).join(' ') };
  }
  function langRank(l) {
    var p = langParts(l);
    var i = PREFERRED.indexOf(p.lang);
    return (i < 0 ? PREFERRED.length : i) * 10 + (QUALITY_RANK[p.q] || 0);
  }
  function langName(l) {
    if (!l) return 'Standard';
    var p = langParts(l);
    return (LANG_NAMES[p.lang] || p.lang) + (p.q ? ' ' + p.q : '');
  }
  function byLang(a, b) { return langRank(a.lang) - langRank(b.lang); }

  // Filme mit gleicher x-work-Kennung werden ein Eintrag; Deutsch ist die Standardfassung.
  function units(list) {
    var out = [];
    var byWork = new Map();
    list.forEach(function (e) {
      if (e.type !== 'movie' || !e.work) { out.push({ entry: e }); return; }
      var u = byWork.get(e.work);
      if (!u) { u = { entry: e, variants: [] }; byWork.set(e.work, u); out.push(u); }
      u.variants.push(e);
    });
    out.forEach(function (u) {
      if (u.variants) { u.variants.sort(byLang); u.entry = u.variants[0]; }
    });
    return out;
  }

  // Sprachen einer Serie, Deutsch zuerst
  function seriesLangs(items) {
    var langs = [];
    items.forEach(function (e) { if (e.lang && langs.indexOf(e.lang) < 0) langs.push(e.lang); });
    return langs.sort(function (a, b) { return langRank(a) - langRank(b); });
  }

  // Gruppen nach Reihenfolge des ersten Auftretens in der M3U.
  function groupsOf(list) {
    var map = new Map();
    list.forEach(function (e) {
      if (!map.has(e.group)) map.set(e.group, []);
      map.get(e.group).push(e);
    });
    return map;
  }

  // ---------- Baum für den Player der App ----------

  // entries: alle Einträge der Playlist. opts (optional, Favoriten der Hauptansicht):
  //   favKey(e), isFav(e), favMovies() -> Film-Einheiten, favSeriesFirst(Map) -> Map, isFavSeries(name)
  //   first: Bereiche, die vor „Live TV“ stehen (z. B. „Meine Sender“ der Seniorenansicht)
  // Liefert { root: [Bereiche], paths: Map(Eintrag -> [Indizes]) }.
  function buildTree(entries, opts) {
    opts = opts || {};
    var leaf = IPTV.nativeLeaf;
    var paths = new Map();
    var root = (opts.first || []).slice();
    var byType = { live: [], movie: [], series: [] };
    entries.forEach(function (e) { if (byType[e.type]) byType[e.type].push(e); });
    var setPath = function (e, p) { if (!paths.has(e)) paths.set(e, p); };

    if (byType.live.length) {
      var li = root.length;
      var live = [];
      groupsOf(byType.live).forEach(function (items, name) {
        var gi = live.length;
        live.push({ n: name, c: items.map(function (e, i) { setPath(e, [li, gi, i]); return leaf(e); }) });
      });
      root.push({ n: 'Live TV', c: live });
    }

    if (byType.movie.length) {
      var ai = root.length;
      var movies = [];
      // Favoriten-Kennung für den Player: fk = Schlüssel, ft = Art, fv = ist Favorit
      var mark = function (o, e) {
        if (!opts.favKey) return o;
        o.fk = opts.favKey(e);
        o.ft = e.type;
        if (opts.isFav(e)) o.fv = 1;
        return o;
      };
      var unitNode = function (u, path) {
        if (!u.variants || u.variants.length < 2) {
          if (path) setPath(u.entry, path);
          return mark(leaf(u.entry), u.entry);
        }
        return mark({
          n: u.entry.name + '  ·  ' + u.variants.map(function (v) { return v.lang || '?'; }).join(' '),
          v: 1,
          c: u.variants.map(function (v, vi) {
            if (path) setPath(v, path.concat(vi));
            return leaf(v, langName(v.lang), u.entry.name + ' (' + langName(v.lang) + ')');
          })
        }, u.entry);
      };
      var favUnits = opts.favMovies ? opts.favMovies() : [];
      if (favUnits.length) movies.push({ n: '★ Meine Favoriten', c: favUnits.map(function (u) { return unitNode(u, null); }) });
      groupsOf(byType.movie).forEach(function (items, name) {
        var gi = movies.length;
        movies.push({ n: name, c: units(items).map(function (u, ui) { return unitNode(u, [ai, gi, ui]); }) });
      });
      root.push({ n: 'Filme', c: movies });
    }

    if (byType.series.length) {
      var si = root.length;
      var series = [];
      var episode = function (e, name) {
        var label = e.name.indexOf(name) === 0 ? e.name.slice(name.length).replace(/^[\s–:-]+/, '') : e.name;
        return leaf(e, label || e.name, e.name);
      };
      var groups = groupsOf(byType.series);
      if (opts.favSeriesFirst) groups = opts.favSeriesFirst(groups);
      groups.forEach(function (items, name) {
        var gi = series.length;
        var langs = seriesLangs(items);
        var fav = opts.favKey ? { fk: name, ft: 'series' } : {};
        if (opts.isFavSeries && opts.isFavSeries(name)) fav.fv = 1;
        if (langs.length > 1) {
          series.push(Object.assign({ n: name, c: langs.map(function (l, lj) {
            var eps = items.filter(function (e) { return e.lang === l; });
            return { n: langName(l), c: eps.map(function (e, ei) { setPath(e, [si, gi, lj, ei]); return episode(e, name); }) };
          }) }, fav));
        } else {
          series.push(Object.assign({ n: name, c: items.map(function (e, ei) { setPath(e, [si, gi, ei]); return episode(e, name); }) }, fav));
        }
      });
      root.push({ n: 'Serien', c: series });
    }

    root.push({ n: '🔍 Suche', s: 1 });
    return { root: root, paths: paths };
  }

  IPTV.lib = {
    LANG_NAMES: LANG_NAMES, langName: langName, langRank: langRank, byLang: byLang,
    units: units, seriesLangs: seriesLangs, groupsOf: groupsOf
  };
  IPTV.buildTree = buildTree;
})();
