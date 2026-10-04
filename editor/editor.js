// Playlist-Editor: Quellen (Xtream/M3U) durchsuchen, eigene Playlists mit Gruppen zusammenstellen.
// Alle Daten liegen über server.py in editor/data/.
'use strict';

const $ = (s) => document.querySelector(s);
const MAX_ROWS = 1500; // mehr Zeilen auf einmal machen die Liste träge
const SERVER_VERSION = 12; // muss zu VERSION in server.py passen

let state = { sources: [], playlists: [] };
let settings = {};
const catalogs = {}; // sourceId -> { items, byKey, updated }
const ui = {
  sourceId: null, type: 'live', group: null, search: '',
  selected: new Set(), playlistId: null, targetGroupId: null, collapsed: new Set(),
  plSel: new Set(), plLast: null   // Auswahl in der Playlist (Einträge), letzter Klick für ⇧-Bereich
};

// ---------- Hilfen ----------

async function api(path, body) {
  const opts = body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  };
  const r = await fetch(path, opts);
  let data;
  try { data = await r.json(); } catch (e) { throw new Error('Editor-Server antwortet nicht (läuft server.py?)'); }
  if (!r.ok || data.error) throw new Error(data.error || 'HTTP ' + r.status);
  return data;
}

let toastTimer;
function toast(msg, error) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast' + (error ? ' error' : '');
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, error ? 7000 : 3000);
}

function uid() { return Math.random().toString(36).slice(2, 10); }

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function logo(src) {
  const img = el('img');
  img.loading = 'lazy';
  img.alt = '';
  img.referrerPolicy = 'no-referrer';
  if (src) { img.src = src; img.onerror = () => img.classList.add('empty'); } else img.classList.add('empty');
  return img;
}

let saveTimer;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    api('/api/state', state).catch((e) => toast('Speichern fehlgeschlagen: ' + e.message, true));
  }, 300);
}

async function busy(fn) {
  document.body.classList.add('busy');
  try { return await fn(); } finally { document.body.classList.remove('busy'); }
}

// Land/Region aus dem Gruppennamen: "DE | Sport", "|UK| News", "[FR] Kino", "ASIA | India",
// "EXYU| Sport", ausgeschriebene Länder ("SWEDEN PPV" -> SE) und "24/7 …".
const COUNTRY_NAMES = {
  GERMANY: 'DE', DEUTSCHLAND: 'DE', AUSTRIA: 'AT', SWITZERLAND: 'CH', SWEDEN: 'SE', NORWAY: 'NO',
  DENMARK: 'DK', FINLAND: 'FI', NETHERLANDS: 'NL', HOLLAND: 'NL', BELGIUM: 'BE', FRANCE: 'FR',
  SPAIN: 'ES', ITALY: 'IT', PORTUGAL: 'PT', POLAND: 'PL', TURKEY: 'TR', GREECE: 'GR', ALBANIA: 'AL',
  ROMANIA: 'RO', BULGARIA: 'BG', HUNGARY: 'HU', CZECH: 'CZ', RUSSIA: 'RU', UKRAINE: 'UA', IRELAND: 'IE',
  USA: 'US', CANADA: 'CA', MEXICO: 'MX', BRAZIL: 'BR', ARGENTINA: 'AR', INDIA: 'IN', PAKISTAN: 'PK',
  ARABIC: 'AR-WORLD', ARAB: 'AR-WORLD', AFRICA: 'AFR', ASIA: 'ASIA', LATINO: 'LAT', LATIN: 'LAT'
};
function countryOf(group) {
  const g = (group || '').trim();
  let m = /^[[(|]?\s*([A-Za-z]{2,6})\s*[\])|:\-–]/.exec(g);
  if (m) return COUNTRY_NAMES[m[1].toUpperCase()] || m[1].toUpperCase();
  if (/^24\/7\b/.test(g)) return '24/7';
  m = /^([A-Za-zÄÖÜäöü]+)/.exec(g);
  return (m && COUNTRY_NAMES[m[1].toUpperCase()]) || '—';
}

const source = () => state.sources.find((s) => s.id === ui.sourceId) || null;
const playlist = () => state.playlists.find((p) => p.id === ui.playlistId) || null;
const catalog = () => catalogs[ui.sourceId] || null;

function lookup(key) {
  const c = catalogs[key.split(':', 1)[0]];
  return c ? c.byKey.get(key) : undefined;
}

// ---------- Quellen ----------

// ---------- Zugang (Quelle) einer Playlist ----------

function plKeys(pl) {
  const keys = [];
  pl.groups.forEach((g) => g.items.forEach((i) => {
    keys.push(i.key);
    (i.variants || []).forEach((v) => keys.push(v.key));
  }));
  return keys;
}

// Auswahlfeld: Zugang, über den die Playlist läuft („gemischt“, wenn mehrere)
function renderPlSource() {
  const sel = $('#pl-source');
  const pl = playlist();
  sel.textContent = '';
  sel.disabled = !pl;
  if (!pl) return;
  const used = new Set(plKeys(pl).map((k) => k.split(':', 1)[0]));
  if (used.size !== 1) sel.appendChild(el('option', '', used.size ? 'gemischt' : '–'));
  state.sources.forEach((s) => {
    const o = el('option', '', s.name);
    o.value = s.id;
    sel.appendChild(o);
  });
  sel.value = used.size === 1 ? [...used][0] : '';
  if (used.size !== 1) sel.selectedIndex = 0;
}

async function switchPlSource(target) {
  const pl = playlist();
  const src = state.sources.find((s) => s.id === target);
  if (!pl || !src) return;
  if (!confirm(`Playlist „${pl.name}“ auf den Zugang „${src.name}“ umstellen?\n\n`
    + 'Gruppen, Reihenfolge und Fassungen bleiben gleich; die Geräte nutzen danach die Verbindung von „'
    + src.name + '“. Zum Übernehmen danach „Veröffentlichen“.')) return renderPlSource();
  let res;
  try {
    res = await busy(() => api('/api/switch-source', { source: target, keys: plKeys(pl) }));
  } catch (e) {
    renderPlSource();
    return toast(e.message, true);
  }
  const m = res.mapping;
  pl.groups.forEach((g) => g.items.forEach((i) => {
    if (m[i.key]) i.key = m[i.key];
    (i.variants || []).forEach((v) => { if (m[v.key]) v.key = m[v.key]; });
  }));
  save();
  if (target !== ui.sourceId) await loadCatalog(target, true, sourceKeys(target)).catch(() => null);
  renderAll();
  toast(`${Object.keys(m).length} Einträge auf „${src.name}“ umgestellt`
    + (res.missing.length ? ` – ${res.missing.length} gibt es dort nicht (bleiben beim alten Zugang): ${res.missing.slice(0, 5).join(', ')}` : '')
    + '. Jetzt „Veröffentlichen“.', !!res.missing.length);
}

// Alle Schlüssel einer Quelle, die in Playlists vorkommen (auch Sprachfassungen)
function sourceKeys(sid) {
  const keys = [];
  state.playlists.forEach((p) => p.groups.forEach((g) => g.items.forEach((i) => {
    [i.key, ...(i.variants || []).map((v) => v.key)].forEach((k) => { if (k.startsWith(sid + ':')) keys.push(k); });
  })));
  return keys;
}

// Ohne keys: ganzer Katalog der Quelle. Mit keys: nur diese Einträge (für Playlist-Anzeige
// anderer Quellen – ganze Kataloge sind je ~70 MB, mehrere gleichzeitig überfordern den Browser).
async function loadCatalog(id, force, keys) {
  if (!id) return null;
  if (catalogs[id] && !force && (keys || !catalogs[id].partial)) return catalogs[id];
  const data = keys ? await api('/api/catalog-part', { id, keys })
    : await api('/api/catalog?id=' + encodeURIComponent(id));
  const byKey = new Map();
  const works = new Map(); // Werk-Schlüssel -> alle Sprachfassungen (nur Filme/Serien)
  const years = new Map(); // Titel ohne Jahr -> gefundene Jahre
  const vod = [];
  (data.items || []).forEach((it) => {
    it.name = it.name || '(ohne Namen)';
    it.group = it.group || 'Sonstige';
    it.cc = countryOf(it.group);
    byKey.set(it.key, it);
    if (it.type === 'movie' || it.type === 'series') {
      const t = parseTitle(it.name);
      it.title = t.title;
      it.lang = t.lang || (it.cc !== '—' && it.cc) || t.suffix || '?';
      it.q = qualityOf(it);
      it._t = t;
      vod.push(it);
      if (t.year) {
        const k = it.type + '|' + t.base;
        if (!years.has(k)) years.set(k, new Set());
        years.get(k).add(t.year);
      }
    }
  });
  vod.forEach((it) => {
    const t = it._t;
    let year = t.year;
    if (!year) {
      // Titel ohne Jahr: dem Werk mit Jahr zuordnen, wenn es genau eines gibt
      const ys = years.get(it.type + '|' + t.base);
      if (ys && ys.size === 1) { year = [...ys][0]; it.title = `${it.title} (${year})`; }
    }
    it.wk = it.type + '|' + t.base + '|' + year;
    delete it._t;
    if (!works.has(it.wk)) works.set(it.wk, []);
    works.get(it.wk).push(it);
  });
  catalogs[id] = { items: data.items || [], byKey, works, updated: data.updated, partial: !!keys };
  return catalogs[id];
}

// ---------- Sprachen ----------

// Deutschsprachiges zuerst, dann Mehrsprachiges, dann der Rest.
const PREFERRED = ['DE', 'AT', 'CH', 'MULTI'];
function langRank(cc) {
  const i = PREFERRED.indexOf(cc);
  return i < 0 ? PREFERRED.length : i;
}

// "EN - Dune: Part Two (2024)", "Dark Hearts (2023) (FR)" -> Titel, Jahr, Sprache, Vergleichsschlüssel
// "[SE] Titel", "|DE| Titel", "EN - Titel", "SE-4K - Titel", "DE-4K-BLURAY-DV - Titel"
const LANG_PRE = /^\s*-?\s*(?:[[(|]\s*([A-Z]{2,5})\s*[\])|]\s*[-–:|]?\s*|([A-Z]{2,5})(?:[-+][A-Z0-9+]{1,10}|\s*-?\s*\[[^\]]{1,20}\])*\s*[-–:|]\s+)/;
const KNOWN_LANGS = new Set(('DE AT CH EN US UK GB FR ES IT NL PL TR GR PT BR RU SE NO DK FI AL EX EXYU BG HU RO CZ '
  + 'SK SI HR RS BA IN AR IR KU NF MULTI SC SCA LAT QFR KR JP CN TH VN ID PH IL UA LT LV EE').split(' '));
