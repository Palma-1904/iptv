#!/usr/bin/env python3
"""Lokaler Playlist-Editor für die IPTV-Webapp.

Läuft nur auf diesem Mac (127.0.0.1). Holt Senderlisten aus Xtream-Zugängen oder M3U-Dateien,
speichert eigene Playlists und veröffentlicht sie samt verkleinertem EPG als Secret Gist.
Nur Python-Standardbibliothek, nichts zu installieren.

Daten (inkl. Zugangsdaten und GitHub-Token) liegen in editor/data/ – nicht ins Git einchecken.
"""
import gzip
import hashlib
import json
import os
import re
import shutil
import ssl
import sys
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
import xml.etree.ElementTree as ET
from datetime import datetime
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, 'data')
CACHE = os.path.join(DATA, 'cache')
OUT = os.path.join(DATA, 'out')
STATE_FILE = os.path.join(DATA, 'state.json')
SETTINGS_FILE = os.path.join(DATA, 'settings.json')
WEBAPP = os.path.dirname(HERE)                 # Webapp-Ordner (eine Ebene über editor/)
LOCAL_OUT = os.path.join(WEBAPP, 'lokal')      # Listen zum Testen im WLAN (per .gitignore ausgeschlossen)
WEBAPP_PORT = 8765                             # Port von Start-Webapp.command
PORT = int(os.environ.get('EDITOR_PORT', '8790'))
VERSION = 16  # bei Änderungen an Server UND Oberfläche erhöhen (editor.js: SERVER_VERSION)
STATIC = {'/': 'index.html', '/index.html': 'index.html', '/editor.js': 'editor.js', '/editor.css': 'editor.css',
          '/watch.html': 'watch.html'}

UA = 'Mozilla/5.0 (Macintosh) IPTV-Playlist-Editor'
EPG_TTL = 6 * 3600          # EPG-Download höchstens alle 6 Stunden
SERIES_TTL = 24 * 3600      # Episodenlisten einen Tag zwischenspeichern
EPG_PAST = 1 * 3600         # EPG-Fenster: 1 Stunde zurück ...
EPG_FUTURE = 36 * 3600      # ... bis 36 Stunden voraus
# Zusätzliche Programmquellen (XMLTV): füllen nur Lücken, das EPG des Anbieters hat immer Vorrang.
# Zuordnung über tvg-id oder Sendernamen (Sender ohne tvg-id bekommen die Kennung der Zusatzquelle).
EXTRA_EPG = [
    'https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz',
    'https://www.open-epg.com/files/germany.xml.gz',
]

for d in (DATA, CACHE, OUT, LOCAL_OUT, os.path.join(CACHE, 'series')):
    os.makedirs(d, exist_ok=True)


# ---------- Hilfsfunktionen ----------

def make_ssl_context():
    """python.org-Python bringt oft keine Zertifikate mit -> macOS-Bundle verwenden."""
    ctx = ssl.create_default_context()
    if ctx.cert_store_stats().get('x509_ca', 0) == 0 and os.path.exists('/etc/ssl/cert.pem'):
        ctx = ssl.create_default_context(cafile='/etc/ssl/cert.pem')
    return ctx


SSL_CTX = make_ssl_context()


class UserError(Exception):
    """Fehler mit verständlicher Meldung für die Oberfläche."""


def http(url, data=None, headers=None, method=None, timeout=90):
    req = urllib.request.Request(url, data=data, method=method, headers={'User-Agent': UA, **(headers or {})})
    try:
        return urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX)
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8', 'replace')[:300]
        raise UserError(f'HTTP {e.code} von {urllib.parse.urlsplit(url).netloc}: {body}')
    except urllib.error.URLError as e:
        raise UserError(f'Keine Verbindung zu {urllib.parse.urlsplit(url).netloc}: {e.reason}')


def fetch(url, **kw):
    with http(url, **kw) as r:
        raw = r.read()
    return gzip.decompress(raw) if raw[:2] == b'\x1f\x8b' else raw


def download(url, path):
    """Große Dateien (EPG) direkt auf die Platte streamen."""
    tmp = path + '.part'
    with http(url, timeout=300) as r, open(tmp, 'wb') as f:
        shutil.copyfileobj(r, f, 1024 * 1024)
    os.replace(tmp, path)


def read_json(path, default):
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def write_json(path, obj):
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, separators=(',', ':'))
    os.replace(tmp, path)


def fresh(path, ttl):
    return os.path.exists(path) and time.time() - os.path.getmtime(path) < ttl


def load_state():
    return read_json(STATE_FILE, {'sources': [], 'playlists': []})


def load_settings():
    return read_json(SETTINGS_FILE, {})


def find(items, id_, what):
    for x in items:
        if x.get('id') == id_:
            return x
    raise UserError(f'{what} nicht gefunden.')


def attr(v):
    return str(v or '').replace('"', "'").replace('\n', ' ').strip()


def lan_ip():
    """IP-Adresse dieses Macs im WLAN (für Links, die iPad/Fire TV öffnen)."""
    import socket
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sk:
            sk.connect(('192.0.2.1', 9))   # sendet nichts, ermittelt nur die Route
            return sk.getsockname()[0]
    except OSError:
        return None


def setup_links(base_url, list_ref):
    q = list_ref
    return {'komplett': f'{base_url}#{q}&ansicht=komplett', 'senioren': f'{base_url}#{q}&ansicht=senioren'}


def slugify(s):
    s = s.lower().translate(str.maketrans({'ä': 'ae', 'ö': 'oe', 'ü': 'ue', 'ß': 'ss'}))
    return re.sub(r'[^a-z0-9]+', '-', s).strip('-') or 'liste'


# ---------- Quellen: Xtream ----------

def xtream_base(src):
    s = src.get('server', '').strip().rstrip('/')
    if not s:
        raise UserError('Server-Adresse fehlt.')
    if not re.match(r'^https?://', s, re.I):
        s = 'http://' + s
    return s


def xtream_api(src, action=None, **params):
    q = {'username': src.get('username', ''), 'password': src.get('password', '')}
    if action:
        q['action'] = action
    q.update(params)
    try:
        raw = fetch(xtream_base(src) + '/player_api.php?' + urllib.parse.urlencode(q))
    except UserError as e:
        # Hinweis auf die zuletzt vom Anbieter gemeldete Adresse (falls er umgezogen ist)
        last = read_json(os.path.join(CACHE, f'serverinfo_{src.get("id")}.json'), {})
        known = last.get('url')
        if known and known not in xtream_base(src):
            raise UserError(f'{e} – Der Anbieter meldete zuletzt die Adresse „{known}“. Bitte in der Quelle prüfen.')
        raise
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        raise UserError('Server antwortet nicht im Xtream-Format. Adresse prüfen.')


def xtream_catalog(src):
    info = xtream_api(src)
    if not isinstance(info, dict) or str(info.get('user_info', {}).get('auth')) != '1':
        raise UserError('Anmeldung fehlgeschlagen. Benutzername/Passwort prüfen.')
    base, u, p = xtream_base(src), src['username'], src['password']
    fmt = src.get('liveFormat') or 'm3u8'
    sid = src['id']
    out = []

    def cats(action):
        return {str(c.get('category_id')): c.get('category_name') or 'Sonstige' for c in (xtream_api(src, action) or [])}

    live_cats = cats('get_live_categories')
    for s in xtream_api(src, 'get_live_streams') or []:
        out.append({
            'key': f'{sid}:live:{s.get("stream_id")}', 'type': 'live', 'name': s.get('name') or '',
            'group': live_cats.get(str(s.get('category_id')), 'Sonstige'),
            'logo': s.get('stream_icon') or '', 'tvgId': s.get('epg_channel_id') or '',
            'url': f'{base}/live/{u}/{p}/{s.get("stream_id")}.{fmt}',
        })
    vod_cats = cats('get_vod_categories')
    for s in xtream_api(src, 'get_vod_streams') or []:
        ext = s.get('container_extension') or 'mp4'
        out.append({
            'key': f'{sid}:movie:{s.get("stream_id")}', 'type': 'movie', 'name': s.get('name') or '',
            'group': vod_cats.get(str(s.get('category_id')), 'Sonstige'),
            'logo': s.get('stream_icon') or '', 'tvgId': '',
            'url': f'{base}/movie/{u}/{p}/{s.get("stream_id")}.{ext}',
        })
    ser_cats = cats('get_series_categories')
    for s in xtream_api(src, 'get_series') or []:
        out.append({
            'key': f'{sid}:series:{s.get("series_id")}', 'type': 'series', 'name': s.get('name') or '',
            'group': ser_cats.get(str(s.get('category_id')), 'Sonstige'),
            'logo': s.get('cover') or '', 'tvgId': '', 'url': '',
        })
    epg = (src.get('epgUrl') or '').strip() or \
        f'{base}/xmltv.php?' + urllib.parse.urlencode({'username': u, 'password': p})
    return out, epg


