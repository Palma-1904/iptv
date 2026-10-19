"""Neuen Fire-TV-Stick einrichten – Assistent im Editor (stick.html), dieselben Schritte wie Stick-einrichten.command.

Alles läuft über adb aus werkzeuge/ (ADB-Schlüssel in editor/data/adb, gilt an jedem Mac). Jeder Schritt ist ein
eigener Aufruf POST /api/stick/<schritt> mit {ip, …}; die Seite führt der Reihe nach hindurch.
Ändert nichts am übrigen Editor: eigene Datei, eigene Adressen, nur Python-Standardbibliothek.
"""
import concurrent.futures
import json
import os
import re
import shlex
import socket
import safeproc  # Hilfsprogramme absturzsicher starten (macOS)
import subprocess
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ADB = os.path.join(ROOT, 'werkzeuge', 'android-sdk', 'platform-tools', 'adb')
ADB_HOME = os.path.join(HERE, 'data', 'adb')
PKG = 'de.iptv.firetv'
WEB = 'https://palma-1904.github.io/iptv/'
IP_RE = re.compile(r'^\d{1,3}(\.\d{1,3}){3}$')
TIME_RE = re.compile(r'^([01]\d|2[0-3]):[0-5]\d$')


class StickError(Exception):
    """Verständliche Fehlermeldung für die Seite."""


