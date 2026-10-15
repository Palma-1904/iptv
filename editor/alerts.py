"""Warnungen an die Admin-Fernbedienung (iPhone: Banner + rote Zahl per Web-Push, siehe webpush.py) – stündlich
von der IPTV-Automatik (auto_publish.py). Keine Fremd-App nötig.

Meldet nur Wichtiges und jedes nur einmal (Merker in data/alerts.json), mit „… ✓“, wenn es sich erledigt:
- Stick seit mehr als 24 Stunden ohne Meldung (Standby meldet nichts – darum nicht schon nach Stunden)
- Programm einer Liste älter als 24 Stunden (Veröffentlichen klappt nicht)
- Abo eines Zugangs läuft in ≤ 14 Tagen ab (einmal pro Woche erinnern)
- App eines Sticks seit über 2 Tagen auf alter Version
Offene Warnungen stehen in Firebase <kanal>/alerts (open, n = Zahl am Symbol, last = letzte Meldung).
"""
import json
import os
import time

import webpush

OFFLINE_H = 24
EPG_H = 24
ABO_DAYS = 14
UPDATE_H = 48


def night_updates(s, log):
    """Nachts (3–5 Uhr) Sticks mit alter App aktualisieren – nur wenn dort gerade niemand schaut und der Stick wach
    ist (meldet sich). Sticks im Standby holen das Update beim Einschalten selbst. Je Stick und Nacht einmal."""
    hour = time.localtime().tm_hour
    if not 3 <= hour < 5:
        return
    st = s.load_settings()
    base, kanal = s.fb_base(), st.get('remoteTopic')
    if not base or not kanal:
        return
    now = time.time()
    try:
        latest = int(s.fetch(f'https://palma-1904.github.io/iptv/app-version.txt?t={int(now)}', timeout=10).decode().strip())
    except Exception:
        return
    devices = json.loads(s.fetch(f'{base}{kanal}/status.json', timeout=20) or b'null') or {}
    memo_file = os.path.join(s.DATA, 'alerts.json')
    memo = s.read_json(memo_file, {})
    night = time.strftime('%Y-%m-%d')
    done = memo.setdefault('nightUpdate', {})
    for did, d in devices.items():
        if not isinstance(d, dict) or not d.get('cfg') or not d.get('ver') or d['ver'] >= latest:
            continue
        if done.get(did) == night or now - (d.get('t') or 0) > 1800 or d.get('screen') is False:
            continue
        if d.get('state') == 'playing':
            log(f'Nacht-Update: {d.get("name")} – es wird gerade geschaut, später')
            continue
        try:
            s.remote_cmd(did, 'update', {})
            done[did] = night
            log(f'Nacht-Update an {d.get("name")} geschickt (App {d.get("ver")} → {latest})')
        except Exception as ex:
            log(f'Nacht-Update an {d.get("name")} nicht gesendet ({ex})')
    s.write_json(memo_file, memo)