def xtream_episodes(src, series_id):
    """Episoden einer Serie (zwischengespeichert)."""
    path = os.path.join(CACHE, 'series', f'{src["id"]}_{series_id}.json')
    if fresh(path, SERIES_TTL):
        return read_json(path, [])
    info = xtream_api(src, 'get_series_info', series_id=series_id)
    base, u, p = xtream_base(src), src['username'], src['password']
    eps = []
    episodes = info.get('episodes') if isinstance(info, dict) else None
    if isinstance(episodes, dict):
        episodes = [e for season in episodes.values() for e in season]
    for e in episodes or []:
        season = int(e.get('season') or 0)
        num = int(e.get('episode_num') or 0)
        title = (e.get('title') or '').strip()
        # Anbieter-Titel wie "DE-4K - Serie (2021) (US) - S01E01 - Folgenname" -> "Folgenname"
        m = re.search(r'S\d{1,3}\s*E\d{1,4}\s*[-–:]\s*(.+)$', title, re.I)
        if m:
            title = m.group(1).strip()
        elif re.search(r'S\d{1,3}\s*E\d{1,4}\s*$', title, re.I):
            title = ''
        ext = e.get('container_extension') or 'mp4'
        eps.append({'season': season, 'episode': num, 'title': title,
                    'url': f'{base}/series/{u}/{p}/{e.get("id")}.{ext}'})
    eps.sort(key=lambda x: (x['season'], x['episode']))
    write_json(path, eps)
    return eps


# ---------- Quellen: M3U ----------

ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')
SERIES_NAME = re.compile(r'\bs\d{1,2}\s*e\d{1,3}\b', re.I)
SERIES_GROUP = re.compile(r'(^|[^a-z])(series?|serien|staffel|season|tv[ -]?shows?)([^a-z]|$)')
MOVIE_GROUP = re.compile(r'(^|[^a-z])(vod|movies?|filme?|kino|cinema)([^a-z]|$)')


def classify(name, group, url, hint):
    hint = (hint or '').lower()
    if hint in ('live', 'movie', 'series'):
        return hint
    g, u = group.lower(), url.lower()
    if '/series/' in u or SERIES_GROUP.search(g) or SERIES_NAME.search(name):
        return 'series_ep'   # einzelne Episode aus einer M3U
    if '/movie/' in u or MOVIE_GROUP.search(g) or re.search(r'\.(mp4|mkv|avi|mov)(\?|$)', u):
        return 'movie'
    return 'live'


def title_comma(line):
    q = False
    for i, c in enumerate(line):
        if c == '"':
            q = not q
        elif c == ',' and not q:
            return i
    return -1


def m3u_catalog(src):
    loc = (src.get('url') or '').strip()
    if not loc:
        raise UserError('M3U-Adresse fehlt.')
    if re.match(r'^https?://', loc, re.I):
        text = fetch(loc).decode('utf-8', 'replace')
    else:
        try:
            with open(os.path.expanduser(loc), encoding='utf-8', errors='replace') as f:
                text = f.read()
        except OSError as e:
            raise UserError(f'Datei nicht lesbar: {e}')
    if '#EXTM3U' not in text[:1000] and '#EXTINF' not in text:
        raise UserError('Das ist keine M3U-Playlist.')
    epg = src.get('epgUrl') or ''
    m = re.search(r'#EXTM3U[^\n]*(?:x-tvg-url|url-tvg)="([^"]+)"', text)
    if m and not epg:
        epg = m.group(1).split(',')[0].strip()

    out, seen, cur = [], set(), None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith('#EXTINF'):
            i = title_comma(line)
            head, name = (line[:i], line[i + 1:].strip()) if i >= 0 else (line, '')
            a = {k.lower(): v.strip() for k, v in ATTR_RE.findall(head)}
            cur = {'name': name or a.get('tvg-name', ''), 'group': a.get('group-title', ''),
                   'logo': a.get('tvg-logo', ''), 'tvgId': a.get('tvg-id', ''), 'hint': a.get('tvg-type', '')}
        elif line.startswith('#EXTGRP:') and cur and not cur['group']:
            cur['group'] = line[8:].strip()
        elif not line.startswith('#'):
            if cur and re.match(r'^https?://', line, re.I) and line not in seen:
                seen.add(line)
                group = cur['group'] or 'Sonstige'
                t = classify(cur['name'], group, line, cur['hint'])
                out.append({
                    'key': f'{src["id"]}:m3u:{hashlib.sha1(line.encode()).hexdigest()[:14]}',
                    'type': 'movie' if t == 'movie' else ('series' if t == 'series_ep' else 'live'),
                    'episode': t == 'series_ep',
                    'name': cur['name'] or line, 'group': group, 'logo': cur['logo'],
                    'tvgId': cur['tvgId'], 'url': line,
                })
            cur = None
    return out, epg


def refresh_source(src):
    items, epg = xtream_catalog(src) if src.get('type') == 'xtream' else m3u_catalog(src)
    old = load_catalog(src['id'])
    cat = {'source': src['id'], 'updated': int(time.time()), 'epg': epg, 'items': items}
    write_json(os.path.join(CACHE, f'catalog_{src["id"]}.json'), cat)
    counts = {}
    for it in items:
        counts[it['type']] = counts.get(it['type'], 0) + 1
    # Was hat der Anbieter seit der letzten Aktualisierung geändert?
    changes = None
    if old and old.get('items'):
        before = {it['key']: it['type'] for it in old['items']}
        now = {it['key']: it['type'] for it in items}
        changes = {}
        for t in ('live', 'movie', 'series'):
            added = sum(1 for k, v in now.items() if v == t and k not in before)
            removed = sum(1 for k, v in before.items() if v == t and k not in now)
            if added or removed:
                changes[t] = {'added': added, 'removed': removed}
        changes['since'] = old.get('updated')
    return {'updated': cat['updated'], 'counts': counts, 'epg': bool(epg), 'changes': changes}


# ---------- Zugang & Qualität ----------

def source_info(src):
    """Abo-Status, Verbindungen und gemeldete Server-Adresse (nur Xtream)."""
    if src.get('type') != 'xtream':
        return {'type': 'm3u'}
    t0 = time.time()
    info = xtream_api(src)
    api_ms = int((time.time() - t0) * 1000)
    ui = info.get('user_info') or {}
    si = info.get('server_info') or {}
    def num(v):
        try:
            return int(v)
        except (TypeError, ValueError):
            return None
    reported = (si.get('url') or '').strip()
    if reported:
        write_json(os.path.join(CACHE, f'serverinfo_{src["id"]}.json'), {'url': reported, 'port': si.get('port'), 't': int(time.time())})
    configured = urllib.parse.urlsplit(xtream_base(src)).hostname or ''
    return {
        'type': 'xtream', 'auth': str(ui.get('auth')) == '1', 'status': ui.get('status'),
        'expires': num(ui.get('exp_date')), 'maxConnections': num(ui.get('max_connections')),
        'activeConnections': num(ui.get('active_cons')), 'trial': str(ui.get('is_trial')) == '1',
        'apiMs': api_ms, 'reportedServer': reported,
        'serverMoved': bool(reported and configured and reported.lower() != configured.lower()),
    }


def measure_stream(url, seconds=4.0, max_bytes=6 * 1024 * 1024):
    """Startzeit (bis zu den ersten Videodaten) und Datenrate eines Streams messen."""
    t0 = time.time()
    try:
        if url.split('?')[0].lower().endswith('.m3u8'):
            def get_playlist(u):
                with http(u, timeout=15) as r:
                    return r.read().decode('utf-8', 'replace'), r.geturl()
            text, final = get_playlist(url)
            uris = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith('#')]
            if '#EXT-X-STREAM-INF' in text and uris:          # Master-Playlist -> erste Qualitätsstufe
                text, final = get_playlist(urllib.parse.urljoin(final, uris[0]))
                uris = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith('#')]
            if not uris:
                return {'ok': False, 'error': 'leere Playlist'}
            seg = urllib.parse.urljoin(final, uris[-1])
            t1 = time.time()
            with http(seg, timeout=15) as r:
                first = r.read(64 * 1024)
                start_ms = int((time.time() - t0) * 1000)
                got = len(first)
                while got < max_bytes:
                    chunk = r.read(256 * 1024)
                    if not chunk:
                        break
                    got += len(chunk)
            dur = max(time.time() - t1, 0.05)
        else:
            with http(url, timeout=15) as r:                   # MPEG-TS: läuft in Echtzeit
                first = r.read(64 * 1024)
                start_ms = int((time.time() - t0) * 1000)
                t1, got = time.time(), len(first)
                while time.time() - t1 < seconds and got < max_bytes:
                    chunk = r.read(128 * 1024)
                    if not chunk:
                        break
                    got += len(chunk)
            dur = max(time.time() - t1, 0.05)
        return {'ok': got > 0, 'startMs': start_ms, 'mbit': round(got * 8 / dur / 1e6, 1)}
    except UserError as e:
        return {'ok': False, 'error': str(e)[:120]}
    except Exception as e:  # Abbruch, Zeitüberschreitung …
        return {'ok': False, 'error': f'{type(e).__name__}: {e}'[:120]}


