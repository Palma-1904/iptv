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
import time
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
VERSION = 7   # bei Änderungen an Server UND Oberfläche erhöhen (editor.js: SERVER_VERSION)
STATIC = {'/': 'index.html', '/index.html': 'index.html', '/editor.js': 'editor.js', '/editor.css': 'editor.css',
          '/watch.html': 'watch.html'}

UA = 'Mozilla/5.0 (Macintosh) IPTV-Playlist-Editor'
EPG_TTL = 6 * 3600          # EPG-Download höchstens alle 6 Stunden
SERIES_TTL = 24 * 3600      # Episodenlisten einen Tag zwischenspeichern
EPG_PAST = 1 * 3600         # EPG-Fenster: 1 Stunde zurück ...
EPG_FUTURE = 36 * 3600      # ... bis 36 Stunden voraus

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


def load_catalog(source_id):
    return read_json(os.path.join(CACHE, f'catalog_{source_id}.json'), None)


# ---------- Veröffentlichen ----------

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

    lines = ['#EXTM3U']
    epg_ids = {}  # source_id -> set(tvgId)
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
                work = attr(item['key'])
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
                            t = f'{title} S{e["season"]:02d}E{e["episode"]:02d}'
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
                    t = f'{name} S{e["season"]:02d}E{e["episode"]:02d}'
                    if e['title'] and e['title'] not in t:
                        t += f' – {e["title"]}'
                    lines.append(f'#EXTINF:-1 tvg-type="series" tvg-logo="{attr(ch["logo"])}" '
                                 f'group-title="{attr(name)}",{t}')
                    lines.append(e['url'])
                    count += 1
                continue
            parts = ['#EXTINF:-1']
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
    return '\n'.join(lines) + '\n', epg_ids, catalogs, count


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


def gist_upload(files, settings):
    token = settings.get('token')
    if not token:
        return None
    headers = {'Authorization': f'Bearer {token}', 'Accept': 'application/vnd.github+json',
               'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'}
    body = {'files': {name: {'content': content} for name, content in files.items()}}
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
        m3u, epg_ids, catalogs, count = build_playlist(pl, state, warnings)
        epg = {}
        for sid, wanted in epg_ids.items():
            epg.update(epg_for_source(sid, catalogs.get(sid, {}).get('epg'), wanted, warnings))
        # Eigene Reihenfolge für den Reiter „Programm“ (sonst wie in der Playlist)
        order = [i for i in pl.get('epgOrder') or [] if i in epg]
        epg_json = json.dumps({'v': 1, 'generated': int(time.time()), 'channels': epg, 'order': order},
                              ensure_ascii=False, separators=(',', ':'))
        for folder in (OUT, LOCAL_OUT):
            with open(os.path.join(folder, slug + '.m3u'), 'w', encoding='utf-8') as f:
                f.write(m3u)
            with open(os.path.join(folder, slug + '.epg.json'), 'w', encoding='utf-8') as f:
                f.write(epg_json)
        files[slug + '.m3u'] = m3u
        files[slug + '.epg.json'] = epg_json
        results.append({'id': pl['id'], 'name': pl.get('name'), 'slug': slug, 'entries': count,
                        'epgChannels': len(epg), 'sizeKb': round(len(m3u.encode()) / 1024),
                        'warnings': warnings})
    uploaded = None
    if files:
        try:
            uploaded = gist_upload(files, settings)
        except UserError as e:
            for r in results:
                r['warnings'].append(f'Hochladen fehlgeschlagen: {e}')
    for r in results:
        r.update(device_links(r['slug'], uploaded or {}))
    return {'results': results, 'uploaded': bool(uploaded)}


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
                    'pagesUrl': s.get('pagesUrl', '')}})
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
                return self.send_json(publish(self.body().get('ids') or []))
            self.send_error(404)
        except UserError as e:
            self.send_json({'error': str(e)}, 400)
        except Exception as e:  # unerwartet: trotzdem verständlich melden
            self.send_json({'error': f'Interner Fehler: {type(e).__name__}: {e}'}, 500)


def main():
    srv = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
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