// Qualität vor der Sprache: "4K-DE - Titel", "4K - Titel", "[4K] Titel"
const QUALITY_PRE = /^\s*(?:\[?(?:4K|UHD|FHD)\]?\s*[-–:|]?\s*)+(?=\S)/;
const LANG_SUF = /\s*[([]([A-Z]{2,3})[)\]]\s*$/;
function parseTitle(name) {
  let n = name || '';
  let lang = null;      // Kürzel vorne = Sprache der Fassung
  let suffix = null;    // Kürzel hinten = oft nur Produktionsland, daher nachrangig
  n = n.replace(QUALITY_PRE, '');
  let m = LANG_PRE.exec(n);
  // "NCIS: Los Angeles", "CSI: Miami": Doppelpunkt nur bei bekannten Sprachkürzeln als Trenner werten
  if (m && /:\s*$/.test(m[0]) && !KNOWN_LANGS.has(m[1] || m[2])) m = null;
  if (m) { lang = m[1] || m[2]; n = n.slice(m[0].length); }
  while ((m = LANG_SUF.exec(n))) { suffix = suffix || m[1]; n = n.slice(0, m.index); }
  const year = (/\((\d{4})\)/.exec(n) || [])[1] || '';
  const clean = n.replace(/\(\d{4}\)/g, '').replace(/\b(4K|UHD|FHD|HDR|DV|HEVC)\b/g, '')
    .replace(/\s*[-–|]\s*$/, '').replace(/\s{2,}/g, ' ').trim();
  const base = clean.toLowerCase()
    .replace(/\b(4k|uhd|fhd|hd|lq|hdr|dv|sub|dub|multi|polski|uncut)\b/g, '')
    .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  return { title: clean + (year ? ` (${year})` : ''), lang, suffix, key: base + '|' + year, base, year };
}

// Qualität einer Fassung aus Name und Gruppe: "" (normal), "4K", "HDR", "4K HDR"
function qualityOf(it) {
  const s = (it.name + ' ' + it.group).toUpperCase();
  const q = [];
  if (/(^|[^A-Z0-9])(4K|UHD|2160P?)([^A-Z0-9]|$)/.test(s)) q.push('4K');
  if (/(^|[^A-Z0-9])(DV|HDR10?\+?|DOLBY VISION)([^A-Z0-9]|$)/.test(s)) q.push('HDR');
  return q.join(' ');
}
const QUALITY_RANK = { '': 0, '4K': 1, 'HDR': 2, '4K HDR': 3 };

// Bezeichnung einer Fassung: Sprache plus Qualität, z. B. "DE", "DE 4K"
const variantLabel = (v) => v.lang + (v.q ? ' ' + v.q : '');

// Sichtbare Fassungen eines Werks (ausgeblendete Sprachen weg): Deutsch zuerst, je Sprache
// die normale Qualität vor 4K/HDR (läuft auf mehr Geräten). Je Sprache+Qualität eine Fassung.
function variantsOf(it) {
  const c = catalogs[it.key.split(':', 1)[0]];
  const hidden = new Set((state.sources.find((s) => it.key.startsWith(s.id + ':')) || {}).hiddenCountries || []);
  const all = (c && it.wk && c.works.get(it.wk)) || [it];
  const seen = new Set();
  return all.filter((v) => !hidden.has(v.lang) && !hidden.has(v.cc))
    .sort((a, b) => langRank(a.lang) - langRank(b.lang) || (QUALITY_RANK[a.q || ''] || 0) - (QUALITY_RANK[b.q || ''] || 0))
    .filter((v) => !seen.has(variantLabel(v)) && seen.add(variantLabel(v)));
}

const grouping = () => ui.type !== 'live' && $('#by-title').checked;

async function refreshSource() {
  const src = source();
  if (!src) return toast('Bitte zuerst eine Quelle anlegen.', true);
  await busy(async () => {
    $('#center-status').textContent = 'Lade „' + src.name + '“ … (kann bei großen Anbietern eine Minute dauern)';
    try {
      clearTimeout(saveTimer);
      await api('/api/state', state);
      const res = await api('/api/refresh', { id: src.id });
      await loadCatalog(src.id, true);
      const c = res.counts;
      let msg = `${src.name}: ${c.live || 0} Live, ${c.movie || 0} Filme, ${c.series || 0} Serien` +
        (res.epg ? ' · EPG gefunden' : ' · kein EPG');
      const ch = res.changes;
      const names = { live: 'Live', movie: 'Filme', series: 'Serien' };
      const parts = ch ? Object.keys(names).filter((t) => ch[t]).map((t) => `${names[t]} +${ch[t].added}/−${ch[t].removed}`) : [];
      if (parts.length) msg += ' · Änderungen beim Anbieter: ' + parts.join(', ');
      const rep = repairPlaylists(src.id);
      if (rep.fixed) msg += ` · ${rep.fixed} Playlist-Einträge neu zugeordnet`;
      if (rep.missing) msg += ` · ${rep.missing} Einträge nicht mehr vorhanden`;
      toast(msg, rep.missing > 0);
      loadSourceInfo(src.id);
    } catch (e) {
      toast(e.message, true);
    }
  });
  ui.group = null;
  renderAll();
}

function renderSourceSelect() {
  const sel = $('#source');
  sel.textContent = '';
  if (!state.sources.length) sel.appendChild(el('option', '', '– keine Quelle –'));
  state.sources.forEach((s) => {
    const o = el('option', '', s.name);
    o.value = s.id;
    sel.appendChild(o);
  });
  sel.value = ui.sourceId || '';
}

function openSourceDialog(src) {
  const dlg = $('#dlg-source');
  const f = $('#form-source');
  f.reset();
  $('#dlg-source-title').textContent = src ? 'Quelle bearbeiten' : 'Neue Quelle';
  $('#src-delete').hidden = !src;
  const s = src || { type: 'xtream', liveFormat: 'm3u8' };
  ['name', 'server', 'username', 'password', 'url', 'epgUrl'].forEach((k) => { f.elements[k].value = s[k] || ''; });
  f.elements.liveFormat.value = s.liveFormat || 'm3u8';
  f.querySelector(`input[name="type"][value="${s.type}"]`).checked = true;
  syncSourceType();
  dlg.returnValue = '';
  dlg.onclose = async () => {
    const action = dlg.returnValue;
    if (action === 'delete' && src) {
      if (!confirm(`Quelle „${src.name}“ löschen? Playlist-Einträge daraus werden beim Veröffentlichen übersprungen.`)) return;
      state.sources = state.sources.filter((x) => x.id !== src.id);
      ui.sourceId = state.sources[0] ? state.sources[0].id : null;
      save();
      renderAll();
      return;
    }
    if (action !== 'save') return;
    const data = {};
    ['name', 'server', 'username', 'password', 'url', 'epgUrl', 'liveFormat'].forEach((k) => { data[k] = f.elements[k].value.trim(); });
    data.type = f.querySelector('input[name="type"]:checked').value;
    if (src) Object.assign(src, data);
    else {
      const neu = Object.assign({ id: 's' + uid(), hiddenCountries: [] }, data);
      state.sources.push(neu);
      ui.sourceId = neu.id;
    }
    renderSourceSelect();
    await refreshSource();
  };
  dlg.showModal();
}

function syncSourceType() {
  const type = $('#form-source').querySelector('input[name="type"]:checked').value;
  document.querySelectorAll('#form-source fieldset').forEach((fs) => { fs.hidden = fs.dataset.for !== type; });
}

// ---------- Linke Spalte: Länder und Gruppen ----------

function visibleItems() {
  const c = catalog();
  if (!c) return [];
  const hidden = new Set((source() || {}).hiddenCountries || []);
  return c.items.filter((it) => it.type === ui.type && !hidden.has(it.cc));
}

function renderCountries() {
  const box = $('#countries');
  box.textContent = '';
  const c = catalog();
  const src = source();
  if (!c || !src) return;
  const counts = new Map();
  c.items.forEach((it) => {
    if (it.type !== ui.type) return;
    const k = it.cc;
    counts.set(k, (counts.get(k) || 0) + 1);
  });
  const hidden = new Set(src.hiddenCountries || []);
  [...counts.entries()].sort((a, b) => langRank(a[0]) - langRank(b[0]) || b[1] - a[1]).forEach(([k, n]) => {
    const b = el('button', 'chip' + (hidden.has(k) ? ' off' : ''), `${k === '—' ? 'ohne Kürzel' : k} ${n}`);
    b.type = 'button';
    b.title = hidden.has(k) ? 'Einblenden' : 'Ausblenden';
    b.onclick = () => {
      const set = new Set(src.hiddenCountries || []);
      set.has(k) ? set.delete(k) : set.add(k);
      src.hiddenCountries = [...set];
      save();
      renderAll();
    };
    box.appendChild(b);
  });
  if (counts.size > 1) {
    const all = el('button', 'chip', 'alle umkehren');
    all.type = 'button';
    all.onclick = () => {
      const set = new Set(src.hiddenCountries || []);
      src.hiddenCountries = [...counts.keys()].filter((k) => !set.has(k));
      save();
      renderAll();
    };
    box.appendChild(all);
  }
}

function renderGroups() {
  const box = $('#groups');
  box.textContent = '';
  const items = visibleItems();
  if (!catalog()) {
    box.appendChild(el('div', 'empty-msg', state.sources.length ? 'Quelle noch nicht geladen – „Aktualisieren“.' : 'Oben mit ＋ eine Quelle anlegen.'));
    return;
  }
  const groups = new Map();
  items.forEach((it) => groups.set(it.group, (groups.get(it.group) || 0) + 1));
  if (ui.group && !groups.has(ui.group)) ui.group = null;
  const sorted = [...groups.entries()].sort((a, b) => langRank(countryOf(a[0])) - langRank(countryOf(b[0])));
  sorted.forEach(([g, n]) => {
    const row = el('div', 'grp' + (ui.group === g ? ' active' : ''));
    row.appendChild(el('span', 'name', g));
    row.appendChild(el('span', 'count', n));
    const add = el('button', 'add', '＋');
    add.type = 'button';
    add.title = 'Ganze Gruppe zur Playlist';
    add.onclick = (ev) => {
      ev.stopPropagation();
      addToPlaylist(uniqueWorks(items.filter((it) => it.group === g)), g);
    };
    row.appendChild(add);
    row.onclick = () => {
      ui.group = g;
      ui.search = '';
      $('#search').value = '';
      ui.selected.clear();
      renderGroups();
      renderChannels();
    };
    box.appendChild(row);
  });
}

// ---------- Mitte: Sender ----------

// Suchtext vergleichbar machen: klein, ohne Akzente und Satzzeichen ("Chicago P.D." -> "chicago pd")
function searchNorm(s) {
  return String(s || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[^\p{L}\p{N}\s]+/gu, '').replace(/\s+/g, ' ');
}