def _adb(*args, timeout=30):
    if not os.path.exists(ADB):
        raise StickError('adb fehlt (werkzeuge/android-sdk/platform-tools) – Claude fragen.')
    os.makedirs(ADB_HOME, exist_ok=True)
    env = dict(os.environ, HOME=ADB_HOME)
    try:
        r = safeproc.run([ADB, *args], capture_output=True, text=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired:
        raise StickError('Der Stick antwortet nicht (Zeitüberschreitung).')
    return (r.stdout + r.stderr).replace('\r', '').strip()


def _dev(ip):
    ip = (ip or '').strip()
    if not IP_RE.match(ip):
        raise StickError('Bitte eine gültige IP-Adresse eingeben (z. B. 192.168.6.47).')
    return ip + ':5555'


def _sh(ip, cmd, timeout=30):
    """Befehl auf dem Stick (cmd = Liste, wird für die Stick-Shell sicher gequotet)."""
    return _adb('-s', _dev(ip), 'shell', ' '.join(shlex.quote(str(c)) for c in cmd), timeout=timeout)


def _state(ip):
    out = _adb('-s', _dev(ip), 'get-state', timeout=10)
    if 'unauthorized' in out:
        return 'unauthorized'
    return 'device' if out.strip() == 'device' else 'offline'


def _last_ip_file():
    return os.path.join(ADB_HOME, 'letzte-ip')


# ---------- Schritte ----------

def scan(lan_ip):
    """Geräte im WLAN mit offenem ADB-Port 5555 suchen (Fire TV mit ADB-Debugging)."""
    last = ''
    try:
        last = open(_last_ip_file()).read().strip()
    except OSError:
        pass
    found = []
    if lan_ip and IP_RE.match(lan_ip):
        net = lan_ip.rsplit('.', 1)[0]

        def probe(i):
            ip = f'{net}.{i}'
            try:
                with socket.create_connection((ip, 5555), timeout=0.4):
                    return ip
            except OSError:
                return None
        with concurrent.futures.ThreadPoolExecutor(64) as ex:
            found = [ip for ip in ex.map(probe, range(1, 255)) if ip and ip != lan_ip]
    return {'found': found, 'last': last}


def connect(ip):
    dev = _dev(ip)
    _adb('start-server', timeout=15)
    st = _state(ip)
    if st == 'offline':
        _adb('connect', dev, timeout=15)
        time.sleep(1.5)
        st = _state(ip)
    out = {'state': st}
    if st == 'device':
        try:
            with open(_last_ip_file(), 'w') as f:
                f.write(ip.strip())
        except OSError:
            pass
        out.update(info(ip))
    return out


def _version_installed(ip):
    m = re.search(r'versionCode=(\d+)', _sh(ip, ['dumpsys', 'package', PKG]))
    return int(m.group(1)) if m else None


def _version_online():
    try:
        with urllib.request.urlopen(f'{WEB}app-version.txt?t={int(time.time())}', timeout=10) as r:
            return int(r.read().decode().strip())
    except Exception:
        return None


def info(ip):
    sdk = _sh(ip, ['getprop', 'ro.build.version.sdk'])
    acc = _sh(ip, ['settings', 'get', 'secure', 'enabled_accessibility_services'])
    ops_overlay = _sh(ip, ['appops', 'get', PKG, 'SYSTEM_ALERT_WINDOW'])
    ops_install = _sh(ip, ['appops', 'get', PKG, 'REQUEST_INSTALL_PACKAGES'])
    perm = _sh(ip, ['dumpsys', 'package', PKG])
    inst = _version_installed(ip)
    return {
        'model': _sh(ip, ['getprop', 'ro.product.model']),
        'fireos': _sh(ip, ['getprop', 'ro.build.version.name']),
        'sdk': int(sdk) if sdk.isdigit() else 0,
        'name': _sh(ip, ['settings', 'get', 'global', 'device_name']),
        'installed': inst, 'online': _version_online(),
        'overlay': inst is not None and 'allow' in ops_overlay,
        'install': inst is not None and ('allow' in ops_install or (sdk.isdigit() and int(sdk) < 26)),
        'secure': 'android.permission.WRITE_SECURE_SETTINGS: granted=true' in perm,
        'waechter': PKG in acc,
        'waechterPossible': sdk.isdigit() and int(sdk) >= 28,
    }


def rename(ip, name):
    name = re.sub(r'[^\w äöüÄÖÜß.\'-]', '', name or '').strip()[:40]
    if not name:
        raise StickError('Bitte einen Namen eingeben.')
    _sh(ip, ['settings', 'put', 'global', 'device_name', name])
    return {'name': _sh(ip, ['settings', 'get', 'global', 'device_name'])}


def install(ip):
    online = _version_online()
    inst = _version_installed(ip)
    if inst and online and inst >= online:
        return {'installed': inst, 'msg': f'App ist aktuell (Version {inst})'}
    apk = os.path.join(ADB_HOME, 'app.apk')
    try:
        urllib.request.urlretrieve(f'{WEB}app.apk?t={int(time.time())}', apk)
    except Exception as e:
        raise StickError(f'App konnte nicht geladen werden (Internet?): {e}')
    out = _adb('-s', _dev(ip), 'install', '-r', apk, timeout=300)
    if 'Success' not in out:
        if 'SIGNATURE' in out or 'UPDATE_INCOMPATIBLE' in out:
            raise StickError('Auf dem Stick ist eine fremde Fassung der App. Dort erst deinstallieren, dann noch einmal.')
        raise StickError('Installation fehlgeschlagen: ' + out[-300:])
    v = _version_installed(ip)
    return {'installed': v, 'msg': f'App installiert (Version {v})'}


def grant(ip):
    sdk = info(ip)['sdk']
    steps = [('Autostart (über anderen Apps einblenden)', ['appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow'])]
    if sdk >= 26:
        steps.append(('Updates installieren', ['appops', 'set', PKG, 'REQUEST_INSTALL_PACKAGES', 'allow']))
    else:
        steps.append(('Updates installieren', ['settings', 'put', 'secure', 'install_non_market_apps', '1']))
    steps.append(('Wächter selbst schalten', ['pm', 'grant', PKG, 'android.permission.WRITE_SECURE_SETTINGS']))
    res = []
    for text, cmd in steps:
        out = _sh(ip, cmd)
        res.append({'text': text, 'ok': not out, 'out': out[-200:]})
    return {'steps': res, **info(ip)}


def waechter(ip, on):
    svc = f'{PKG}/{PKG}.Waechter'
    cur = _sh(ip, ['settings', 'get', 'secure', 'enabled_accessibility_services'])
    items = [x for x in cur.split(':') if x and x != 'null' and PKG not in x]
    if on:
        items.append(svc)
    if items:
        _sh(ip, ['settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(items)])
    else:
        _sh(ip, ['settings', 'delete', 'secure', 'enabled_accessibility_services'])
    if on:
        _sh(ip, ['settings', 'put', 'secure', 'accessibility_enabled', '1'])
    return info(ip)


def configure(ip, b):
    """Liste, Wächter/Autostart und Einstellungen an die App übergeben (SetupReceiver), dann App starten."""
    kiosk = bool(b.get('kiosk'))
    ex = ['--es', 'ansicht', 'senioren',
          '--es', 'autostart', '1' if kiosk else '0', '--es', 'waechter', '1' if kiosk else '0']
    if not kiosk:
        ex += ['--es', 'home', '0']
    link = (b.get('link') or '').strip()
    if link:
        if not re.match(r'^[\w.-]+/[\w.-]+/[\w.-]+$', link):
            raise StickError('Ungültiger Einrichtungs-Link.')
        ex += ['--es', 'link', link]
    off, on = (b.get('schedOff') or '').strip(), (b.get('schedOn') or '').strip()
    ex += ['--es', 'schedOff', off if TIME_RE.match(off) else '', '--es', 'schedOn', on if TIME_RE.match(on) else '']
    ex += ['--es', 'sleepH', str(max(0, min(12, int(b.get('sleepH') or 0))))]
    ex += ['--es', 'ownlists', '1' if b.get('ownlists') else '0', '--es', 'normvol', '1' if b.get('normvol') else '0']
    out = _sh(ip, ['am', 'broadcast', '-f', '32', '-n', f'{PKG}/.SetupReceiver', *ex])
    if 'vorgemerkt' not in out:
        raise StickError('Einrichtung nicht übertragen – ist die App installiert? (' + out[-200:] + ')')
    if b.get('restart'):
        _sh(ip, ['am', 'force-stop', PKG])     # z. B. neuer Gerätename: gilt ab dem nächsten App-Start
    _sh(ip, ['am', 'start', '-n', f'{PKG}/.MainActivity'])
    return {'ok': True}


def report(ip):
    out = _sh(ip, ['am', 'broadcast', '-f', '32', '-n', f'{PKG}/.SetupReceiver', '--es', 'report', '1'])
    m = re.search(r'data="(.*)"\s*$', out, re.S)
    try:
        r = json.loads(m.group(1)) if m else {}
    except ValueError:
        r = {}
    r.update({k: v for k, v in info(ip).items() if k in ('waechter', 'overlay', 'install', 'name', 'installed')})
    return r


def disconnect(ip):
    _adb('disconnect', _dev(ip), timeout=10)   # Fire OS erlaubt nur EINE ADB-Verbindung gleichzeitig
    return {'ok': True}


def api(step, b, lan_ip, playlists):
    """Einstieg für server.py: POST /api/stick/<step>."""
    ip = b.get('ip')
    if step == 'scan':
        return scan(lan_ip)
    if step == 'lists':
        return {'lists': playlists}
    if step == 'connect':
        return connect(ip)
    if step != 'disconnect' and _state(ip) != 'device':
        raise StickError('Keine Verbindung zum Stick – bitte zurück zu „Verbinden“.')
    if step == 'info':
        return info(ip)
    if step == 'rename':
        return rename(ip, b.get('name'))
    if step == 'install':
        return install(ip)
    if step == 'grant':
        return grant(ip)
    if step == 'waechter':
        return waechter(ip, bool(b.get('on')))
    if step == 'configure':
        return configure(ip, b)
    if step == 'report':
        return report(ip)
    if step == 'disconnect':
        return disconnect(ip)
    raise StickError('Unbekannter Schritt')
