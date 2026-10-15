"""Web-Push an die Admin-Fernbedienung auf dem iPhone (Home-Bildschirm-App, iOS 16.4+): Banner und rote Zahl.

Ohne Zusatzpakete: Schlüssel und ES256-Signatur (VAPID) über das eingebaute /usr/bin/openssl des Macs.
Gesendet wird ein Push OHNE Inhalt (dann ist keine Verschlüsselung nötig); der Service Worker der Admin-Seite
(sw-admin.js) holt Titel/Text selbst aus Firebase (<kanal>/alerts) und zeigt Banner + Zahl am Symbol.
Firebase: <kanal>/push/vapid = öffentlicher Schlüssel, <kanal>/push/subs/<id> = Anmeldungen der iPhones.
"""
import base64
import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

OPENSSL = '/usr/bin/openssl'
SUBJECT = 'https://palma-1904.github.io/iptv/'   # Kontakt für den Push-Dienst (keine E-Mail-Adresse nötig)


def b64u(b):
    return base64.urlsafe_b64encode(b).rstrip(b'=').decode()


def _key_file(s):
    return os.path.join(s.DATA, 'vapid.pem')   # editor/data – nicht im Repo


def public_key(s):
    """Öffentlicher Schlüssel (65 Byte, base64url); legt das Schlüsselpaar beim ersten Mal an."""
    kf = _key_file(s)
    if not os.path.exists(kf):
        subprocess.run([OPENSSL, 'ecparam', '-name', 'prime256v1', '-genkey', '-noout', '-out', kf],
                       check=True, capture_output=True, timeout=20)
        os.chmod(kf, 0o600)
    der = subprocess.run([OPENSSL, 'ec', '-in', kf, '-pubout', '-outform', 'DER'],
                         check=True, capture_output=True, timeout=20).stdout
    return b64u(der[-65:])


def _sign(s, data):
    """ES256: openssl liefert DER (SEQUENCE r, s) → 64 Byte r‖s."""
    der = subprocess.run([OPENSSL, 'dgst', '-sha256', '-sign', _key_file(s)], input=data,
                         check=True, capture_output=True, timeout=20).stdout
    i = 2 if der[1] < 0x80 else 3
    out = b''
    for _ in range(2):
        assert der[i] == 0x02
        n = der[i + 1]
        v = der[i + 2:i + 2 + n].lstrip(b'\x00')
        out += v.rjust(32, b'\x00')
        i += 2 + n
    return out


def _jwt(s, endpoint):
    u = urllib.parse.urlsplit(endpoint)
    head = b64u(json.dumps({'typ': 'JWT', 'alg': 'ES256'}, separators=(',', ':')).encode())
    body = b64u(json.dumps({'aud': f'{u.scheme}://{u.netloc}', 'exp': int(time.time()) + 12 * 3600, 'sub': SUBJECT},
                           separators=(',', ':')).encode())
    msg = f'{head}.{body}'.encode()
    return msg.decode() + '.' + b64u(_sign(s, msg))


def publish_key(s):
    """Öffentlichen Schlüssel für die Admin-Seite nach Firebase (zum Anmelden der iPhones)."""
    st, base = s.load_settings(), s.fb_base()
    kanal = st.get('remoteTopic')
    if not base or not kanal:
        return None
    k = public_key(s)
    with s.http(f'{base}{kanal}/push/vapid.json', data=json.dumps(k).encode(), method='PUT', timeout=20):
        pass
    return k


def send_all(s, urgent=True):
    """Leeren Push an alle angemeldeten iPhones; abgelaufene Anmeldungen (404/410) werden gelöscht.
    Liefert (gesendet, Fehler)."""
    st, base = s.load_settings(), s.fb_base()
    kanal = st.get('remoteTopic')
    if not base or not kanal:
        return 0, 0
    pub = publish_key(s)
    subs = json.loads(s.fetch(f'{base}{kanal}/push/subs.json', timeout=20) or b'null') or {}
    ok = bad = 0
    for sid, sub in subs.items():
        ep = (sub or {}).get('endpoint') or ''
        if not ep.startswith('https://'):
            continue
        req = urllib.request.Request(ep, data=b'', method='POST', headers={
            'Authorization': f'vapid t={_jwt(s, ep)}, k={pub}', 'TTL': '86400',
            'Urgency': 'high' if urgent else 'normal', 'Content-Length': '0'})
        try:
            with urllib.request.urlopen(req, timeout=20, context=s.SSL_CTX):
                ok += 1
        except urllib.error.HTTPError as e:
            if e.code in (404, 410):   # iPhone hat sich abgemeldet / App gelöscht
                try:
                    with s.http(f'{base}{kanal}/push/subs/{sid}.json', method='DELETE', timeout=20):
                        pass
                except Exception:
                    pass
            bad += 1
        except Exception:
            bad += 1
    return ok, bad


def test(s):
    """Test-Nachricht (Admin-Seite → Editor „pushtest“): offene Warnungen bleiben stehen."""
    st, base = s.load_settings(), s.fb_base()
    kanal = st.get('remoteTopic')
    last = {'title': '🔔 Test', 'text': 'Benachrichtigungen funktionieren ✓', 't': int(time.time()), 'id': f't{int(time.time())}'}
    with s.http(f'{base}{kanal}/alerts/last.json', data=json.dumps(last).encode(), method='PUT', timeout=20):
        pass
    return send_all(s, urgent=False)


def post_alert(s, title, text, active, urgent=True):
    """Meldung nach Firebase (<kanal>/alerts: last + offene Warnungen) und Push an alle iPhones."""
    st, base = s.load_settings(), s.fb_base()
    kanal = st.get('remoteTopic')
    if not base or not kanal:
        return 0, 0
    body = {'last': {'title': title, 'text': text, 't': int(time.time()), 'id': f'm{int(time.time() * 1000)}'},
            'open': active, 'n': len(active)}
    with s.http(f'{base}{kanal}/alerts.json', data=json.dumps(body).encode(), method='PUT', timeout=20):
        pass
    return send_all(s, urgent)
