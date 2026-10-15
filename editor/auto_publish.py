"""Automatik (macOS-Hintergrunddienst, siehe Automatik-einrichten.command): stündlich aufgerufen, solange der Mac an ist.

Ist das veröffentlichte Programm (EPG) älter als MAX_AGE, werden alle Listen neu veröffentlicht – Senderlisten und
Programm frisch im Gist, die Geräte laden es im Hintergrund. Läuft der Editor gerade, bekommt er den Auftrag (damit
nie zwei gleichzeitig schreiben); sonst veröffentlicht dieses Skript selbst, ohne Fenster.
Aufruf:  python3 auto_publish.py [--force] [--dry-run]
"""
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
MAX_AGE = 6 * 3600          # Programm reicht 36 h voraus – alle 6 h auffrischen hält es immer ≥ 30 h aktuell
LOCK = os.path.join(HERE, 'data', 'auto_publish.lock')
EDITOR = 'http://127.0.0.1:8790'


def log(msg):
    print(time.strftime('%d.%m. %H:%M:%S'), msg, flush=True)


def oldest_epg(out_dir, slugs):
    """Ältester Veröffentlichungszeitpunkt (generated) der Programme aller Listen; 0 = fehlt."""
    ages = []
    for slug in slugs:
        try:
            with open(os.path.join(out_dir, slug + '.epg.json'), encoding='utf-8') as f:
                ages.append(json.load(f).get('generated') or 0)
        except (OSError, ValueError):
            ages.append(0)
    return min(ages) if ages else 0


def editor_running():
    try:
        with urllib.request.urlopen(EDITOR + '/api/job', timeout=3) as r:
            return json.loads(r.read() or b'{}')
    except Exception:
        return None


def main():
    force, dry = '--force' in sys.argv, '--dry-run' in sys.argv
    if not os.path.exists(os.path.join(HERE, 'data', 'state.json')):
        return log('Keine Daten (SSD nicht da?) – nichts zu tun')
    sys.path.insert(0, HERE)
    import server as s   # nur Standardbibliothek; startet beim Import nichts
    state = s.load_state()
    slugs = [s.slugify(p.get('slug') or p.get('name') or p['id']) for p in state.get('playlists', [])]
    oldest = oldest_epg(s.OUT, slugs)
    age = time.time() - oldest
    if not force and age < MAX_AGE:
        return log(f'Programm aktuell (ältestes vor {age / 3600:.1f} h) – nichts zu tun')
    if not s.load_settings().get('token'):
        return log('Kein GitHub-Token in den Editor-Einstellungen – kann nicht veröffentlichen')
    why = 'erzwungen' if force else ('fehlt' if not oldest else f'vor {age / 3600:.1f} h')
    if dry:
        return log(f'Würde jetzt veröffentlichen (Programm {why}) – Testlauf, nichts geändert')

    job = editor_running()
    if job is not None:
        if job.get('running'):
            return log(f'Editor ist beschäftigt ({job.get("label")}) – nächstes Mal')
        req = urllib.request.Request(EDITOR + '/api/publish', data=b'{"ids": []}', method='POST',
                                     headers={'Content-Type': 'application/json', 'Host': 'localhost:8790'})
        with urllib.request.urlopen(req, timeout=20):
            pass
        return log(f'Programm {why} – Auftrag an den laufenden Editor gegeben')

    # Editor läuft nicht: selbst veröffentlichen (Sperre gegen doppelte Läufe)
    try:
        fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        os.write(fd, str(os.getpid()).encode())
        os.close(fd)
    except FileExistsError:
        if time.time() - os.path.getmtime(LOCK) < 3600:
            return log('Läuft schon – nächstes Mal')
        os.remove(LOCK)
        return log('Alte Sperre entfernt – nächstes Mal')
    try:
        log(f'Programm {why} – veröffentliche alle Listen …')
        res = s.publish([])
        for r in res['results']:
            log(f'  {r["name"]}: {r["entries"]} Einträge, Programm für {r["epgChannels"]} Sender'
                + (f' – {len(r["warnings"])} Hinweise' if r['warnings'] else ''))
        log('hochgeladen ✓' + (f', {res["devicesNotified"]} Listen an die Geräte gemeldet' if res.get('devicesNotified') else '')
            if res['uploaded'] else 'NICHT hochgeladen (Token/Internet?)')
        st = s.load_settings()
        st['autoResult'] = time.strftime('%d.%m. %H:%M') + ': automatisch veröffentlicht (Mac-Hintergrunddienst)' \
            + ('' if res['uploaded'] else ' – Hochladen fehlgeschlagen')
        s.write_json(s.SETTINGS_FILE, st)
    finally:
        try:
            os.remove(LOCK)
        except OSError:
            pass


if __name__ == '__main__':
    try:
        main()
    except Exception as e:   # nie hängen bleiben, nur protokollieren
        log(f'Fehler: {type(e).__name__}: {e}')