function channelList() {
  const q = searchNorm(ui.search).trim();
  const items = visibleItems();
  if (q) {
    const terms = q.split(' ');
    return items.filter((it) => {
      if (it._s === undefined) it._s = searchNorm(it.name + ' ' + it.group);
      return terms.every((t) => it._s.includes(t));
    });
  }
  return ui.group ? items.filter((it) => it.group === ui.group) : [];
}

function renderChannels() {
  const box = $('#channels');
  box.textContent = '';
  const list = grouping() ? uniqueWorks(channelList()) : channelList();
  const inPl = playlistKeys();
  const status = $('#center-status');
  if (!list.length) {
    box.appendChild(el('div', 'empty-msg', ui.search ? 'Keine Treffer.' : 'Links eine Gruppe wählen oder oben suchen.'));
    status.textContent = catalogInfo();
    $('#select-all').checked = false;
    return;
  }
  const frag = document.createDocumentFragment();
  list.slice(0, MAX_ROWS).forEach((it) => {
    const vs = grouping() ? variantsOf(it) : null;
    const isIn = vs ? vs.some((v) => inPl.has(v.key)) : inPl.has(it.key);
    const row = el('div', 'ch' + (ui.selected.has(it.key) ? ' sel' : '') + (isIn ? ' in' : ''));
    row.draggable = true;
    const cb = el('input');
    cb.type = 'checkbox';
    cb.checked = ui.selected.has(it.key);
    row.appendChild(cb);
    row.appendChild(playButton(vs && vs[0] ? vs[0] : it, vs ? `${it.title} (${variantLabel(vs[0] || it)})` : it.name));
    row.appendChild(logo(it.logo));
    row.appendChild(el('span', 'nm', vs ? it.title : it.name));
    const meta = el('span', 'meta');
    if (ui.search && !vs) meta.appendChild(document.createTextNode(it.group + ' '));
    if (vs) vs.forEach((v) => meta.appendChild(el('span', 'lang' + (langRank(v.lang) < PREFERRED.length ? ' pref' : ''), variantLabel(v))));
    if (it.tvgId) meta.appendChild(el('span', 'epg', 'EPG'));
    row.appendChild(meta);
    row.onclick = () => {
      ui.selected.has(it.key) ? ui.selected.delete(it.key) : ui.selected.add(it.key);
      cb.checked = ui.selected.has(it.key);
      row.classList.toggle('sel', cb.checked);
      updateCenterStatus(list);
    };
    row.addEventListener('dragstart', (ev) => {
      const keys = ui.selected.has(it.key) ? [...ui.selected] : [it.key];
      drag = { kind: 'catalog', keys, group: it.group };
      ev.dataTransfer.effectAllowed = 'copy';
      ev.dataTransfer.setData('text/plain', keys.length + ' Sender');
    });
    frag.appendChild(row);
  });
  box.appendChild(frag);
  updateCenterStatus(list);
}

function catalogInfo() {
  const c = catalog();
  if (!c || !c.updated) return '';
  return 'Stand der Quelle: ' + new Date(c.updated * 1000).toLocaleString('de-DE');
}

function updateCenterStatus(list) {
  const sel = list.filter((it) => ui.selected.has(it.key)).length;
  $('#select-all').checked = sel > 0 && sel === Math.min(list.length, MAX_ROWS);
  $('#center-status').textContent = `${list.length} Sender` +
    (list.length > MAX_ROWS ? ` (erste ${MAX_ROWS} angezeigt – Suche eingrenzen)` : '') +
    ` · ${sel} gewählt · ${catalogInfo()}`;
}

// ---------- Playlists ----------

function playlistKeys() {
  const pl = playlist();
  const s = new Set();
  if (pl) pl.groups.forEach((g) => g.items.forEach((i) => { s.add(i.key); (i.variants || []).forEach((v) => s.add(v.key)); }));
  return s;
}

function renderPlaylistSelect() {
  const sel = $('#playlist');
  sel.textContent = '';
  if (!state.playlists.length) sel.appendChild(el('option', '', '– keine Playlist –'));
  state.playlists.forEach((p) => {
    const o = el('option', '', p.name);
    o.value = p.id;
    sel.appendChild(o);
  });
  sel.value = ui.playlistId || '';
}

function newPlaylist() {
  const name = prompt('Name der neuen Playlist (z. B. Familie, Oma, Sport):');
  if (!name || !name.trim()) return;
  const pl = { id: 'p' + uid(), name: name.trim(), groups: [] };
  state.playlists.push(pl);
  ui.playlistId = pl.id;
  ui.targetGroupId = null;
  save();
  renderAll();
}

// Fügt Sender in die Ziel-Gruppe ein; ohne Ziel in eine Gruppe mit dem Namen der Quell-Gruppe.
function addToPlaylist(items, fallbackGroup) {
  let pl = playlist();
  if (!pl) {
    newPlaylist();
    pl = playlist();
    if (!pl) return;
  }
  const existing = playlistKeys();
  let added = 0;
  items.forEach((it) => {
    if (existing.has(it.key)) return;
    let g = pl.groups.find((x) => x.id === ui.targetGroupId);
    if (!g) {
      const name = fallbackGroup || it.group || 'Sonstige';
      g = pl.groups.find((x) => x.name === name);
      if (!g) { g = { id: 'g' + uid(), name, items: [] }; pl.groups.push(g); }
    }
    const item = toPlaylistItem(it);
    if (!item) return;
    g.items.push(item);
    existing.add(item.key);
    (item.variants || []).forEach((v) => existing.add(v.key));
    added++;
  });
  save();
  ui.selected.clear();
  renderChannels();
  renderPlaylist();
  toast(added ? `${added} zur Playlist „${pl.name}“ hinzugefügt` : 'Schon alles in der Playlist');
}

function renderPlaylist() {
  const box = $('#pl-groups');
  box.textContent = '';
  const pl = playlist();
  if (!pl) {
    $('#pl-summary').textContent = '';
    box.appendChild(el('div', 'empty-msg', 'Oben mit ＋ eine Playlist anlegen.'));
    return;
  }
  const total = pl.groups.reduce((n, g) => n + g.items.length, 0);
  $('#pl-summary').textContent = `${pl.groups.length} Gruppen · ${total} Einträge`;
  renderSelBar(pl);
  if (!pl.groups.length) box.appendChild(el('div', 'empty-msg', 'Sender links auswählen und „Zur Playlist →“ oder hierher ziehen.'));

  pl.groups.forEach((g, gi) => {
    const wrap = el('div', 'pg' + (ui.collapsed.has(g.id) ? ' collapsed' : '') + (ui.targetGroupId === g.id ? ' target' : ''));
    const head = el('div', 'pg-head');
    head.draggable = true;
    head.appendChild(el('span', 'handle', '⠿'));
    head.appendChild(el('span', 'tg', ui.collapsed.has(g.id) ? '▸' : '▾'));
    head.appendChild(el('span', 'name', g.name));
    head.appendChild(el('span', 'count', g.items.length));
    const sortBtn = el('button', 'sort', '⇅');
    sortBtn.type = 'button';
    sortBtn.title = 'Gruppe automatisch sortieren';
    sortBtn.onclick = (ev) => { ev.stopPropagation(); openSort(g); };
    head.appendChild(sortBtn);
    const del = el('button', '', '✕');
    del.type = 'button';
    del.title = 'Gruppe löschen';
    del.onclick = (ev) => {
      ev.stopPropagation();
      if (g.items.length && !confirm(`Gruppe „${g.name}“ mit ${g.items.length} Einträgen löschen?`)) return;
      pl.groups.splice(gi, 1);
      if (ui.targetGroupId === g.id) ui.targetGroupId = null;
      save();
      renderPlaylist();
      renderChannels();
    };
    head.appendChild(del);
    head.onclick = (ev) => {
      if (ev.target.classList.contains('tg')) {
        ui.collapsed.has(g.id) ? ui.collapsed.delete(g.id) : ui.collapsed.add(g.id);
      } else {
        ui.targetGroupId = ui.targetGroupId === g.id ? null : g.id;
      }
      renderPlaylist();
    };
    head.ondblclick = () => {
      const name = prompt('Gruppe umbenennen:', g.name);
      if (name && name.trim()) { g.name = name.trim(); save(); renderPlaylist(); }
    };
    dragSource(head, () => ({ kind: 'group', gi }));
    dropTarget(head, (d) => dropOnGroupHead(pl, gi, d));
    wrap.appendChild(head);

    const itemsBox = el('div', 'pg-items');
    g.items.forEach((item, ii) => {
      const ch = lookup(item.key);
      const row = el('div', 'it' + (catalogs[item.key.split(':', 1)[0]] && !ch ? ' missing' : '')
        + (isDead(pl.id, item.key) ? ' dead' : '') + (ui.plSel.has(item) ? ' sel' : ''));
      row.onclick = (ev) => { if (!ev.target.closest('button')) selectItem(pl, g, item, ev); };
      row.draggable = true;
      row.appendChild(el('span', 'handle', '⠿'));
      row.appendChild(logo(ch && ch.logo));
      const nm = el('span', 'nm', item.name || (item.variants ? item.label : ch && ch.name) || item.label);
      nm.title = ch ? `${ch.name} · ${ch.group}` : (item.label + ' (Quelle nicht geladen)');
      row.appendChild(nm);
      if (item.variants) {
        const langs = el('span', 'meta');
        item.variants.forEach((v) => langs.appendChild(el('span', 'lang' + (langRank(v.lang.split(' ')[0]) < PREFERRED.length ? ' pref' : ''), v.lang)));
        row.appendChild(langs);
      } else {
        row.appendChild(ch && ch.tvgId ? el('span', 'epg', 'EPG') : el('span'));
      }
      row.appendChild(ch ? playButton(ch, item.name || item.label) : el('span'));
      const x = el('button', '', '✕');
      x.type = 'button';
      x.title = 'Entfernen';
      x.onclick = () => { g.items.splice(ii, 1); save(); renderPlaylist(); renderChannels(); };
      row.appendChild(x);
      row.ondblclick = () => {
        const name = prompt('Anzeigename (leer = Originalname):', item.name || (ch && ch.name) || item.label);
        if (name === null) return;
        if (name.trim() && (!ch || name.trim() !== ch.name)) item.name = name.trim(); else delete item.name;
        save();
        renderPlaylist();
      };
      dragSource(row, () => ({ kind: 'item', gi, ii }));
      dropTarget(row, (d) => dropBefore(pl, gi, ii, d));
      itemsBox.appendChild(row);
    });
    const end = el('div', 'drop-end');
    dropTarget(end, (d) => dropBefore(pl, gi, g.items.length, d));
    itemsBox.appendChild(end);
    wrap.appendChild(itemsBox);
    box.appendChild(wrap);
  });
}

// ---------- Sortieren (je Gruppe) ----------