def rate(start_ms, mbit, fail_ratio):
    if fail_ratio > 0.34 or start_ms is None:
        return 'schlecht'
    if start_ms <= 2000 and mbit >= 8 and fail_ratio == 0:
        return 'gut'
    if start_ms <= 5000 and mbit >= 3:
        return 'mittel'
    return 'schlecht'


def quality_test(src, keys):
    """Spielt einige Sender kurz an (nacheinander, wegen Verbindungslimit) und speichert das Ergebnis."""
    if src.get('type') == 'xtream':
        info = source_info(src)
        mx, act = info.get('maxConnections'), info.get('activeConnections')
        if mx and act is not None and act >= mx:
            raise UserError(f'Gerade laufen {act} von {mx} erlaubten Verbindungen. '
                            'Der Test würde einen laufenden Stream unterbrechen – bitte später testen.')
    cat = load_catalog(src['id'])
    if not cat:
        raise UserError('Quelle zuerst laden („Aktualisieren“).')
    index = {it['key']: it for it in cat['items']}
    sample = [index[k] for k in keys if k in index and index[k]['type'] == 'live'][:3]
    if len(sample) < 3:
        import random
        # Event-/PPV-Sender senden außerhalb von Events nichts -> für den Test ungeeignet
        bad = re.compile(r'EVENT|PPV|NUR W[ÄA]HREND|REPLAY|8K|NO STREAM', re.I)
        live = [it for it in cat['items'] if it['type'] == 'live' and not bad.search(it['name'] + ' ' + (it['group'] or ''))]
        pool = [it for it in live if re.match(r'^\W*DE\W', it['group'] or '')] or live
        random.shuffle(pool)
        sample += [it for it in pool if it not in sample][:3 - len(sample)]
    if not sample:
        raise UserError('Keine Live-Sender zum Testen gefunden.')
    results = []
    for it in sample:
        r = measure_stream(it['url'])
        r['name'] = it['name']
        results.append(r)
    ok = [r for r in results if r['ok']]
    from statistics import median
    summary = {
        't': int(time.time()), 'tested': len(results), 'failed': len(results) - len(ok),
        'startMs': int(median(r['startMs'] for r in ok)) if ok else None,
        'mbit': round(median(r['mbit'] for r in ok), 1) if ok else 0,
    }
    summary['rating'] = rate(summary['startMs'], summary['mbit'], summary['failed'] / len(results))
    path = os.path.join(DATA, f'quality_{src["id"]}.json')
    history = read_json(path, [])
    history.append(summary)
    write_json(path, history[-30:])
    return {'summary': summary, 'results': results, 'history': history[-30:]}


# Gleicher Sender in anderer Fassung: Name ohne Land, Qualität und Satzzeichen (wie sortKey in editor.js)
TV_ALIAS = {
    'ard': 'daserste', 'pro7': 'prosieben', 'kabel1': 'kabeleins', 'rtl2': 'rtlzwei', 'rtlii': 'rtlzwei',
    'rtlnitro': 'nitro', 'brfernsehen': 'br', 'bayerischesfernsehen': 'br', 'hrfernsehen': 'hr', 'swrbw': 'swr',
    'swrrp': 'swr', 'swrfernsehen': 'swr', 'srfernsehen': 'sr', 'ndrfernsehen': 'ndr', 'wdrfernsehen': 'wdr',
    'mdrfernsehen': 'mdr', 'rbbfernsehen': 'rbb', 'kabel1doku': 'kabeleinsdoku', 'welttv': 'welt',
    'eurosport': 'eurosport1', 'disney': 'disneychannel',
}
CHANNEL_PREFIX = re.compile(r'^\s*([A-Za-z]{2,6})\s*[|:]\s*')
CHANNEL_QUALITY = re.compile(r'(^|[^a-z0-9])(4k|uhd|fhd|hd|sd|hevc|h\.?265|raw|50fps|60fps|backup|[ᴴᴰᴿᴬᵂᴳᴼᴸ⁶⁰ᶠᵖˢ]+)(?=[^a-z0-9]|$)')
EVENT_NAME = re.compile(r'EVENT|PPV|NUR W[ÄA]HREND|NO EVENT|REPLAY|ᴴᴰ ◉|\b8K\b', re.I)


def channel_key(name):
    k = CHANNEL_PREFIX.sub('', (name or '').lower())
    k = re.sub(r'\(.*?\)|\[.*?\]', ' ', k)
    k = CHANNEL_QUALITY.sub(' ', k)
    k = re.sub(r'[^a-z0-9äöüß]+', '', k)
    return TV_ALIAS.get(k, k)


def channel_prefix(name):
    m = CHANNEL_PREFIX.match(name or '')
    return m.group(1).upper() if m else ''


def quality_rank(name):
    """Kleiner = besser geeignet: HD/FHD vor SD, 4K und HEVC zuletzt (Datenrate, Browser)."""
    n = (name or '').upper()
    if re.search(r'HEVC|H\.?265', n):
        return 3
    if re.search(r'4K|UHD|2160', n):
        return 4
    if re.search(r'\bSD\b', n):
        return 2
    return 0 if re.search(r'FHD|1080', n) else 1


def find_alternatives(dead, index, used):
    """Andere Fassungen desselben Senders aus dem Katalog, beste zuerst."""
    k = channel_key(dead['name'])
    cands = {it['key']: it for it in index['by_name'].get(k, [])}
    if dead.get('tvgId'):
        cands.update({it['key']: it for it in index['by_tvg'].get(dead['tvgId'], [])})
    cands.pop(dead['key'], None)
    pre = channel_prefix(dead['name'])
    out = [it for it in cands.values() if not EVENT_NAME.search(it['name'] + ' ' + (it.get('group') or ''))]
    out.sort(key=lambda it: (channel_prefix(it['name']) != pre, it['key'] in used,
                             bool(re.search(r'RAW|ᴿᴬᵂ', it['name'])), quality_rank(it['name'])))
    return out


def catalog_index(sid):
    items = [x for x in (load_catalog(sid) or {'items': []})['items'] if x['type'] == 'live']
    by_name, by_tvg = {}, {}
    for it in items:
        by_name.setdefault(channel_key(it['name']), []).append(it)
        if it.get('tvgId'):
            by_tvg.setdefault(it['tvgId'], []).append(it)
    return {'keys': {it['key']: it for it in items}, 'by_name': by_name, 'by_tvg': by_tvg}


def check_playlist(pl_id, keys=None):
    """Live-Sender einer Playlist kurz anspielen (nacheinander, wegen Verbindungslimit); für Sender ohne Bild
    andere Fassungen desselben Senders suchen und anspielen (Ersatz). keys: nur diese Einträge prüfen
    (z. B. aus „Doppelte“), das Ergebnis wird mit dem bisherigen zusammengeführt.
    Ergebnis in data/check_<id>.json; Event-/PPV-Kanäle werden gesondert markiert."""
    state = load_state()
    pl = find(state['playlists'], pl_id, 'Playlist')
    sources = {s['id']: s for s in state['sources']}
    indexes, items, used = {}, [], set()
    for g in pl.get('groups', []):
        for it in g.get('items', []):
            if it.get('variants'):
                continue
            used.add(it['key'])
            if keys is not None and it['key'] not in keys:
                continue
            sid = it['key'].split(':', 1)[0]
            if sid not in indexes:
                indexes[sid] = catalog_index(sid)
            ch = indexes[sid]['keys'].get(it['key'])
            if ch:
                items.append((it['key'], it.get('name') or ch['name'], ch, sid))
    if not items:
        raise UserError('In dieser Playlist gibt es keine Live-Sender zum Prüfen.')
    for sid in {x[3] for x in items}:
        src = sources.get(sid)
        if src and src.get('type') == 'xtream':
            info = source_info(src)
            mx, act = info.get('maxConnections'), info.get('activeConnections')
            if mx and act is not None and act >= mx:
                raise UserError(f'Gerade laufen {act} von {mx} erlaubten Verbindungen (es wird ferngesehen). '
                                'Bitte später prüfen, z. B. nachts.')
    busy = re.compile(r'HTTP (403|429|458|503|509)\b')

    def test(url, label, i):
        r = measure_stream(url, seconds=1.0, max_bytes=192 * 1024)
        tries = 0
        # Verbindungslimit: der Anbieter gibt die letzte Verbindung erst nach einigen Sekunden frei
        while not r.get('ok') and busy.search(r.get('error') or '') and tries < 3 and not JOB.get('cancel'):
            tries += 1
            job_progress(f'{label} (warte auf freie Verbindung …)', i, len(items))
            time.sleep(20)
            r = measure_stream(url, seconds=1.0, max_bytes=192 * 1024)
        time.sleep(0.5)
        return r

    results = {}
    for i, (key, name, ch, sid) in enumerate(items):
        if JOB.get('cancel'):
            break
        job_progress(f'Prüfe {name}', i, len(items))
        r = test(ch['url'], f'Prüfe {name}', i)
        res = {'ok': bool(r.get('ok')), 'err': (r.get('error') or '')[:120],
               'event': bool(EVENT_NAME.search(name)), 'name': name}
        if not res['ok'] and not res['event']:
            # Ersatz: andere Fassungen desselben Senders, bis zu 4 anspielen, die erste mit Bild nehmen
            tried = 0
            for alt in find_alternatives({**ch, 'name': ch['name']}, indexes[sid], used)[:4]:
                if JOB.get('cancel'):
                    break
                tried += 1
                job_progress(f'Suche Ersatz für {name}: {alt["name"]}', i, len(items))
                ra = test(alt['url'], f'Ersatz {alt["name"]}', i)
                if ra.get('ok'):
                    res['alt'] = {'key': alt['key'], 'name': alt['name'], 'mbit': ra.get('mbit'),
                                  'inPlaylist': alt['key'] in used}
                    break
            res['altTried'] = tried
        results[key] = res
    path = os.path.join(DATA, f'check_{pl_id}.json')
    if keys is not None:                       # Teilprüfung: bisherige Ergebnisse behalten
        old = read_json(path, {}).get('results', {})
        old.update(results)
        results_all = old
    else:
        results_all = results
    out = {'t': int(time.time()), 'checked': len(results), 'total': len(items),
           'cancelled': bool(JOB.get('cancel')), 'results': results_all}
    write_json(path, out)
    bad = [v for v in results.values() if not v['ok'] and not v['event']]
    return {'checked': len(results), 'total': len(items), 'bad': len(bad), 'cancelled': out['cancelled'],
            'withAlt': sum(1 for v in bad if v.get('alt')),
            'events': sum(1 for v in results.values() if not v['ok'] and v['event'])}