def check(s, log):
    st = s.load_settings()
    base, kanal = s.fb_base(), st.get('remoteTopic')
    if not base or not kanal:
        return
    memo_file = os.path.join(s.DATA, 'alerts.json')
    memo = s.read_json(memo_file, {})
    now = time.time()
    names = {k: (v or {}).get('label') for k, v in (st.get('deviceNotes') or {}).items()}
    devices = json.loads(s.fetch(f'{base}{kanal}/status.json', timeout=20) or b'null') or {}
    try:
        latest = int(s.fetch(f'https://palma-1904.github.io/iptv/app-version.txt?t={int(now)}', timeout=10).decode().strip())
    except Exception:
        latest = 0
    out = []   # (schlüssel, aktiv?, titel, text)

    for did, d in devices.items():
        if not isinstance(d, dict) or not d.get('name'):
            continue
        name = names.get(did) or d['name']
        age = now - (d.get('t') or 0)
        out.append((f'off:{did}', age > OFFLINE_H * 3600, f'📴 {name} meldet sich nicht',
                    f'{name} (Liste {str(d.get("list") or "?").upper()}) hat sich seit {int(age // 3600)} Stunden nicht gemeldet '
                    '– Strom, WLAN oder Fernseher prüfen lassen.', f'{name} ist wieder da ✓'))
        old = latest and d.get('ver') and d['ver'] < latest and d.get('cfg')
        key = f'upd:{did}'
        if old and key not in memo.get('since', {}):
            memo.setdefault('since', {})[key] = now
        if not old:
            memo.setdefault('since', {}).pop(key, None)
        since = memo.get('since', {}).get(key, now)
        out.append((key, bool(old) and now - since > UPDATE_H * 3600 and age < OFFLINE_H * 3600,
                    f'⬆️ {name} aktualisiert nicht',
                    f'{name} hat noch App {d.get("ver")}, neu ist {latest}. Admin-Fernbedienung → „Alle aktualisieren“ '
                    '(ohne Wächter: am Fernseher „Installieren“ drücken).', f'{name} ist jetzt aktuell ✓'))

    for p in s.load_state().get('playlists', []):
        slug = s.slugify(p.get('slug') or p.get('name') or p['id'])
        gen = s.read_json(os.path.join(s.OUT, slug + '.epg.json'), {}).get('generated') or 0
        out.append((f'epg:{slug}', gen and now - gen > EPG_H * 3600, f'🗓 Programm {p.get("name")} veraltet',
                    f'Das Programm der Liste {p.get("name")} ist von {time.strftime("%d.%m. %H:%M", time.localtime(gen))} '
                    '– Veröffentlichen klappt nicht (Token/Internet?). Im Editor „Veröffentlichen“ prüfen.',
                    f'Programm {p.get("name")} wieder aktuell ✓'))

    sources = {x['id']: x.get('name') or x['id'] for x in s.load_state().get('sources', [])}
    for sid, e in s.read_json(os.path.join(s.CACHE, 'expires.json'), {}).items():
        if sid not in sources or not e.get('expires'):
            continue
        days = (e['expires'] - now) / 86400
        week = time.strftime('%Y-%W')
        out.append((f'abo:{sid}:{week}', days <= ABO_DAYS, f'⏳ Abo {sources[sid]} läuft ab',
                    f'Der Zugang {sources[sid]} läuft am {time.strftime("%d.%m.%Y", time.localtime(e["expires"]))} ab '
                    f'(in {max(0, int(days))} Tagen) – rechtzeitig verlängern.', None))

    active = memo.setdefault('active', {})
    news, backs = [], []
    for key, on, title, text, back in out:
        if on and key not in active:
            active[key] = {'title': title, 'text': text, 't': int(now)}
            news.append((title, text))
        elif not on and key in active:
            del active[key]
            if back:
                backs.append(back)
    # Erledigtes ohne eigene Rückmeldung (z. B. Abo-Erinnerung der Vorwoche, entfernter Stick) still entfernen
    keys = {o[0] for o in out}
    gone = [k for k in active if k not in keys]
    for k in gone:
        del active[k]
    if gone and not (news or backs):   # nur die Liste/Zahl angleichen, ohne Banner
        try:
            open_ = {k.replace(':', '_').replace('/', '_'): v for k, v in active.items() if isinstance(v, dict)}
            with s.http(f'{base}{kanal}/alerts/open.json', data=json.dumps(open_).encode(), method='PUT', timeout=20):
                pass
            with s.http(f'{base}{kanal}/alerts/n.json', data=json.dumps(len(open_)).encode(), method='PUT', timeout=20):
                pass
        except Exception:
            pass
    if news or backs:
        open_ = {k.replace(':', '_').replace('/', '_'): v for k, v in active.items() if isinstance(v, dict)}
        title, text = news[-1] if news else (backs[-1], backs[-1])
        if len(news) + len(backs) > 1:
            text = ' · '.join([n[0] for n in news] + backs)
        try:
            ok, bad = webpush.post_alert(s, title, text, open_, urgent=bool(news))
            log(f'Meldung „{title}“ – an {ok} iPhone(s)' + (f', {bad} Fehler' if bad else ''))
        except Exception as ex:
            log(f'Meldung nicht gesendet ({type(ex).__name__}: {ex})')
    s.write_json(memo_file, memo)