// Übliche Senderfolge im deutschen Fernsehen (Vergleichsschlüssel, siehe sortKey)
const TV_ORDER = ['daserste', 'zdf', 'rtl', 'sat1', 'prosieben', 'vox', 'kabeleins', 'rtlzwei', 'superrtl',
  'ntv', 'welt', 'tagesschau24', 'phoenix', 'zdfinfo', 'zdfneo', 'one', '3sat', 'arte', 'kika', 'ardalpha',
  'br', 'ndr', 'wdr', 'swr', 'mdr', 'hr', 'rbb', 'sr', 'radiobremen', 'rtlup', 'nitro', 'voxup', 'sixx',
  'prosiebenmaxx', 'sat1gold', 'kabeleinsdoku', 'kabeleinsclassics', 'dmax', 'tlc', 'tele5', 'servustv',
  'n24doku', 'weltdoku', 'ntvdoku', 'sport1', 'eurosport1', 'eurosport2', 'skysportnews', 'dazn1', 'dazn2',
  'comedycentral', 'nickelodeon', 'disneychannel', 'toggoplus', 'deluxemusic', 'mtv', 'bildtv', 'qvc', 'hse'];
const TV_ALIAS = {
  ard: 'daserste', daserste: 'daserste', pro7: 'prosieben', prosieben: 'prosieben', kabel1: 'kabeleins',
  rtl2: 'rtlzwei', rtlii: 'rtlzwei', rtlnitro: 'nitro', brfernsehen: 'br', bayerischesfernsehen: 'br',
  hrfernsehen: 'hr', swrbw: 'swr', swrrp: 'swr', swrfernsehen: 'swr', srfernsehen: 'sr', ndrfernsehen: 'ndr',
  wdrfernsehen: 'wdr', mdrfernsehen: 'mdr', rbbfernsehen: 'rbb', kabel1doku: 'kabeleinsdoku', welttv: 'welt',
  eurosport: 'eurosport1', sat1emotions: 'sat1emotions', prosiebenfun: 'prosiebenfun', disney: 'disneychannel'
};
const TV_RANK = new Map(TV_ORDER.map((k, i) => [k, i]));

// "DE| SAT.1 GOLD FHD" -> "sat1gold" (ohne Land, Qualität und Satzzeichen)
function sortKey(name) {
  let k = String(name || '').toLowerCase()
    .replace(/^\s*[a-z]{2,4}\s*[|:]\s*/, '')
    .replace(/\(.*?\)|\[.*?\]/g, ' ')
    .replace(/(^|[^a-z0-9])(4k|uhd|fhd|hd|sd|hevc|h\.?265|raw|50fps|60fps|backup|[ᴴᴰᴿᴬᵂ⁶⁰ᶠᵖˢ]+)(?=[^a-z0-9]|$)/g, ' ')
    .replace(/[^a-z0-9äöüß]+/g, '');
  return TV_ALIAS[k] || k;
}

// Qualität aus dem Namen: kleiner = besser
function qualityRank(name) {
  const n = String(name || '').toUpperCase();
  if (/4K|UHD|2160/.test(n)) return 0;
  if (/FHD|1080/.test(n)) return 1;
  if (/HEVC|H\.?265/.test(n)) return 2;
  if (/\bSD\b/.test(n)) return 4;
  return 3;
}

function itemName(item) {
  const ch = lookup(item.key);
  return item.name || (item.variants ? item.label : ch && ch.name) || item.label || '';
}

function sortGroup(g, how, dedupe) {
  const rows = g.items.map((item, i) => {
    const name = itemName(item);
    return { item, i, name, key: sortKey(name), q: qualityRank(name) };
  });
  const first = new Map();
  rows.forEach((r) => { if (!first.has(r.key)) first.set(r.key, r.i); });
  const cmp = {
    tv: (a, b) => {
      const ra = TV_RANK.has(a.key) ? TV_RANK.get(a.key) : 1e6 + first.get(a.key);
      const rb = TV_RANK.has(b.key) ? TV_RANK.get(b.key) : 1e6 + first.get(b.key);
      return ra - rb || a.q - b.q || a.i - b.i;
    },
    az: (a, b) => a.key.localeCompare(b.key, 'de') || a.q - b.q || a.i - b.i,
    quality: (a, b) => first.get(a.key) - first.get(b.key) || a.q - b.q || a.i - b.i
  }[how];
  rows.sort(cmp);
  let removed = 0;
  if (dedupe) {
    const seen = new Set();
    const keep = [];
    rows.forEach((r) => {
      if (!r.item.variants && seen.has(r.key)) { removed++; return; }
      seen.add(r.key);
      keep.push(r);
    });
    rows.length = 0;
    rows.push(...keep);
  }
  g.items = rows.map((r) => r.item);
  return removed;
}

function openSort(g) {
  const dlg = $('#dlg-sort');
  const f = $('#form-sort');
  $('#sort-title').textContent = `„${g.name}“ sortieren (${g.items.length} Einträge)`;
  f.reset();
  dlg.returnValue = '';
  dlg.onclose = () => {
    if (dlg.returnValue !== 'ok') return;
    const removed = sortGroup(g, f.elements.how.value, f.elements.dedupe.checked);
    save();
    renderPlaylist();
    toast(`„${g.name}“ sortiert` + (removed ? `, ${removed} doppelte entfernt` : '') + ' – zum Übernehmen „Veröffentlichen“');
  };
  dlg.showModal();
}

// ---------- Auswahl in der Playlist: mehrere Einträge verschieben ----------

function selectItem(pl, g, item, ev) {
  if (ev.shiftKey && ui.plLast && ui.plLast.g === g) {
    const a = g.items.indexOf(ui.plLast.item), b = g.items.indexOf(item);
    for (let i = Math.min(a, b); i <= Math.max(a, b); i++) ui.plSel.add(g.items[i]);
  } else if (ui.plSel.has(item)) {
    ui.plSel.delete(item);
  } else {
    ui.plSel.add(item);
  }
  ui.plLast = { g, item };
  renderPlaylist();
}

function renderSelBar(pl) {
  const bar = $('#pl-selbar');
  // nur Einträge zählen, die es noch gibt
  const all = new Set(pl ? pl.groups.flatMap((g) => g.items) : []);
  [...ui.plSel].forEach((i) => { if (!all.has(i)) ui.plSel.delete(i); });
  bar.hidden = !ui.plSel.size;
  if (!ui.plSel.size) return;
  $('#pl-selcount').textContent = `${ui.plSel.size} ausgewählt`;
  const sel = $('#sel-move');
  sel.textContent = '';
  sel.appendChild(el('option', '', 'Verschieben nach …'));
  pl.groups.forEach((g) => {
    const o = el('option', '', g.name);
    o.value = g.id;
    sel.appendChild(o);
  });
  sel.value = '';
  sel.selectedIndex = 0;
}

// Ausgewählte innerhalb ihrer Gruppe ganz nach oben/unten (Reihenfolge untereinander bleibt)
function moveSelToEdge(top) {
  const pl = playlist();
  if (!pl || !ui.plSel.size) return;
  pl.groups.forEach((g) => {
    const sel = g.items.filter((i) => ui.plSel.has(i));
    if (!sel.length) return;
    const rest = g.items.filter((i) => !ui.plSel.has(i));
    g.items = top ? sel.concat(rest) : rest.concat(sel);
  });
  save();
  renderPlaylist();
}

// Ausgewählte um eine Position verschieben
function moveSelStep(dir) {
  const pl = playlist();
  if (!pl || !ui.plSel.size) return;
  pl.groups.forEach((g) => {
    const it = g.items;
    const order = dir < 0 ? it.map((_, i) => i) : it.map((_, i) => it.length - 1 - i);
    order.forEach((i) => {
      const j = i + dir;
      if (ui.plSel.has(it[i]) && j >= 0 && j < it.length && !ui.plSel.has(it[j])) [it[i], it[j]] = [it[j], it[i]];
    });
  });
  save();
  renderPlaylist();
  const first = document.querySelector('#pl-groups .it.sel');
  if (first) first.scrollIntoView({ block: 'nearest' });
}

function moveSelToGroup(gid) {
  const pl = playlist();
  const target = pl && pl.groups.find((g) => g.id === gid);
  if (!target) return;
  const moving = [];
  pl.groups.forEach((g) => {
    g.items = g.items.filter((i) => {
      if (ui.plSel.has(i) && g !== target) { moving.push(i); return false; }
      return true;
    });
  });
  target.items.push(...moving);
  save();
  renderPlaylist();
  toast(`${moving.length} nach „${target.name}“ verschoben (ans Ende)`);
}

// ---------- Ziehen & Ablegen ----------

let drag = null;

function dragSource(node, make) {
  node.addEventListener('dragstart', (ev) => {
    ev.stopPropagation();
    drag = make();
    node.classList.add('dragging');
    ev.dataTransfer.effectAllowed = 'move';
    ev.dataTransfer.setData('text/plain', 'x');
  });
  node.addEventListener('dragend', () => { node.classList.remove('dragging'); drag = null; });
}

function dropTarget(node, onDrop) {
  node.addEventListener('dragover', (ev) => { if (drag) { ev.preventDefault(); node.classList.add('drag-over'); } });
  node.addEventListener('dragleave', () => node.classList.remove('drag-over'));
  node.addEventListener('drop', (ev) => {
    ev.preventDefault();
    ev.stopPropagation();
    node.classList.remove('drag-over');
    if (drag) onDrop(drag);
    drag = null;
  });
}

// Ein Eintrag je Werk (bei aktiver Zusammenfassung), sonst unverändert.
function uniqueWorks(items) {
  if (!grouping()) return items;
  const seen = new Set();
  return items.filter((it) => !it.wk || (!seen.has(it.wk) && seen.add(it.wk)));
}

// Katalog-Eintrag -> Playlist-Eintrag; Filme/Serien mit allen sichtbaren Sprachfassungen.
function toPlaylistItem(it) {
  if (!grouping() || !it.wk) return { key: it.key, label: it.name, sg: it.group };
  const vs = variantsOf(it);
  if (!vs.length) return null; // alle Sprachen ausgeblendet
  return { key: vs[0].key, label: it.title, variants: vs.map((v) => ({ key: v.key, lang: variantLabel(v) })) };
}

function catalogEntries(keys) {
  return keys.map((k) => lookup(k)).filter(Boolean);
}

// Auf Gruppenkopf: Gruppe davor einsortieren bzw. Einträge ans Ende dieser Gruppe.
function dropOnGroupHead(pl, gi, d) {
  if (d.kind === 'group') {
    if (d.gi === gi) return;
    const [g] = pl.groups.splice(d.gi, 1);
    pl.groups.splice(d.gi < gi ? gi - 1 : gi, 0, g);
    save();
    renderPlaylist();
  } else {
    dropBefore(pl, gi, pl.groups[gi].items.length, d);
  }
}