# ---------- Stand der App: online, GitHub-Bau, nicht gepushte Änderungen ----------

APP_STATUS = {'t': 0, 'data': None}


def app_status():
    """Für die Anzeige im Editor (alle 20 s neu): Version online (app-version.txt), letzter Bau-Lauf auf GitHub
    (öffentliche API, ohne Token) und lokale Commits, die noch nicht gepusht sind."""
    if APP_STATUS['data'] and time.time() - APP_STATUS['t'] < 20:
        return APP_STATUS['data']
    pages = (load_settings().get('pagesUrl') or 'https://palma-1904.github.io/iptv/').strip()
    if not pages.endswith('/'):
        pages += '/'
    m = re.match(r'https?://([^.]+)\.github\.io/([^/]+)/', pages)
    repo = f'{m.group(1)}/{m.group(2)}' if m else 'Palma-1904/iptv'
    out = {'actionsUrl': f'https://github.com/{repo}/actions', 'online': None, 'run': None, 'ahead': None}
    try:
        out['online'] = int(fetch(f'{pages}app-version.txt?t={int(time.time())}', timeout=10).decode().strip())
    except Exception:
        pass
    try:
        runs = json.loads(fetch(f'https://api.github.com/repos/{repo}/actions/runs?per_page=1', timeout=10))
        r = (runs.get('workflow_runs') or [None])[0]
        if r:
            out['run'] = {'number': r['run_number'], 'status': r['status'], 'conclusion': r['conclusion'],
                          'url': r['html_url'], 'msg': ((r.get('head_commit') or {}).get('message') or '').split('\n')[0]}
    except Exception:
        pass
    try:
        import subprocess
        res = subprocess.run(['git', '-C', os.path.dirname(HERE), 'rev-list', '--count', '@{u}..HEAD'],
                             capture_output=True, text=True, timeout=10)
        if res.returncode == 0:
            out['ahead'] = int(res.stdout.strip() or 0)
    except Exception:
        pass
    APP_STATUS.update(t=time.time(), data=out)
    return out


# ---------- Nachts automatisch veröffentlichen ----------

def auto_publish_loop():
    """Läuft im Hintergrund, solange der Editor läuft: einmal täglich ab der eingestellten Uhrzeit."""
    while True:
        time.sleep(60)
        try:
            s = load_settings()
            if not s.get('autoPublish'):
                continue
            now = datetime.now()
            today = now.strftime('%Y-%m-%d')
            hh, mm = (s.get('autoTime') or '04:00').split(':')
            if s.get('lastAuto') == today or (now.hour, now.minute) < (int(hh), int(mm)) or JOB.get('running'):
                continue
            s['lastAuto'] = today
            write_json(SETTINGS_FILE, s)

            def run():
                res = publish([])
                st = load_settings()
                warn = sum(len(r.get('warnings') or []) for r in res['results'])
                st['autoResult'] = (f'{now.strftime("%d.%m. %H:%M")}: {len(res["results"])} Playlists '
                                    + ('hochgeladen' if res['uploaded'] else 'NICHT hochgeladen')
                                    + (f', {warn} Hinweise' if warn else ''))
                write_json(SETTINGS_FILE, st)
                return res
            start_job('publish', 'Automatisch veröffentlichen (nachts)', run)
        except Exception as e:  # nie den Editor stören
            print('Automatisch veröffentlichen fehlgeschlagen:', e)


def switch_source_map(keys, target_id):
    """Playlist-Einträge auf einen anderen Zugang desselben Anbieters umstellen:
    gleiche Stream-Nummer im Ziel-Katalog, sonst gleicher Name und Typ. Liefert {alt: neu} und Fehlende."""
    target = load_catalog(target_id)
    if not target:
        raise UserError('Den Ziel-Zugang bitte zuerst laden („Aktualisieren“).')
    by_key = {it['key']: it for it in target['items']}
    by_name = {}
    for it in target['items']:
        by_name.setdefault((it['type'], (it['name'] or '').strip().lower()), it['key'])
    olds = {}
    mapping, missing = {}, []
    for k in keys:
        sid, rest = k.split(':', 1)
        if sid == target_id:
            continue
        nk = f'{target_id}:{rest}'
        if nk in by_key:
            mapping[k] = nk
            continue
        if sid not in olds:
            olds[sid] = {it['key']: it for it in (load_catalog(sid) or {'items': []})['items']}
        old = olds[sid].get(k)
        hit = old and by_name.get((old['type'], (old['name'] or '').strip().lower()))
        if hit:
            mapping[k] = hit
        else:
            missing.append(old['name'] if old else k)
    return {'mapping': mapping, 'missing': missing}


# ---------- Fernwartung über ntfy.sh (Geräte melden Status, Editor schickt Befehle) ----------

NTFY = 'https://ntfy.sh/'


def stream_norm(url):
    """Adresse ohne Server und Zugangsdaten – so vergleicht auch die App (Memory.norm)."""
    return re.sub(r'^https?://[^/]+/(live|movie|series)/[^/]+/[^/]+/', r'\1/', url or '')


def remote_enable(on):
    s = load_settings()
    if not s.get('token') or not s.get('gistId'):
        raise UserError('Zuerst Token eintragen und einmal veröffentlichen (dann gibt es den Gist).')
    if on:
        if not s.get('remoteTopic'):
            import secrets
            s['remoteTopic'] = 'iptv' + re.sub(r'[^A-Za-z0-9]', '', secrets.token_urlsafe(32))[:28]
        gist_upload({'fernwartung.json': json.dumps({'topic': s['remoteTopic']})}, s)
    else:
        gist_upload({'fernwartung.json': None}, s)
        s.pop('remoteTopic', None)
    write_json(SETTINGS_FILE, s)
    return {'enabled': bool(s.get('remoteTopic'))}


def remote_status():
    topic = load_settings().get('remoteTopic')
    if not topic:
        return {'enabled': False, 'devices': []}
    try:
        text = fetch(NTFY + topic + '-status/json?poll=1&since=12h', timeout=20)
    except UserError as e:
        return {'enabled': True, 'devices': [], 'error': str(e)}
    devices = {}
    for line in text.decode('utf-8', 'replace').splitlines() if isinstance(text, bytes) else text.splitlines():
        try:
            m = json.loads(line)
            if m.get('event') != 'message':
                continue
            d = json.loads(m.get('message') or '{}')
        except ValueError:
            continue
        if not d.get('id'):
            continue
        old = devices.get(d['id'], {})
        if d.get('note'):
            d['lastNote'] = d['note']
        elif old.get('lastNote'):
            d['lastNote'] = old['lastNote']
        if d.get('t', 0) >= old.get('t', 0):
            devices[d['id']] = d
    now = int(time.time())
    out = sorted(devices.values(), key=lambda d: (d.get('list') or '', d.get('name') or ''))
    for d in out:
        d['age'] = now - int(d.get('t') or 0)
    return {'enabled': True, 'devices': out}


def remote_cmd(to, action, arg):
    topic = load_settings().get('remoteTopic')
    if not topic:
        raise UserError('Fernwartung ist nicht eingeschaltet.')
    import uuid
    body = json.dumps({'id': uuid.uuid4().hex[:12], 'to': to, 'action': action, 'arg': arg or {},
                       't': int(time.time())}).encode()
    with http(NTFY + topic + '-cmd', data=body, method='POST', timeout=20) as r:
        r.read()
    return {'ok': True}