function dropBefore(pl, gi, index, d) {
  const target = pl.groups[gi];
  if (d.kind === 'item') {
    const src = pl.groups[d.gi];
    const [item] = src.items.splice(d.ii, 1);
    if (src === target && d.ii < index) index--;
    target.items.splice(index, 0, item);
  } else if (d.kind === 'catalog') {
    const existing = playlistKeys();
    const add = catalogEntries(d.keys).filter((it) => !existing.has(it.key))
      .map(toPlaylistItem).filter(Boolean);
    target.items.splice(index, 0, ...add);
    ui.selected.clear();
    renderChannels();
  } else {
    return;
  }
  save();
  renderPlaylist();
}

// ---------- Veröffentlichen & Einstellungen ----------

function linkRow(label, value) {
  const wrap = el('div');
  wrap.appendChild(el('div', 'hint', label));
  const row = el('div', 'linkrow');
  const input = el('input');
  input.readOnly = true;
  input.value = value;
  const copy = el('button', '', 'Kopieren');
  copy.type = 'button';
  copy.onclick = () => { navigator.clipboard.writeText(value); toast('Kopiert'); };
  const open = el('button', '', 'Öffnen');
  open.type = 'button';
  open.onclick = () => window.open(value, '_blank');
  row.append(input, copy, open);
  wrap.appendChild(row);
  return wrap;
}

// ---------- Hintergrund-Aufgaben: Fortschritt anzeigen ----------