def remote_channels(slug):
    """Live-Sender der zuletzt veröffentlichten Playlist (genau das, was die Geräte kennen):
    Name, Gruppe und Adresse ohne Zugangsdaten für „Umschalten auf …“."""
    path = os.path.join(OUT, re.sub(r'[^a-z0-9-]', '', slug) + '.m3u')
    if not os.path.exists(path):
        return {'channels': []}
    out, cur = [], None
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line.startswith('#EXTINF'):
                cur = line if 'tvg-type="live"' in line else None
            elif cur and line.startswith('http'):
                g = re.search(r'group-title="([^"]*)"', cur)
                out.append({'name': cur.rsplit(',', 1)[-1], 'group': g.group(1) if g else '', 'norm': stream_norm(line)})
                cur = None
    return {'channels': out}


# Trennzeilen des Anbieters („##### DOKUS UND NEWS #####“): keine Sender, nur Überschriften in seiner Liste
SEPARATOR = re.compile(r'^\W*#{3,}')


def load_catalog(source_id):
    cat = read_json(os.path.join(CACHE, f'catalog_{source_id}.json'), None)
    if cat and cat.get('items'):
        cat['items'] = [x for x in cat['items'] if not SEPARATOR.match(x.get('name') or '')]
    return cat


def remove_separators():
    """Beim Start: Trennzeilen aus allen Playlists entfernen (früher mit übernommen)."""
    state = load_state()
    n = 0
    for pl in state.get('playlists', []):
        for g in pl.get('groups', []):
            keep = [it for it in g.get('items', []) if not SEPARATOR.match(it.get('name') or it.get('label') or '')]
            n += len(g.get('items', [])) - len(keep)
            g['items'] = keep
    if n:
        write_json(STATE_FILE, state)
        print(f'{n} Trennzeilen („#####…“) aus den Playlists entfernt.')


# ---------- Hintergrund-Aufgaben mit Fortschritt (Veröffentlichen, Sender prüfen) ----------

JOB = {'running': False}
JOB_LOCK = threading.Lock()


def job_progress(step, done=None, total=None):
    JOB.update(step=step, done=done, total=total)


def start_job(kind, label, fn):
    """Aufgabe im Hintergrund starten; die Oberfläche fragt /api/job ab."""
    with JOB_LOCK:
        if JOB.get('running'):
            raise UserError(f'Es läuft gerade: {JOB.get("label")} – bitte warten, bis das fertig ist.')
        JOB.clear()
        JOB.update(kind=kind, label=label, running=True, step='Start …', done=None, total=None,
                   cancel=False, started=time.time())

    def run():
        try:
            JOB['result'] = fn()
        except UserError as e:
            JOB['error'] = str(e)
        except Exception as e:  # unerwartet: trotzdem verständlich melden
            JOB['error'] = f'Interner Fehler: {type(e).__name__}: {e}'
        finally:
            JOB.update(running=False, finished=time.time())

    threading.Thread(target=run, daemon=True).start()
    return job_status()


def job_status():
    st = {k: v for k, v in JOB.items() if k != 'cancel'}
    st['now'] = time.time()
    return st


# ---------- Veröffentlichen ----------

def prefetch_episodes(pl, state, lookup):
    """Folgenlisten aller Serien der Playlist parallel laden (nur Datenabfragen, keine Streams) –
    nacheinander dauerte das bei vielen Serien sehr lange. Danach liegen sie im Zwischenspeicher."""
    from concurrent.futures import ThreadPoolExecutor
    sources = {s['id']: s for s in state['sources']}
    todo = set()
    for g in pl.get('groups', []):
        for item in g.get('items', []):
            for key in [v['key'] for v in item.get('variants') or []] or [item['key']]:
                ch = lookup(key)
                if not ch or ch['type'] != 'series' or ch.get('episode'):
                    continue
                sid, sser = key.split(':', 1)[0], key.rsplit(':', 1)[1]
                src = sources.get(sid)
                if src and src.get('type') == 'xtream' and \
                        not fresh(os.path.join(CACHE, 'series', f'{sid}_{sser}.json'), SERIES_TTL):
                    todo.add((sid, sser))
    if not todo:
        return

    def load(job):
        try:
            xtream_episodes(sources[job[0]], job[1])
        except Exception:
            pass   # Fehler zeigt der normale Durchlauf

    from concurrent.futures import as_completed
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = [pool.submit(load, job) for job in sorted(todo)]
        for i, _ in enumerate(as_completed(futures), 1):
            if i % 5 == 0 or i == len(futures):
                job_progress('Folgenlisten der Serien laden', i, len(futures))


def build_playlist(pl, state, warnings):
    """Erzeugt M3U-Text; liefert außerdem benötigte EPG-IDs je Quelle."""
    catalogs, index = {}, {}

    def lookup(key):
        sid = key.split(':', 1)[0]
        if sid not in catalogs:
            catalogs[sid] = load_catalog(sid) or {'items': [], 'epg': ''}
            for it in catalogs[sid]['items']:
                index[it['key']] = it
        return index.get(key)

    prefetch_episodes(pl, state, lookup)
    lines = ['#EXTM3U']
    epg_ids = {}  # source_id -> set(tvgId)
    live = []     # Live-Sender: Zeile, tvg-id, Namen (für Zusatz-EPG)
    count = 0
    for g in pl.get('groups', []):
        gname = g.get('name') or 'Sonstige'
        for item in g.get('items', []):
            ch = lookup(item['key'])
            if not ch:
                warnings.append(f'„{item.get("label") or item["key"]}“ gibt es in der Quelle nicht mehr.')
                continue
            name = item.get('name') or ch['name']
            sid = item['key'].split(':', 1)[0]
            if item.get('variants'):
                # Ein Werk in mehreren Sprachen: gleiche x-work-Kennung, je Fassung x-lang.
                title = item.get('name') or item.get('label') or ch['name']
                work = attr(item['key'].split(':', 1)[1])   # ohne Quelle: Favoriten bleiben beim Zugangswechsel
                for v in item['variants']:
                    vch = lookup(v['key'])
                    if not vch:
                        warnings.append(f'„{title}“ ({v.get("lang")}) gibt es in der Quelle nicht mehr.')
                        continue
                    lang = attr(v.get('lang') or '')
                    logo = attr(vch.get('logo') or ch.get('logo'))
                    if vch['type'] == 'series' and not vch.get('episode'):
                        src = next((s for s in state['sources'] if s['id'] == sid), None)
                        for e in (xtream_episodes(src, v['key'].rsplit(':', 1)[1]) if src else []):
                            t = f'{title} S{e["season"]:02d} E{e["episode"]:02d}'
                            if e['title'] and e['title'] not in t:
                                t += f' – {e["title"]}'
                            lines.append(f'#EXTINF:-1 tvg-type="series" tvg-logo="{logo}" x-work="{work}" '
                                         f'x-lang="{lang}" group-title="{attr(title)}",{t}')
                            lines.append(e['url'])
                            count += 1
                    else:
                        lines.append(f'#EXTINF:-1 tvg-type="{vch["type"]}" tvg-logo="{logo}" x-work="{work}" '
                                     f'x-lang="{lang}" group-title="{attr(gname)}",{title}')
                        lines.append(vch['url'])
                        count += 1
                continue
            if ch['type'] == 'series' and not ch.get('episode'):
                src = next((s for s in state['sources'] if s['id'] == sid), None)
                if not src:
                    continue
                for e in xtream_episodes(src, item['key'].rsplit(':', 1)[1]):
                    t = f'{name} S{e["season"]:02d} E{e["episode"]:02d}'
                    if e['title'] and e['title'] not in t:
                        t += f' – {e["title"]}'
                    lines.append(f'#EXTINF:-1 tvg-type="series" tvg-logo="{attr(ch["logo"])}" '
                                 f'group-title="{attr(name)}",{t}')
                    lines.append(e['url'])
                    count += 1
                continue
            parts = ['#EXTINF:-1']
            if ch['type'] == 'live':
                live.append({'line': len(lines), 'tvgId': ch.get('tvgId') or '', 'names': [ch['name'], name]})
            if ch.get('tvgId'):
                parts.append(f'tvg-id="{attr(ch["tvgId"])}"')
                epg_ids.setdefault(sid, set()).add(ch['tvgId'])
            parts.append(f'tvg-name="{attr(name)}"')
            if ch.get('logo'):
                parts.append(f'tvg-logo="{attr(ch["logo"])}"')
            parts.append(f'tvg-type="{ch["type"]}"')
            parts.append(f'group-title="{attr(gname)}"')
            lines.append(' '.join(parts) + ',' + name.replace('\n', ' '))
            lines.append(ch['url'])
            count += 1
    return lines, epg_ids, catalogs, count, live


def parse_xmltv_time(s):
    s = (s or '').strip()
    for fmt in ('%Y%m%d%H%M%S %z', '%Y%m%d%H%M%S'):
        try:
            d = datetime.strptime(s[:20] if fmt.endswith('%z') else s[:14], fmt)
            return int(d.timestamp())
        except ValueError:
            continue
    return None