// Fragt /api/job ab, bis die Aufgabe fertig ist; zeigt Schritt, Balken und Dauer im Dialog.
async function followJob(body, cancelable) {
  const step = el('p', 'jobstep', 'Start …');
  const bar = el('div', 'jobbar indet');
  bar.appendChild(el('span'));
  const meta = el('p', 'hint', '');
  body.textContent = '';
  body.append(step, bar, meta);
  const cancel = $('#job-cancel');
  cancel.hidden = !cancelable;
  cancel.onclick = () => { api('/api/job/cancel', {}).catch(() => {}); cancel.disabled = true; cancel.textContent = 'Wird abgebrochen …'; };
  cancel.disabled = false;
  cancel.textContent = 'Abbrechen';
  for (;;) {
    let j;
    try { j = await api('/api/job'); } catch (e) { await new Promise((r) => setTimeout(r, 2000)); continue; }
    step.textContent = (j.pl ? `„${j.pl}“: ` : '') + (j.step || '');
    const known = j.total > 0 && j.done !== null && j.done !== undefined;
    bar.classList.toggle('indet', !known);
    bar.firstChild.style.width = known ? Math.round(100 * j.done / j.total) + '%' : '';
    const secs = Math.round(((j.finished || j.now) - j.started) || 0);
    meta.textContent = (known ? `${j.done} von ${j.total} · ` : '') + `läuft seit ${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')} min`;
    if (!j.running) {
      cancel.hidden = true;
      if (j.error) throw new Error(j.error);
      return j.result;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
}

// Nur die gewählte Playlist (schneller) oder alle veröffentlichen
async function publish(all) {
  if (!state.playlists.length) return toast('Noch keine Playlist angelegt.', true);
  const pl = playlist();
  if (!all && !pl) return toast('Bitte eine Playlist wählen.', true);
  const dlg = $('#dlg-publish');
  const body = $('#publish-body');
  $('#publish-title').textContent = all ? 'Alle veröffentlichen' : `„${pl.name}“ veröffentlichen`;
  body.textContent = '';
  dlg.showModal();
  let res;
  try {
    await api('/api/state', state);
    await api('/api/publish', { ids: all ? [] : [pl.id] });
    res = await followJob(body, false);
  } catch (e) {
    body.textContent = '';
    body.appendChild(el('p', 'warn', e.message));
    return;
  }
  body.textContent = '';
  if (!res.uploaded) {
    body.appendChild(el('p', 'hint', settings.hasToken
      ? 'Hochladen fehlgeschlagen – Details bei den Playlists.'
      : 'Nur lokal erzeugt. Zum Hochladen in den Einstellungen ein GitHub-Token eintragen.'));
  }
  await loadSettings();
  body.appendChild(appCard());
  res.results.forEach((r) => body.appendChild(linksBox(r)));
}

// ---------- Sender prüfen ----------

const checks = {};   // Playlist-ID -> Ergebnis von /api/check

async function loadCheck(pid) {
  try { checks[pid] = await api('/api/check?id=' + encodeURIComponent(pid)); } catch (e) { checks[pid] = {}; }
  if (pid === ui.playlistId) renderPlaylist();
}

function isDead(pid, key) {
  const r = checks[pid] && checks[pid].results && checks[pid].results[key];
  return !!(r && !r.ok && !r.event);
}

async function checkPlaylist() {
  const pl = playlist();
  if (!pl) return toast('Bitte eine Playlist wählen.', true);
  const n = pl.groups.reduce((a, g) => a + g.items.filter((i) => !i.variants && i.key.split(':')[1] === 'live').length, 0);
  if (!n) return toast('In dieser Playlist gibt es keine Live-Sender.', true);
  if (!confirm(`${n} Live-Sender nacheinander kurz anspielen – dauert etwa ${Math.max(1, Math.round(n * 2.5 / 60))} Minuten.\n\n`
    + 'Der Anbieter erlaubt nur eine Verbindung: währenddessen bitte nicht fernsehen (am besten abends oder nachts).')) return;
  const dlg = $('#dlg-publish');
  const body = $('#publish-body');
  $('#publish-title').textContent = `Sender prüfen „${pl.name}“`;
  dlg.showModal();
  let res;
  try {
    await api('/api/check', { id: pl.id });
    res = await followJob(body, true);
  } catch (e) {
    body.textContent = '';
    body.appendChild(el('p', 'warn', e.message));
    return;
  }
  await loadCheck(pl.id);
  showCheckResult(pl, res, body);
}

function showCheckResult(pl, res, body) {
  body.textContent = '';
  const c = checks[pl.id] || {};
  const dead = Object.entries(c.results || {}).filter(([, v]) => !v.ok && !v.event);
  body.appendChild(el('p', '', `${res.checked} von ${res.total} Sendern geprüft${res.cancelled ? ' (abgebrochen)' : ''}: `
    + (dead.length ? `${dead.length} ohne Bild.` : 'alle laufen.')
    + (res.events ? ` ${res.events} Event-/PPV-Kanäle senden gerade nichts (normal außerhalb von Events).` : '')));
  if (!dead.length) return;
  const ul = el('ul', 'checklist');
  dead.forEach(([, v]) => ul.appendChild(el('li', '', v.name + (v.err ? ` – ${v.err}` : ''))));
  body.appendChild(ul);
  body.appendChild(el('p', 'hint', 'In der Playlist sind sie rot mit ✕ markiert. Manchmal ist ein Sender nur kurz gestört – dann später erneut prüfen.'));
  const rm = el('button', 'danger', `${dead.length} defekte aus der Playlist entfernen`);
  rm.type = 'button';
  rm.onclick = () => {
    if (!confirm(`${dead.length} Sender aus „${pl.name}“ entfernen?`)) return;
    const keys = new Set(dead.map(([k]) => k));
    pl.groups.forEach((g) => { g.items = g.items.filter((i) => !keys.has(i.key)); });
    save();
    renderPlaylist();
    rm.disabled = true;
    rm.textContent = 'Entfernt – zum Übernehmen „Veröffentlichen“';
  };
  body.appendChild(rm);
}

function qrEl(text, label) {
  const wrap = el('div', 'qr');
  if (window.qrcode) {
    const q = qrcode(0, 'M');
    q.addData(text);
    q.make();
    const img = el('img');
    img.src = q.createDataURL(4, 0);
    img.alt = 'QR-Code ' + label;
    wrap.appendChild(img);
  }
  if (label) wrap.appendChild(document.createTextNode(label));
  return wrap;
}

// Großer, gut lesbarer Wert mit „Kopieren“ (z. B. zum Abtippen auf dem Fernseher)
function bigValue(value) {
  const row = el('div', 'bigvalue');
  row.appendChild(el('code', '', value));
  const copy = el('button', '', 'Kopieren');
  copy.type = 'button';
  copy.onclick = () => { navigator.clipboard.writeText(value); toast('Kopiert'); };
  row.appendChild(copy);
  return row;
}

function section(icon, title, sub) {
  const sec = el('section', 'lsec');
  const h = el('h4');
  h.appendChild(el('span', 'lsec-icon', icon));
  h.appendChild(document.createTextNode(title));
  sec.appendChild(h);
  if (sub) sec.appendChild(el('p', 'hint', sub));
  return sec;
}

// Oben im Fenster: wo es die Fire-TV-App gibt
function appCard() {
  const card = el('div', 'lcard appcard');
  card.appendChild(el('h3', '', '① Fire-TV-App installieren'));
  const pages = (settings.pagesUrl || '').trim();
  if (!pages) {
    card.appendChild(el('p', 'hint', 'Adresse der Webapp in den Einstellungen eintragen, dann steht hier der Download-Link.'));
    return card;
  }
  const apk = pages.replace(/^https?:\/\//, '').replace(/\/?$/, '/') + 'app.apk';
  card.appendChild(el('p', '', 'Auf dem Fire-TV-Stick in der App „Downloader“ eingeben (Feld vorher ganz leeren):'));
  card.appendChild(bigValue(apk));
  card.appendChild(el('p', 'hint', 'Gleicher Link für Updates – die neue Version wird einfach drüber installiert. Vorher einmalig: VLC installieren und bei „Apps unbekannter Herkunft“ Downloader erlauben.'));
  return card;
}

// Links einer Playlist (nach Veröffentlichen oder über "Geräte-Links")
function linksBox(r) {
  const card = el('div', 'lcard');
  const head = el('div', 'lcard-head');
  head.appendChild(el('h3', '', '② Playlist „' + r.name + '“ einrichten'));
  head.appendChild(el('span', 'chip', r.entries !== undefined
    ? `${r.entries} Einträge · EPG ${r.epgChannels} Sender · ${r.sizeKb} KB`
    : (r.published ? 'veröffentlicht' : 'noch nicht veröffentlicht')));
  card.appendChild(head);

  if (r.setupRef) {
    const tv = section('📺', 'Fire-TV-App', 'Beim ersten Start der App eingeben, dann „Komplett“ oder „Senioren“ wählen. Später erneut: Menü-Taste ☰ auf der Fernbedienung.');
    tv.appendChild(bigValue(r.setupRef));
    card.appendChild(tv);
  }

  if (r.webLinks) {
    const web = section('📱', 'iPhone, iPad, Computer', 'QR-Code mit der Kamera scannen oder Link auf dem Gerät öffnen. Fürs iPhone danach in Safari: Teilen → „Zum Home-Bildschirm“.');
    const cols = el('div', 'lcols');
    [['Komplett', r.webLinks.komplett], ['Senioren', r.webLinks.senioren]].forEach(([name, url]) => {
      const col = el('div', 'lcol');
      col.appendChild(el('strong', '', name));
      col.appendChild(qrEl(url, ''));
      const btns = el('div', 'lbtns');
      const copy = el('button', '', 'Link kopieren');
      copy.type = 'button';
      copy.onclick = () => { navigator.clipboard.writeText(url); toast('Kopiert'); };
      const open = el('button', '', 'Öffnen');
      open.type = 'button';
      open.onclick = () => window.open(url, '_blank');
      btns.append(copy, open);
      col.appendChild(btns);
      cols.appendChild(col);
    });
    web.appendChild(cols);
    card.appendChild(web);
  } else if (r.setupRef) {
    card.appendChild(el('p', 'hint', 'Für Links und QR-Codes fürs iPhone die Adresse der Webapp in den Einstellungen eintragen.'));
  } else {
    card.appendChild(el('p', 'hint', 'Noch nicht über GitHub veröffentlicht – Token in den Einstellungen eintragen und „Veröffentlichen“.'));
  }

  if (r.appLinks) {
    const other = section('📡', 'Andere IPTV-Apps (z. B. IPTV Smarters auf dem Samsung)',
      'In der App „Playlist hinzufügen“ → „M3U-URL“ wählen und diese Adresse eintragen. Das Programm (EPG) findet die App meist selbst; sonst die zweite Adresse als EPG-/XMLTV-URL eintragen. Enthält die Zugangsdaten – nicht weitergeben.');
    other.appendChild(el('p', 'hint', 'Playlist (M3U):'));
    other.appendChild(bigValue(r.appLinks.m3u));
    other.appendChild(el('p', 'hint', 'Programm (XMLTV/EPG):'));
    other.appendChild(bigValue(r.appLinks.xmltv));
    card.appendChild(other);
  }

  const test = el('details', 'ltest');
  test.appendChild(el('summary', '', '🔧 Zum Testen (WLAN / dieser Mac)'));
  if (r.lanLinks) {
    test.appendChild(el('p', 'hint', 'Im WLAN – „Start-Webapp“ muss auf dem Mac laufen:'));
    test.appendChild(linkRow('Komplett:', r.lanLinks.komplett));
    test.appendChild(linkRow('Senioren:', r.lanLinks.senioren));
  }
  test.appendChild(el('p', 'hint', 'Auf diesem Mac:'));
  test.appendChild(linkRow('Komplett:', r.macLinks.komplett));
  test.appendChild(linkRow('Senioren:', r.macLinks.senioren));
  card.appendChild(test);

  if (r.warnings && r.warnings.length) {
    const ul = el('ul', 'warn');
    r.warnings.forEach((w) => ul.appendChild(el('li', '', w)));
    card.appendChild(ul);
  }
  return card;
}

async function showLinks() {
  const dlg = $('#dlg-publish');
  const body = $('#publish-body');
  $('#publish-title').textContent = 'Geräte-Links';
  body.textContent = '';
  let res;
  try { res = await api('/api/links'); } catch (e) { return toast(e.message, true); }
  if (!res.results.length) body.appendChild(el('p', '', 'Noch keine Playlist angelegt.'));
  if (!settings.hasToken) body.appendChild(el('p', 'hint', 'Für Links „über GitHub“ in den Einstellungen ein Token eintragen und einmal veröffentlichen.'));
  body.appendChild(appCard());
  res.results.forEach((r) => body.appendChild(linksBox(r)));
  body.appendChild(el('p', 'hint', 'Die Links bleiben gleich. Nach Änderungen an einer Playlist nur „Veröffentlichen“ – die Geräte holen sich die neue Fassung selbst.'));
  dlg.showModal();
}

async function loadSettings() {
  const data = await api('/api/state');
  settings = data.settings;
  return data;
}

function openSettings() {
  const f = $('#form-settings');
  f.reset();
  f.elements.pagesUrl.value = settings.pagesUrl || '';
  f.elements.autoPublish.checked = !!settings.autoPublish;
  f.elements.autoTime.value = settings.autoTime || '04:00';
  if (settings.autoResult) $('#auto-status').textContent = 'Zuletzt automatisch: ' + settings.autoResult;
  f.elements.token.placeholder = settings.hasToken
    ? `ghp_••••••••${settings.tokenEnd || ''} (gespeichert – leer lassen = behalten)`
    : 'Token hier einfügen';
  $('#token-status').textContent = settings.hasToken
    ? `Token gespeichert${settings.login ? ' · GitHub-Konto: ' + settings.login : ''}${settings.gistId ? ' · Gist vorhanden' : ''}`
    : 'Noch kein Token – Playlists werden nur lokal erzeugt.';
  const dlg = $('#dlg-settings');
  dlg.returnValue = '';
  dlg.onclose = async () => {
    try {
      if (dlg.returnValue === 'save') {
        await api('/api/settings', {
          token: f.elements.token.value, pagesUrl: f.elements.pagesUrl.value,
          autoPublish: f.elements.autoPublish.checked, autoTime: f.elements.autoTime.value
        });
      } else if (dlg.returnValue === 'clear') {
        if (!confirm('Token entfernen?')) return;
        await api('/api/settings', { clearToken: true });
      } else return;
      await loadSettings();
      toast('Einstellungen gespeichert');
    } catch (e) { toast(e.message, true); }
  };
  dlg.showModal();
}

// ---------- Reihenfolge im Reiter „Programm“ ----------

// Live-Sender der Playlist mit EPG-Kennung, je Kennung einmal; gespeicherte Reihenfolge zuerst.
function epgChannelsOf(pl) {
  const byId = new Map();
  pl.groups.forEach((g) => g.items.forEach((item) => {
    if (item.variants) return;
    const ch = lookup(item.key);
    if (!ch || ch.type !== 'live' || !ch.tvgId || byId.has(ch.tvgId)) return;
    byId.set(ch.tvgId, { id: ch.tvgId, name: item.name || ch.name, logo: ch.logo });
  }));
  const first = (pl.epgOrder || []).filter((id) => byId.has(id));
  const rest = [...byId.keys()].filter((id) => !first.includes(id));
  return first.concat(rest).map((id) => byId.get(id));
}

async function openEpgOrder() {
  const pl = playlist();
  if (!pl) return toast('Zuerst eine Playlist anlegen.', true);
  try {
    await busy(() => Promise.all([...playlistSources(pl)].map((sid) => loadCatalog(sid))));
  } catch (e) { return toast(e.message, true); }
  const list = epgChannelsOf(pl);
  if (!list.length) return toast('In dieser Playlist gibt es keine Sender mit Programmdaten (EPG).', true);

  const box = $('#epg-list');
  let dragIndex = null;
  const render = () => {
    box.textContent = '';
    list.forEach((c, i) => {
      const row = el('div', 'epg-row');
      row.draggable = true;
      row.appendChild(el('span', 'handle', '⠿'));
      row.appendChild(el('span', 'pos', i + 1));
      row.appendChild(logo(c.logo));
      row.appendChild(el('span', 'nm', c.name));
      [['↑', -1, 'Nach oben'], ['↓', 1, 'Nach unten']].forEach(([t, d, title]) => {
        const b = el('button', '', t);
        b.type = 'button';
        b.title = title;
        b.disabled = !list[i + d];
        b.onclick = () => { list.splice(i + d, 0, list.splice(i, 1)[0]); render(); };
        row.appendChild(b);
      });
      row.addEventListener('dragstart', (ev) => {
        dragIndex = i;
        row.classList.add('dragging');
        ev.dataTransfer.effectAllowed = 'move';
        ev.dataTransfer.setData('text/plain', 'x');
      });
      row.addEventListener('dragend', () => { dragIndex = null; row.classList.remove('dragging'); });
      row.addEventListener('dragover', (ev) => { if (dragIndex !== null) { ev.preventDefault(); row.classList.add('drag-over'); } });
      row.addEventListener('dragleave', () => row.classList.remove('drag-over'));
      row.addEventListener('drop', (ev) => {
        ev.preventDefault();
        if (dragIndex === null || dragIndex === i) return render();
        const [c2] = list.splice(dragIndex, 1);
        list.splice(dragIndex < i ? i - 1 : i, 0, c2);
        render();
      });
      box.appendChild(row);
    });
  };
  render();
  $('#epg-tv').onclick = () => {
    const pos = new Map(list.map((c, i) => [c, i]));
    const rank = (c) => { const k = sortKey(c.name); return TV_RANK.has(k) ? TV_RANK.get(k) : 1e6 + pos.get(c); };
    list.sort((a, b) => rank(a) - rank(b) || qualityRank(a.name) - qualityRank(b.name));
    render();
  };

  const dlg = $('#dlg-epg');
  dlg.onclose = () => {
    if (dlg.returnValue === 'save') {
      pl.epgOrder = list.map((c) => c.id);
      toast('Programm-Reihenfolge gespeichert – wirkt nach dem nächsten Veröffentlichen');
    } else if (dlg.returnValue === 'reset') {
      delete pl.epgOrder;
      toast('Programm wieder wie die Playlist sortiert – wirkt nach dem nächsten Veröffentlichen');
    } else return;
    save();
  };
  dlg.showModal();
}

// ---------- Zugang, Verbindungen, Qualität ----------

const sourceInfos = {}; // sourceId -> Antwort von /api/source-info (oder {error})

async function loadSourceInfo(id) {
  if (!id) return;
  try {
    sourceInfos[id] = await api('/api/source-info', { id });
  } catch (e) {
    sourceInfos[id] = { error: e.message };
  }
  if (id === ui.sourceId) renderSrcInfo();
  renderDeviceWarning();
}

const RATING_CLASS = { gut: 'good', mittel: 'mid', schlecht: 'bad' };
const RATING_COLOR = { gut: 'var(--ok)', mittel: '#c27c00', schlecht: 'var(--danger)' };

function infoRow(box, k, v, cls) {
  const r = el('div', 'row');
  r.appendChild(el('span', 'k', k));
  r.appendChild(el('span', cls || '', v));
  box.appendChild(r);
}

function renderSrcInfo() {
  const box = $('#src-info');
  box.textContent = '';
  const src = source();
  if (!src) return;
  const info = sourceInfos[src.id];
  if (!info) { box.appendChild(el('div', 'k', 'Prüfe Zugang …')); return; }
  if (info.error) {
    box.appendChild(el('div', 'bad', 'Anbieter nicht erreichbar'));
    box.appendChild(el('div', 'k', info.error));
    return;
  }
  if (info.type === 'xtream') {
    infoRow(box, 'Status', info.auth ? (info.status || 'aktiv') : 'Anmeldung fehlgeschlagen', info.auth ? 'good' : 'bad');
    if (info.expires) {
      const days = Math.round((info.expires * 1000 - Date.now()) / 86400000);
      infoRow(box, 'Gültig bis', new Date(info.expires * 1000).toLocaleDateString('de-DE') + ` (${days} Tage)`,
        days < 0 ? 'bad' : days < 14 ? 'mid' : '');
    }
    if (info.maxConnections) {
      const full = info.activeConnections >= info.maxConnections;
      infoRow(box, 'Verbindungen', `${info.activeConnections ?? '?'} von ${info.maxConnections} belegt`, full ? 'mid' : '');
    }
    infoRow(box, 'Antwortzeit', info.apiMs + ' ms', info.apiMs > 1500 ? 'mid' : '');
    if (info.serverMoved) {
      const a = el('div', 'alert', `Anbieter meldet die Adresse „${info.reportedServer}“.`);
      const b = el('button', '', 'Adresse übernehmen');
      b.type = 'button';
      b.onclick = async () => {
        src.server = 'http://' + info.reportedServer;
        save();
        toast('Adresse übernommen – lade Quelle neu …');
        await refreshSource();
      };
      box.append(a, b);
    }
  }
  const q = info.quality || [];
  const last = q[q.length - 1];
  if (last) {
    infoRow(box, 'Qualität', `${last.rating} · Start ${(last.startMs / 1000 || 0).toFixed(1)} s · ${last.mbit} Mbit/s`,
      RATING_CLASS[last.rating]);
    infoRow(box, 'Getestet', new Date(last.t * 1000).toLocaleString('de-DE') + (last.failed ? ` · ${last.failed}/${last.tested} ohne Bild` : ''));
    const spark = el('div', 'spark');
    spark.title = 'Verlauf der letzten Tests';
    q.forEach((t) => {
      const bar = el('span');
      bar.style.height = { gut: '16px', mittel: '10px', schlecht: '5px' }[t.rating] || '5px';
      bar.style.background = RATING_COLOR[t.rating] || 'var(--muted)';
      bar.title = `${new Date(t.t * 1000).toLocaleString('de-DE')}: ${t.rating}`;
      spark.appendChild(bar);
    });
    box.appendChild(spark);
  } else {
    infoRow(box, 'Qualität', 'noch nicht getestet');
  }
  const stop = el('button', '', 'Streams des Editors beenden');
  stop.type = 'button';
  stop.title = 'Beendet Player-Fenster und VLC, die vom Editor gestartet wurden – gibt die Verbindung beim Anbieter frei';
  stop.onclick = stopStreams;
  box.appendChild(stop);
  const btn = el('button', '', 'Qualität testen');
  btn.type = 'button';
  btn.title = 'Spielt 3 Sender je ca. 5 Sekunden an. Belegt kurz eine Verbindung.';
  btn.onclick = () => testQuality(src);
  box.appendChild(btn);
}

async function stopStreams() {
  stopVideo();
  $('#player').hidden = true;
  playing = null;
  try {
    const r = await api('/api/stop-streams', {});
    toast('Beendet' + (r.vlc ? ' (auch VLC)' : '') + '. Der Anbieter gibt die Verbindung meist nach 10–60 Sekunden frei.');
  } catch (e) { toast(e.message, true); }
  const src = source();
  if (src) setTimeout(() => loadSourceInfo(src.id), 15000);
}

async function testQuality(src) {
  // bevorzugt Sender aus den eigenen Playlists dieser Quelle
  const keys = [];
  state.playlists.forEach((p) => p.groups.forEach((g) => g.items.forEach((i) => {
    if (i.key.startsWith(src.id + ':') && !i.variants) keys.push(i.key);
  })));
  keys.sort(() => Math.random() - 0.5);
  await busy(async () => {
    toast('Teste Sender … (ca. 20 Sekunden)');
    try {
      const res = await api('/api/quality', { id: src.id, keys: keys.slice(0, 30) });
      const s = res.summary;
      toast(`Qualität: ${s.rating} – Start ${((s.startMs || 0) / 1000).toFixed(1)} s, ${s.mbit} Mbit/s` +
        (s.failed ? `, ${s.failed} von ${s.tested} ohne Bild` : ''), s.rating === 'schlecht');
    } catch (e) {
      toast(e.message, true);
    }
  });
  loadSourceInfo(src.id);
}

// Quellen, aus denen eine Playlist Einträge enthält
function playlistSources(pl) {
  const ids = new Set();
  pl.groups.forEach((g) => g.items.forEach((i) => ids.add(i.key.split(':', 1)[0])));
  return ids;
}

function renderDeviceWarning() {
  const box = $('#pl-warning');
  const pl = playlist();
  box.hidden = true;
  if (!pl) return;
  const msgs = [];
  playlistSources(pl).forEach((sid) => {
    const info = sourceInfos[sid];
    const src = state.sources.find((s) => s.id === sid);
    if (!info || !info.maxConnections || !src) return;
    const users = state.playlists.filter((p) => playlistSources(p).has(sid));
    const need = users.reduce((n, p) => n + (p.devices || 1), 0);
    if (need > info.maxConnections) {
      msgs.push(`„${src.name}“ erlaubt nur ${info.maxConnections} gleichzeitige Verbindung(en), ` +
        `geplant sind ${need} Geräte (${users.map((p) => `${p.name}: ${p.devices || 1}`).join(', ')}). ` +
        'Schauen mehr Geräte gleichzeitig, bricht der Anbieter Streams ab.');
    }
  });
  if (msgs.length) { box.textContent = msgs.join(' '); box.hidden = false; }
}

// Nach dem Aktualisieren: Einträge, deren Kennung der Anbieter geändert hat, über den Namen wiederfinden.
function repairPlaylists(sid) {
  const c = catalogs[sid];
  if (!c) return { fixed: 0, missing: 0 };
  const byName = new Map();
  c.items.forEach((it) => {
    const k = it.type + '|' + it.name.trim().toLowerCase();
    if (!byName.has(k)) byName.set(k, []);
    byName.get(k).push(it);
  });
  let fixed = 0;
  let missing = 0;
  const typeOf = (key) => key.split(':')[1];
  state.playlists.forEach((pl) => pl.groups.forEach((g) => g.items.forEach((item) => {
    if (!item.key.startsWith(sid + ':')) return;
    if (item.variants) {
      const t = parseTitle(item.label);
      const work = c.works.get(typeOf(item.key) + '|' + t.base + '|' + t.year) || [];
      item.variants.forEach((v) => {
        if (c.byKey.has(v.key)) return;
        const hit = work.find((w) => variantLabel(w) === v.lang) || work.find((w) => w.lang === v.lang.split(' ')[0]);
        if (hit) { v.key = hit.key; fixed++; } else missing++;
      });
      item.variants = item.variants.filter((v) => c.byKey.has(v.key));
      if (item.variants.length) item.key = item.variants[0].key;
      return;
    }
    if (c.byKey.has(item.key)) return;
    const cands = byName.get(typeOf(item.key) + '|' + (item.label || '').trim().toLowerCase()) || [];
    const hit = cands.find((x) => x.group === item.sg) || cands[0]; // gleiche Herkunftsgruppe bevorzugen
    if (hit) { item.key = hit.key; item.sg = hit.group; fixed++; } else missing++;
  })));
  if (fixed) save();
  return { fixed, missing };
}

// ---------- Player im Editor ----------

const HLS_JS = 'https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.20/hls.min.js';
const BROWSER_FORMATS = ['m3u8', 'mp4', 'm4v', 'mov', 'webm'];
let hls = null;
let playing = null; // aktueller Katalog-Eintrag

function loadScript(src) {
  return new Promise((resolve, reject) => {
    if (document.querySelector(`script[src="${src}"]`)) return resolve();
    const s = document.createElement('script');
    s.src = src;
    s.onload = resolve;
    s.onerror = () => reject(new Error('Player-Bibliothek nicht ladbar'));
    document.head.appendChild(s);
  });
}

function playerMsg(text) {
  const m = $('#player-msg');
  m.textContent = text || '';
  m.hidden = !text;
}

function stopVideo() {
  const v = $('#player-video');
  if (hls) { hls.destroy(); hls = null; }
  v.removeAttribute('src');
  v.load();
}

function playButton(it, title) {
  const b = el('button', 'play', '▶');
  b.type = 'button';
  b.title = 'Abspielen';
  b.onclick = (ev) => { ev.stopPropagation(); openPlayer(it, title); };
  return b;
}

async function openPlayer(it, title) {
  if (!it) return toast('Eintrag ist in der Quelle nicht vorhanden.', true);
  playing = it;
  $('#player').hidden = false;
  $('#player-title').textContent = title || it.title || it.name;
  stopVideo();
  playerMsg('');
  const v = $('#player-video');
  const src = '/api/play?key=' + encodeURIComponent(it.key);
  const ext = it.ext || (it.type === 'series' ? '' : 'm3u8');
  if (ext && !BROWSER_FORMATS.includes(ext)) {
    playerMsg(`Format „.${ext}“ kann der Browser nicht abspielen – oben „VLC“ wählen.`);
    return;
  }
  try {
    if (ext === 'm3u8' && !v.canPlayType('application/vnd.apple.mpegurl')) {
      await loadScript(HLS_JS);
      hls = new Hls();
      hls.on(Hls.Events.ERROR, (e, d) => { if (d.fatal) playerMsg('Stream nicht abspielbar (' + d.details + ') – „VLC“ versuchen.'); });
      hls.loadSource(src);
      hls.attachMedia(v);
    } else {
      v.src = src;
    }
    await v.play().catch(() => {});
  } catch (e) {
    playerMsg(e.message);
  }
}

function bindPlayer() {
  const panel = $('#player');
  const v = $('#player-video');
  v.addEventListener('error', () => {
    if (!v.getAttribute('src')) return;
    const hevc = playing && /HEVC|H\.?265/i.test(playing.name || '');
    playerMsg(hevc
      ? 'HEVC-Sender (H.265) kann der Browser meist nicht abspielen – „VLC“ wählen oder die HD-Fassung des Senders nehmen.'
      : 'Stream nicht abspielbar – „VLC“ versuchen. (Ist beim Anbieter evtl. schon die erlaubte Verbindung belegt?)');
  });
  v.addEventListener('playing', () => playerMsg(''));
  $('#player-close').onclick = () => { stopVideo(); panel.hidden = true; playing = null; };
  $('#player-pop').onclick = () => {
    if (!playing) return;
    const p = new URLSearchParams({ key: playing.key, title: $('#player-title').textContent, ext: playing.ext || '' });
    window.open('watch.html?' + p, 'iptv-watch', 'width=1000,height=600');
    stopVideo(); // nur eine Verbindung gleichzeitig belegen
    panel.hidden = true;
  };
  $('#player-vlc').onclick = async () => {
    if (!playing) return;
    stopVideo();
    try { await api('/api/vlc', { key: playing.key }); toast('In VLC geöffnet'); panel.hidden = true; } catch (e) { toast(e.message, true); }
  };
  // Fenster an der Kopfzeile verschieben
  let drag = null;
  $('#player-head').addEventListener('mousedown', (ev) => {
    if (ev.target.tagName === 'BUTTON') return;
    const r = panel.getBoundingClientRect();
    drag = { dx: ev.clientX - r.left, dy: ev.clientY - r.top };
    ev.preventDefault();
  });
  document.addEventListener('mousemove', (ev) => {
    if (!drag) return;
    panel.style.left = Math.max(0, ev.clientX - drag.dx) + 'px';
    panel.style.top = Math.max(0, ev.clientY - drag.dy) + 'px';
    panel.style.right = 'auto';
    panel.style.bottom = 'auto';
  });
  document.addEventListener('mouseup', () => { drag = null; });
}

// ---------- Start ----------

function renderAll() {
  renderSourceSelect();
  renderPlaylistSelect();
  document.querySelectorAll('.types button').forEach((b) => b.classList.toggle('active', b.dataset.type === ui.type));
  $('#by-title-wrap').hidden = ui.type === 'live';
  $('#search').placeholder = { live: 'Sender suchen …', movie: 'Filme suchen (z. B. Dune) …', series: 'Serien suchen (z. B. Chicago PD) …' }[ui.type];
  renderCountries();
  renderGroups();
  renderChannels();
  renderPlaylist();
  renderSrcInfo();
  renderDeviceWarning();
  renderPlSource();
  $('#pl-devices').value = (playlist() || {}).devices || 1;
  $('#pl-devices').disabled = !playlist();
}

function bind() {
  bindPlayer();
  $('#source').onchange = async (ev) => {
    const prev = ui.sourceId;
    ui.sourceId = ev.target.value;
    ui.group = null;
    ui.selected.clear();
    await busy(() => loadCatalog(ui.sourceId)).catch((e) => toast(e.message, true));
    // vorherige Quelle wieder auf die Playlist-Einträge verkleinern (Speicher)
    if (prev && prev !== ui.sourceId && catalogs[prev] && !catalogs[prev].partial) {
      await loadCatalog(prev, true, sourceKeys(prev)).catch(() => null);
    }
    renderAll();
    if (!sourceInfos[ui.sourceId]) loadSourceInfo(ui.sourceId);
  };
  $('#src-add').onclick = () => openSourceDialog(null);
  $('#src-edit').onclick = () => { if (source()) openSourceDialog(source()); };
  $('#src-refresh').onclick = refreshSource;
  document.querySelectorAll('#form-source input[name="type"]').forEach((r) => { r.onchange = syncSourceType; });
  document.querySelectorAll('#form-source [data-epg]').forEach((b) => {
    b.onclick = () => { $('#form-source').elements.epgUrl.value = b.dataset.epg; };
  });

  $('#playlist').onchange = (ev) => {
    ui.playlistId = ev.target.value;
    ui.targetGroupId = null;
    renderAll();
    if (!checks[ui.playlistId]) loadCheck(ui.playlistId);
  };
  $('#pl-devices').onchange = (ev) => {
    const pl = playlist();
    if (!pl) return;
    pl.devices = Math.max(1, Math.min(20, parseInt(ev.target.value, 10) || 1));
    ev.target.value = pl.devices;
    save();
    renderDeviceWarning();
  };
  $('#pl-add').onclick = newPlaylist;
  $('#pl-source').onchange = (ev) => { if (ev.target.value) switchPlSource(ev.target.value); };
  $('#pl-rename').onclick = () => {
    const pl = playlist();
    if (!pl) return;
    const name = prompt('Playlist umbenennen:', pl.name);
    if (name && name.trim()) { pl.name = name.trim(); save(); renderPlaylistSelect(); }
  };
  $('#pl-delete').onclick = () => {
    const pl = playlist();
    if (!pl || !confirm(`Playlist „${pl.name}“ löschen?`)) return;
    state.playlists = state.playlists.filter((p) => p.id !== pl.id);
    ui.playlistId = state.playlists[0] ? state.playlists[0].id : null;
    save();
    renderAll();
  };
  $('#group-add').onclick = () => {
    const pl = playlist();
    if (!pl) return toast('Zuerst eine Playlist anlegen.', true);
    const name = prompt('Name der neuen Gruppe:');
    if (!name || !name.trim()) return;
    const g = { id: 'g' + uid(), name: name.trim(), items: [] };
    pl.groups.push(g);
    ui.targetGroupId = g.id;
    save();
    renderPlaylist();
  };

  document.querySelectorAll('.types button').forEach((b) => {
    b.onclick = () => { ui.type = b.dataset.type; ui.group = null; ui.selected.clear(); renderAll(); };
  });

  let searchTimer;
  $('#search').oninput = (ev) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { ui.search = ev.target.value; ui.selected.clear(); renderChannels(); }, 200);
  };
  $('#select-all').onchange = (ev) => {
    channelList().slice(0, MAX_ROWS).forEach((it) => (ev.target.checked ? ui.selected.add(it.key) : ui.selected.delete(it.key)));
    renderChannels();
  };
  $('#invert').onclick = () => {
    channelList().slice(0, MAX_ROWS).forEach((it) => (ui.selected.has(it.key) ? ui.selected.delete(it.key) : ui.selected.add(it.key)));
    renderChannels();
  };
  $('#add-selected').onclick = () => {
    const items = catalogEntries([...ui.selected]);
    if (!items.length) return toast('Keine Sender ausgewählt.', true);
    addToPlaylist(items, null);
  };
  $('#by-title').onchange = () => { ui.selected.clear(); renderChannels(); };
  $('#publish').onclick = () => publish(false);
  $('#publish-all').onclick = () => publish(true);
  $('#check').onclick = checkPlaylist;
  $('#sel-top').onclick = () => moveSelToEdge(true);
  $('#sel-bottom').onclick = () => moveSelToEdge(false);
  $('#sel-move').onchange = (ev) => { if (ev.target.value) moveSelToGroup(ev.target.value); };
  $('#sel-remove').onclick = () => {
    const pl = playlist();
    if (!pl || !confirm(`${ui.plSel.size} Einträge aus der Playlist entfernen?`)) return;
    pl.groups.forEach((g) => { g.items = g.items.filter((i) => !ui.plSel.has(i)); });
    ui.plSel.clear();
    save();
    renderPlaylist();
    renderChannels();
  };
  $('#sel-clear').onclick = () => { ui.plSel.clear(); renderPlaylist(); };
  // Tastatur: ⌥↑/⌥↓ eine Position, ⌥⇧↑/⌥⇧↓ ganz nach oben/unten, Esc = Auswahl aufheben
  document.addEventListener('keydown', (ev) => {
    if (!ui.plSel.size || (ev.target.closest && ev.target.closest('input, select, textarea, dialog'))) return;
    if (ev.key === 'Escape') { ui.plSel.clear(); renderPlaylist(); return; }
    if (!ev.altKey || (ev.key !== 'ArrowUp' && ev.key !== 'ArrowDown')) return;
    ev.preventDefault();
    const up = ev.key === 'ArrowUp';
    if (ev.shiftKey) moveSelToEdge(up); else moveSelStep(up ? -1 : 1);
  });
  // Beim Ziehen am oberen/unteren Rand der Playlist mitscrollen
  $('#pl-groups').addEventListener('dragover', (ev) => {
    const box = $('#pl-groups');
    const r = box.getBoundingClientRect();
    if (ev.clientY < r.top + 50) box.scrollTop -= 18;
    else if (ev.clientY > r.bottom - 50) box.scrollTop += 18;
  });
  $('#links').onclick = showLinks;
  $('#settings').onclick = openSettings;
  $('#epg-order').onclick = openEpgOrder;
}

async function start() {
  bind();
  try {
    const data = await loadSettings();
    if (data.version !== SERVER_VERSION) {
      toast('Der Editor wurde aktualisiert: Bitte das Terminal-Fenster des Editors schließen und „Start-Editor“ neu starten.', true);
      setTimeout(() => toast('Editor bitte neu starten (Terminal-Fenster schließen, „Start-Editor“ doppelklicken).', true), 7500);
    }
    state = data.state;
    state.sources = state.sources || [];
    state.playlists = state.playlists || [];
    ui.sourceId = state.sources[0] ? state.sources[0].id : null;
    ui.playlistId = state.playlists[0] ? state.playlists[0].id : null;
    // Gewählte Quelle ganz laden, von den anderen nur die Einträge der Playlists (nacheinander)
    await busy(async () => {
      await loadCatalog(ui.sourceId).catch((e) => toast(e.message, true));
      for (const s of state.sources) {
        if (s.id === ui.sourceId) continue;
        const keys = sourceKeys(s.id);
        if (keys.length) await loadCatalog(s.id, false, keys).catch(() => null);
      }
    });
  } catch (e) {
    toast(e.message, true);
  }
  // Bestehende Einträge an die aktuelle Erkennung anpassen: Titel ("-DE - Titel" -> "Titel") und
  // Fassungen neu ermitteln (alle Qualitäten je Sprache, z. B. "DE" und "DE 4K")
  let langFixed = 0;
  state.playlists.forEach((pl) => pl.groups.forEach((g) => g.items.forEach((i) => {
    if (!i.variants) return;
    const c = catalogs[i.key.split(':', 1)[0]];
    if (!c || c.partial) return;   // Fassungen nur mit vollständigem Katalog neu ermitteln
    const it = [i.key, ...i.variants.map((v) => v.key)].map(lookup).find((x) => x && x.wk);
    if (it) {
      const vs = variantsOf(it).map((v) => ({ key: v.key, lang: variantLabel(v) }));
      if (vs.length && JSON.stringify(vs) !== JSON.stringify(i.variants)) {
        i.variants = vs;
        i.key = vs[0].key;
        langFixed++;
      }
    }
    if (i.label && LANG_PRE.test(i.label)) { i.label = parseTitle(i.label).title; langFixed++; }
  })));
  if (langFixed) save();
  renderAll();
  state.sources.forEach((s) => loadSourceInfo(s.id));
  if (ui.playlistId) loadCheck(ui.playlistId);
  // Läuft gerade eine Aufgabe (z. B. nachts gestartet oder Seite neu geladen)? Fortschritt zeigen.
  if (settings.job && settings.job.running) {
    $('#publish-title').textContent = settings.job.label;
    $('#dlg-publish').showModal();
    followJob($('#publish-body'), settings.job.kind === 'check')
      .then(() => { $('#publish-body').appendChild(el('p', '', 'Fertig.')); })
      .catch((e) => { $('#publish-body').appendChild(el('p', 'warn', e.message)); });
  }
}

start();