def clean_programmes(progs):
    """Dubletten und Überlappungen aus Anbieter-EPGs entfernen."""
    progs.sort()
    out = []
    for p in progs:
        if out:
            last = out[-1]
            if p[2] == last[2] and p[0] <= last[1] + 600:      # gleiche Sendung doppelt/zerstückelt
                last[1] = max(last[1], p[1])
                continue
            if p[0] < last[1]:                                  # Überlappung: vorherige endet hier
                if p[0] - last[0] < 120:                        # fast gleicher Start -> spätere ersetzt
                    out[-1] = p
                    continue
                last[1] = p[0]
        out.append(p)
    return out


def epg_for_source(source_id, epg_url, wanted, warnings):
    """Liest das (große) XMLTV der Quelle gestreamt und behält nur gewünschte Sender im Zeitfenster."""
    if not epg_url or not wanted:
        return {}
    path = os.path.join(CACHE, f'epg_{source_id}.xml')
    if not fresh(path, EPG_TTL):
        try:
            download(epg_url, path)
        except UserError as e:
            if not os.path.exists(path):
                warnings.append(f'EPG nicht ladbar: {e}')
                return {}
            warnings.append(f'EPG-Aktualisierung fehlgeschlagen, nutze ältere Fassung: {e}')
    with open(path, 'rb') as f:
        gz = f.read(2) == b'\x1f\x8b'
    lower = {w.lower(): w for w in wanted}
    now = time.time()
    lo, hi = now - EPG_PAST, now + EPG_FUTURE
    result = {}
    try:
        stream = gzip.open(path, 'rb') if gz else open(path, 'rb')
        with stream:
            for _, el in ET.iterparse(stream, events=('end',)):
                if el.tag != 'programme':
                    if el.tag == 'channel':
                        el.clear()
                    continue
                cid = lower.get((el.get('channel') or '').lower())
                if cid:
                    start, stop = parse_xmltv_time(el.get('start')), parse_xmltv_time(el.get('stop'))
                    if start and stop and stop > lo and start < hi:
                        title = (el.findtext('title') or '').strip()
                        result.setdefault(cid, []).append([start, stop, title])
                el.clear()
    except ET.ParseError as e:
        warnings.append(f'EPG-Datei fehlerhaft ({e}); EPG unvollständig.')
    for cid, progs in result.items():
        result[cid] = clean_programmes(progs)
    missing = len(wanted) - len(result)
    if missing:
        warnings.append(f'Für {missing} von {len(wanted)} Sendern gibt es keine EPG-Daten.')
    return result


def epg_name_key(s):
    """Sendername -> Vergleichsschlüssel: "DE| SKY SPORT BUNDESLIGA 1 HEVC" -> "skysportbundesliga1"."""
    s = unicodedata.normalize('NFKC', s or '').lower()
    s = re.sub(r'^\s*(de|ger|germany|deutschland)\s*[:|\-]\s*', '', s)
    s = re.sub(r'\(.*?\)|\[.*?\]', ' ', s)
    s = re.sub(r'\b(full ?hd|fhd|uhd|hd|sd|4k|hevc|h\.?265|h\.?264|raw|50fps|60fps|backup|live|\.de|de)\b', ' ', s)
    s = s.replace('&', 'und').replace('+', 'plus').replace('*', '')
    return re.sub(r'[^a-z0-9äöüß]+', '', s)


# Häufige Namensunterschiede zwischen Anbieter und Programmquellen
EPG_ALIASES = [
    (r'^skybundesliga', 'skysportbundesliga'), (r'^skysports', 'skysport'), (r'^ard$', 'daserste'),
    (r'^swrbw$', 'swrbadenwürttemberg'), (r'^swrrp$', 'swrrheinlandpfalz'), (r'^sr$', 'srfernsehen'),
    (r'^skycinemahighlight$', 'skycinemahighlights'), (r'^eurosport2xtra$', 'eurosport2'),
    (r'^pro7', 'prosieben'), (r'^kabel1', 'kabeleins'), (r'^rtl2$', 'rtlzwei'), (r'^ntv$', 'ntv'),
]


def epg_name_keys(name):
    k = epg_name_key(name)
    keys = [k]
    for pat, rep_ in EPG_ALIASES:
        a = re.sub(pat, rep_, k)
        if a != k and a not in keys:
            keys.append(a)
    return [x for x in keys if x]


def load_extra_epg(url, warnings):
    """Zusatzquelle laden (Cache 6 h): ({id_klein: id}, {namensschlüssel: id}, {id: [[start, ende, titel]]})."""
    path = os.path.join(CACHE, 'epg_extra_' + hashlib.sha1(url.encode()).hexdigest()[:12] + '.xml')
    if not fresh(path, EPG_TTL):
        try:
            download(url, path)
        except UserError as e:
            if not os.path.exists(path):
                warnings.append(f'Zusatz-EPG nicht ladbar ({url.split("/")[2]}): {e}')
                return {}, {}, {}
    with open(path, 'rb') as f:
        gz = f.read(2) == b'\x1f\x8b'
    now = time.time()
    lo, hi = now - EPG_PAST, now + EPG_FUTURE
    names, progs = {}, {}
    try:
        stream = gzip.open(path, 'rb') if gz else open(path, 'rb')
        with stream:
            for _, el in ET.iterparse(stream, events=('end',)):
                if el.tag == 'channel':
                    cid = el.get('id') or ''
                    names[cid] = [d.text or '' for d in el.findall('display-name')] + [cid]
                    el.clear()
                elif el.tag == 'programme':
                    start, stop = parse_xmltv_time(el.get('start')), parse_xmltv_time(el.get('stop'))
                    if start and stop and stop > lo and start < hi:
                        title = (el.findtext('title') or '').strip()
                        progs.setdefault(el.get('channel') or '', []).append([start, stop, title])
                    el.clear()
    except (ET.ParseError, OSError, EOFError) as e:
        warnings.append(f'Zusatz-EPG fehlerhaft ({url.split("/")[2]}): {e}')
    by_id = {cid.lower(): cid for cid in progs}
    by_name = {}
    for cid, ns in names.items():
        if cid not in progs:
            continue
        for n in ns:
            by_name.setdefault(epg_name_key(n), cid)
    return by_id, by_name, progs


def fill_from_extra_epg(lines, live, epg, warnings):
    """Sender ohne Programm vom Anbieter aus den Zusatzquellen ergänzen (Anbieter-EPG bleibt unverändert)."""
    todo = [c for c in live if not (c['tvgId'] and c['tvgId'] in epg)]
    if not todo:
        return 0
    filled = 0
    for url in EXTRA_EPG:
        if not todo:
            break
        by_id, by_name, progs = load_extra_epg(url, warnings)
        if not progs:
            continue
        rest = []
        for c in todo:
            cid = by_id.get(c['tvgId'].lower()) if c['tvgId'] else None
            if not cid:
                for n in c['names']:
                    cid = next((by_name[k] for k in epg_name_keys(n) if k in by_name), None)
                    if cid:
                        break
            if not cid:
                rest.append(c)
                continue
            key = c['tvgId']
            if not key:
                # Sender ohne tvg-id: Kennung der Zusatzquelle in die Playlist schreiben
                key = 'x:' + cid
                lines[c['line']] = lines[c['line']].replace('#EXTINF:-1 ', f'#EXTINF:-1 tvg-id="{attr(key)}" ', 1)
                c['tvgId'] = key
            if key not in epg:
                epg[key] = clean_programmes([list(p) for p in progs[cid]])
                filled += 1
        todo = rest
    if filled:
        warnings.append(f'Programm aus Zusatzquellen für {filled} weitere Sender ergänzt.')
    return filled


def gist_upload(files, settings):
    token = settings.get('token')
    if not token:
        return None
    headers = {'Authorization': f'Bearer {token}', 'Accept': 'application/vnd.github+json',
               'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'}
    # content None = Datei im Gist löschen
    body = {'files': {name: ({'content': content} if content is not None else None) for name, content in files.items()}}
    gid = settings.get('gistId')
    if gid:
        url, method = f'https://api.github.com/gists/{gid}', 'PATCH'
    else:
        url, method = 'https://api.github.com/gists', 'POST'
        body.update({'description': 'IPTV Playlists', 'public': False})
    with http(url, data=json.dumps(body).encode(), headers=headers, method=method, timeout=180) as r:
        res = json.loads(r.read())
    settings['gistId'] = res['id']
    settings['login'] = res['owner']['login']
    write_json(SETTINGS_FILE, settings)
    return settings


def publish(ids):
    state, settings = load_state(), load_settings()
    results, files = [], {}
    for pl in state['playlists']:
        if ids and pl['id'] not in ids:
            continue
        warnings = []
        slug = slugify(pl.get('slug') or pl.get('name') or pl['id'])
        JOB['pl'] = pl.get('name')
        job_progress('Einträge zusammenstellen …')
        lines, epg_ids, catalogs, count, live = build_playlist(pl, state, warnings)
        job_progress('Programm (EPG) des Anbieters …')
        epg = {}
        for sid, wanted in epg_ids.items():
            epg.update(epg_for_source(sid, catalogs.get(sid, {}).get('epg'), wanted, warnings))
        job_progress('Programm aus Zusatzquellen …')
        fill_from_extra_epg(lines, live, epg, warnings)
        # Andere IPTV-Apps (z. B. Smarters): Adresse des XMLTV-Programms in die Kopfzeile
        if settings.get('gistId') and settings.get('login'):
            xml_url = f'https://gist.githubusercontent.com/{settings["login"]}/{settings["gistId"]}/raw/{slug}.xml'
            lines[0] = f'#EXTM3U url-tvg="{xml_url}" x-tvg-url="{xml_url}"'
        m3u = '\n'.join(lines) + '\n'
        # Eigene Reihenfolge für den Reiter „Programm“ (sonst wie in der Playlist)
        order = [i for i in pl.get('epgOrder') or [] if i in epg]
        epg_json = json.dumps({'v': 1, 'generated': int(time.time()), 'channels': epg, 'order': order},
                              ensure_ascii=False, separators=(',', ':'))
        for folder in (OUT, LOCAL_OUT):
            with open(os.path.join(folder, slug + '.m3u'), 'w', encoding='utf-8') as f:
                f.write(m3u)
            with open(os.path.join(folder, slug + '.epg.json'), 'w', encoding='utf-8') as f:
                f.write(epg_json)
        xmltv = build_xmltv(epg, live)
        with open(os.path.join(OUT, slug + '.xml'), 'w', encoding='utf-8') as f:
            f.write(xmltv)
        files[slug + '.m3u'] = m3u
        files[slug + '.epg.json'] = epg_json
        files[slug + '.xml'] = xmltv
        results.append({'id': pl['id'], 'name': pl.get('name'), 'slug': slug, 'entries': count,
                        'epgChannels': len(epg), 'sizeKb': round(len(m3u.encode()) / 1024),
                        'warnings': warnings})
    uploaded = None
    if files:
        JOB['pl'] = None
        job_progress('Hochladen zu GitHub …')
        try:
            uploaded = gist_upload(files, settings)
        except UserError as e:
            for r in results:
                r['warnings'].append(f'Hochladen fehlgeschlagen: {e}')
    for r in results:
        r.update(device_links(r['slug'], uploaded or {}))
    return {'results': results, 'uploaded': bool(uploaded)}


def build_xmltv(epg, live):
    """Programm im XMLTV-Format für andere IPTV-Apps (IPTV Smarters, TiviMate …)."""
    from xml.sax.saxutils import escape, quoteattr
    names = {}
    for c in live:
        if c['tvgId'] and c['tvgId'] not in names:
            names[c['tvgId']] = re.sub(r'^\s*[A-Z]{2,3}\|\s*', '', c['names'][-1] or '')

    def t(sec):
        return datetime.utcfromtimestamp(sec).strftime('%Y%m%d%H%M%S') + ' +0000'
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="IPTV-Editor">']
    for cid in epg:
        out.append(f'<channel id={quoteattr(cid)}><display-name>{escape(names.get(cid, cid))}</display-name></channel>')
    for cid, progs in epg.items():
        for start, stop, title in progs:
            out.append(f'<programme start="{t(start)}" stop="{t(stop)}" channel={quoteattr(cid)}>'
                       f'<title lang="de">{escape(title or "")}</title></programme>')
    out.append('</tv>')
    return '\n'.join(out) + '\n'


def device_links(slug, settings):
    """Einrichtungs-Links einer Playlist – ändern sich nicht, solange Gist und Name gleich bleiben."""
    out = {}
    pages = (settings.get('pagesUrl') or load_settings().get('pagesUrl') or '').strip()
    rel = f'm3u=lokal/{slug}.m3u'   # kurz, damit man es auf dem Fire TV abtippen kann
    out['macLinks'] = setup_links(f'http://localhost:{WEBAPP_PORT}/', rel)
    ip = lan_ip()
    if ip:
        out['lanLinks'] = setup_links(f'http://{ip}:{WEBAPP_PORT}/', rel)
    if settings.get('gistId') and settings.get('login'):
        ref = f'{settings["login"]}/{settings["gistId"]}/{slug}'
        out['setupRef'] = ref
        raw = f'https://gist.githubusercontent.com/{settings["login"]}/{settings["gistId"]}/raw/{slug}'
        out['appLinks'] = {'m3u': raw + '.m3u', 'xmltv': raw + '.xml'}   # andere IPTV-Apps
        if pages:
            out['webLinks'] = setup_links(pages.rstrip('/') + '/index.html', 'liste=' + ref)
    return out


def all_device_links():
    settings = load_settings()
    res = []
    for pl in load_state()['playlists']:
        slug = slugify(pl.get('slug') or pl.get('name') or pl['id'])
        r = {'id': pl['id'], 'name': pl.get('name'), 'slug': slug,
             'published': os.path.exists(os.path.join(OUT, slug + '.m3u'))}
        r.update(device_links(slug, settings))
        res.append(r)
    return res


# ---------- Abspielen im Editor (Durchleitung, Zugangsdaten bleiben im Server) ----------

PROXY_HOSTS = set()   # nur Server, die in Playlists des Anbieters vorkamen, werden durchgeleitet
STOP_GEN = [0]        # wird erhöht, um alle laufenden Durchleitungen abzubrechen
ACTIVE_STREAMS = [0]  # Anzahl gerade laufender Durchleitungen
STOP_UNTIL = [0.0]    # nach "Streams beenden" kurz keine neuen Anfragen (stoppt auch eigene Player-Fenster)


def stream_url(key):
    sid = key.split(':', 1)[0]
    cat = load_catalog(sid) or {'items': []}
    it = next((x for x in cat['items'] if x['key'] == key), None)
    if not it:
        raise UserError('Eintrag nicht gefunden – Quelle aktualisieren.')
    if it['type'] == 'series' and not it.get('episode'):
        src = find(load_state()['sources'], sid, 'Quelle')
        eps = xtream_episodes(src, key.rsplit(':', 1)[1])
        if not eps:
            raise UserError('Keine Folgen gefunden.')
        return eps[0]['url']
    return it['url']


def stop_all_streams():
    """Beendet alle Streams, die dieser Editor geöffnet hat (Player-Durchleitung und VLC)."""
    import subprocess
    STOP_GEN[0] += 1
    STOP_UNTIL[0] = time.time() + 8
    vlc = subprocess.run(['pgrep', '-x', 'VLC'], capture_output=True).returncode == 0
    if vlc:
        subprocess.run(['osascript', '-e', 'tell application "VLC" to quit'], capture_output=True, timeout=10)
    return {'proxies': ACTIVE_STREAMS[0], 'vlc': vlc}


def open_in_vlc(url):
    import subprocess
    if not os.path.isdir('/Applications/VLC.app'):
        raise UserError('VLC ist nicht installiert (videolan.org).')
    subprocess.Popen(['open', '-a', 'VLC', url])


# ---------- HTTP-Server ----------

class Handler(SimpleHTTPRequestHandler):
    server_version = 'IPTVEditor'

    def log_message(self, fmt, *args):
        if '/api/' in (args[0] if args else ''):
            sys.stderr.write('%s\n' % (fmt % args))

    def send_json(self, obj, status=200):
        raw = json.dumps(obj, ensure_ascii=False, separators=(',', ':')).encode()
        gz = 'gzip' in (self.headers.get('Accept-Encoding') or '') and len(raw) > 2048
        if gz:
            raw = gzip.compress(raw, 5)
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        if gz:
            self.send_header('Content-Encoding', 'gzip')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return json.loads(self.rfile.read(n) or b'{}')

    def local_request(self):
        # Schutz gegen fremde Webseiten, die per Browser auf localhost zugreifen wollen.
        host = (self.headers.get('Host') or '').split(':')[0]
        origin = self.headers.get('Origin')
        return host in ('localhost', '127.0.0.1') and (not origin or re.match(r'^http://(localhost|127\.0\.0\.1):\d+$', origin))

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path.startswith('/out/'):
            return self.serve_out(path[5:])
        if path in STATIC:
            return self.serve_file(os.path.join(HERE, STATIC[path]))
        if not path.startswith('/api/') or not self.local_request():
            return self.send_error(404)
        if path in ('/api/play', '/api/seg'):
            return self.play(path)
        self.api('GET', path)

    # --- Durchleitung von Streams für den Player im Editor ---
    def play(self, path):
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(self.path).query))
        if time.time() < STOP_UNTIL[0]:
            return self.send_error(410, 'Streams wurden beendet')
        try:
            if path == '/api/play':
                url = stream_url(q.get('key', ''))
                PROXY_HOSTS.add(urllib.parse.urlsplit(url).hostname)
            else:
                url = q.get('u', '')
                if urllib.parse.urlsplit(url).hostname not in PROXY_HOSTS:
                    return self.send_error(403)
            if url.split('?')[0].lower().endswith('.m3u8'):
                return self.proxy_playlist(url)
            return self.proxy_bytes(url)
        except UserError as e:
            self.send_json({'error': str(e)}, 502)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Player geschlossen

    def proxy_playlist(self, url):
        with http(url, timeout=20) as r:
            text = r.read().decode('utf-8', 'replace')
            final = r.geturl()

        def wrap(u):
            absu = urllib.parse.urljoin(final, u.strip())
            PROXY_HOSTS.add(urllib.parse.urlsplit(absu).hostname)
            return '/api/seg?u=' + urllib.parse.quote(absu, safe='')
        out = []
        for line in text.splitlines():
            if line.startswith('#'):
                line = re.sub(r'URI="([^"]+)"', lambda m: 'URI="' + wrap(m.group(1)) + '"', line)
            elif line.strip():
                line = wrap(line)
            out.append(line)
        raw = ('\n'.join(out) + '\n').encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/vnd.apple.mpegurl')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def proxy_bytes(self, url):
        headers = {}
        if self.headers.get('Range'):
            headers['Range'] = self.headers['Range']
        gen = STOP_GEN[0]
        ACTIVE_STREAMS[0] += 1
        try:
            self._copy_stream(url, headers, gen)
        finally:
            ACTIVE_STREAMS[0] -= 1

    def _copy_stream(self, url, headers, gen):
        with http(url, headers=headers, timeout=30) as r:
            self.send_response(r.status)
            for h in ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges'):
                if r.headers.get(h):
                    self.send_header(h, r.headers[h])
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            while STOP_GEN[0] == gen:   # "Streams beenden" bricht hier ab
                chunk = r.read(256 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def do_POST(self):
        path = urllib.parse.urlsplit(self.path).path
        if not path.startswith('/api/') or not self.local_request():
            return self.send_error(404)
        self.api('POST', path)

    def serve_file(self, fp):
        try:
            with open(fp, 'rb') as f:
                raw = f.read()
        except OSError:
            return self.send_error(404)
        ctype = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css'}.get(os.path.splitext(fp)[1], 'application/octet-stream')
        self.send_response(200)
        self.send_header('Content-Type', ctype + '; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def serve_out(self, name):
        # Erzeugte Listen für den lokalen Test der Webapp (andere Portnummer -> CORS nötig).
        if not re.match(r'^[a-z0-9-]+\.(m3u|epg\.json)$', name):
            return self.send_error(404)
        try:
            with open(os.path.join(OUT, name), 'rb') as f:
                raw = f.read()
        except OSError:
            return self.send_error(404)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json' if name.endswith('.json') else 'audio/x-mpegurl')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def api(self, method, path):
        try:
            q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(self.path).query))
            if method == 'GET' and path == '/api/state':
                s = load_settings()
                return self.send_json({'version': VERSION, 'state': load_state(), 'settings': {
                    'hasToken': bool(s.get('token')), 'tokenEnd': (s.get('token') or '')[-4:],
                    'login': s.get('login'), 'gistId': s.get('gistId'),
                    'pagesUrl': s.get('pagesUrl', ''), 'autoPublish': bool(s.get('autoPublish')),
                    'autoTime': s.get('autoTime') or '04:00', 'autoResult': s.get('autoResult', ''),
                    'job': job_status()}})
            if method == 'POST' and path == '/api/state':
                state = self.body()
                if not isinstance(state.get('sources'), list) or not isinstance(state.get('playlists'), list):
                    raise UserError('Ungültige Daten.')
                write_json(STATE_FILE, state)
                return self.send_json({'ok': True})
            if method == 'POST' and path == '/api/settings':
                b, s = self.body(), load_settings()
                if b.get('token'):
                    s['token'] = b['token'].strip()
                if b.get('clearToken'):
                    s.pop('token', None)
                if 'pagesUrl' in b:
                    s['pagesUrl'] = b['pagesUrl'].strip()
                if 'autoPublish' in b:
                    s['autoPublish'] = bool(b['autoPublish'])
                if re.match(r'^\d{1,2}:\d{2}$', b.get('autoTime') or ''):
                    s['autoTime'] = b['autoTime']
                write_json(SETTINGS_FILE, s)
                return self.send_json({'ok': True, 'hasToken': bool(s.get('token'))})
            if method == 'POST' and path == '/api/refresh':
                src = find(load_state()['sources'], self.body().get('id'), 'Quelle')
                return self.send_json(refresh_source(src))
            if method == 'GET' and path == '/api/catalog':
                cat = load_catalog(q.get('id', ''))
                if not cat:
                    return self.send_json({'items': [], 'updated': None})
                # Ohne Stream-Adressen: halbiert die Datenmenge, Zugangsdaten bleiben im Server.
                items = []
                for it in cat['items']:
                    x = {k: v for k, v in it.items() if k != 'url' and v not in ('', False)}
                    m = re.search(r'\.(\w{2,4})(?:\?|$)', it.get('url') or '')
                    if m:
                        x['ext'] = m.group(1).lower()
                    items.append(x)
                return self.send_json({'updated': cat['updated'], 'items': items})
            if method == 'POST' and path == '/api/remote/enable':
                return self.send_json(remote_enable(bool(self.body().get('on'))))
            if method == 'GET' and path == '/api/remote/status':
                return self.send_json(remote_status())
            if method == 'POST' and path == '/api/remote/cmd':
                b = self.body()
                return self.send_json(remote_cmd(b.get('to') or '', b.get('action') or '', b.get('arg')))
            if method == 'GET' and path == '/api/remote/channels':
                return self.send_json(remote_channels(q.get('list', '')))
            if method == 'POST' and path == '/api/switch-source':
                b = self.body()
                find(load_state()['sources'], b.get('source'), 'Quelle')
                return self.send_json(switch_source_map(b.get('keys') or [], b['source']))
            if method == 'POST' and path == '/api/catalog-part':
                # Nur bestimmte Einträge (z. B. die in Playlists verwendeten) – statt 70 MB je Quelle
                b = self.body()
                cat = load_catalog(b.get('id', '')) or {'items': [], 'updated': None}
                want = set(b.get('keys') or [])
                items = [{k: v for k, v in it.items() if k != 'url' and v not in ('', False)}
                         for it in cat['items'] if it['key'] in want]
                return self.send_json({'updated': cat.get('updated'), 'items': items, 'partial': True})
            if method == 'POST' and path == '/api/source-info':
                src = find(load_state()['sources'], self.body().get('id'), 'Quelle')
                info = source_info(src)
                info['quality'] = read_json(os.path.join(DATA, f'quality_{src["id"]}.json'), [])[-10:]
                return self.send_json(info)
            if method == 'POST' and path == '/api/quality':
                b = self.body()
                src = find(load_state()['sources'], b.get('id'), 'Quelle')
                return self.send_json(quality_test(src, b.get('keys') or []))
            if method == 'GET' and path == '/api/links':
                return self.send_json({'results': all_device_links()})
            if method == 'POST' and path == '/api/stop-streams':
                return self.send_json(stop_all_streams())
            if method == 'POST' and path == '/api/vlc':
                open_in_vlc(stream_url(self.body().get('key', '')))
                return self.send_json({'ok': True})
            if method == 'POST' and path == '/api/publish':
                ids = self.body().get('ids') or []
                names = [p.get('name') for p in load_state()['playlists'] if p['id'] in ids]
                return self.send_json(start_job('publish', 'Veröffentlichen' + (f' „{", ".join(names)}“' if names else ' (alle)'),
                                                lambda: publish(ids)))
            if method == 'GET' and path == '/api/job':
                return self.send_json(job_status())
            if method == 'POST' and path == '/api/job/cancel':
                JOB['cancel'] = True
                return self.send_json({'ok': True})
            if method == 'POST' and path == '/api/check':
                b = self.body()
                pid, keys = b.get('id'), b.get('keys')
                keys = set(keys) if isinstance(keys, list) else None
                name = find(load_state()['playlists'], pid, 'Playlist').get('name')
                return self.send_json(start_job('check', f'Sender prüfen „{name}“', lambda: check_playlist(pid, keys)))
            if method == 'GET' and path == '/api/app-status':
                return self.send_json(app_status())
            if method == 'GET' and path == '/api/check':
                return self.send_json(read_json(os.path.join(DATA, f'check_{q.get("id", "")}.json'), {}))
            self.send_error(404)
        except UserError as e:
            self.send_json({'error': str(e)}, 400)
        except Exception as e:  # unerwartet: trotzdem verständlich melden
            self.send_json({'error': f'Interner Fehler: {type(e).__name__}: {e}'}, 500)


def main():
    remove_separators()
    srv = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    threading.Thread(target=auto_publish_loop, daemon=True).start()
    url = f'http://localhost:{PORT}/'
    print(f'Playlist-Editor läuft: {url}  (Beenden mit Ctrl+C)')
    if '--no-browser' not in sys.argv:
        webbrowser.open(url)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
