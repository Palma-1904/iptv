#!/bin/bash
# Doppelklick: richtet einen Fire-TV-Stick für „Fernsehen“ ein – einmalig vor Ort, Mac im selben WLAN.
# Installiert bzw. aktualisiert die App (kein Downloader nötig), setzt die Rechte (Autostart, Updates, Wächter),
# Gerätename, Liste, Wächter, Zeitplan/Schlaf-Timer/eigene Listen/Lautstärke und zeigt am Ende einen Bericht
# mit QR-Code der Handy-Fernbedienung. Die App kennt nur noch die Seniorenansicht.
# Am Stick vorher: Einstellungen → Mein Fire TV → Entwickleroptionen → ADB-Debugging AN.
# Für Tests: ./Stick-einrichten.command pfad/zur/app.apk  (diese Datei statt der veröffentlichten App)
cd "$(dirname "$0")" || exit 1
ROOT="$PWD"
ADB_BIN="$ROOT/werkzeuge/android-sdk/platform-tools/adb"
PKG=de.iptv.firetv
WEB=https://palma-1904.github.io/iptv/
# ADB-Schlüssel liegt auf der SSD (editor/data ist nicht im Repo): „Immer zulassen“ gilt so an jedem Mac
DATA="$ROOT/editor/data/adb"
mkdir -p "$DATA"

adb() { HOME="$DATA" "$ADB_BIN" "$@"; }
sh_() { adb -s "$DEV" shell "$@" | tr -d '\r'; }
ok() { echo "   ✅ $*"; }
warn() { echo "   ⚠️  $*"; }
fail() { echo; echo "   ❌ $*"; echo; read -r -p "Enter drücken zum Schließen …" _; exit 1; }

[ -x "$ADB_BIN" ] || fail "adb fehlt (werkzeuge/android-sdk/platform-tools) – Claude fragen."

echo
echo "Fire-TV-Stick einrichten"
echo "========================"
echo "Am Stick vorher:  Einstellungen → Mein Fire TV → Entwickleroptionen → ADB-Debugging AN"
echo "IP-Adresse:       Einstellungen → Mein Fire TV → Info → Netzwerk"
echo "(Entwickleroptionen fehlen? Unter „Info“ 7× OK auf den Gerätenamen drücken.)"
echo
LAST=$(cat "$DATA/letzte-ip" 2>/dev/null)
read -r -p "IP-Adresse des Sticks${LAST:+ [Enter = $LAST]}: " IP
IP=$(echo "${IP:-$LAST}" | tr -d ' ')
[ -n "$IP" ] || fail "Keine IP-Adresse eingegeben."
case "$IP" in *:*) DEV="$IP" ;; *) DEV="$IP:5555" ;; esac

# ---------- 1) Verbinden ----------
echo
echo "1) Verbinden mit $DEV …"
adb kill-server >/dev/null 2>&1
adb start-server >/dev/null 2>&1
adb connect "$DEV" >/dev/null 2>&1
STATE=""
HINT=""
for _ in $(seq 1 60); do
  STATE=$(adb -s "$DEV" get-state 2>&1)
  case "$STATE" in
    device) break ;;
    *unauthorized*)
      [ -n "$HINT" ] || echo "   👉 Am Fernseher: Haken bei „Von diesem Computer immer zulassen“, dann OK drücken."
      HINT=1 ;;
    *) adb connect "$DEV" >/dev/null 2>&1 ;;
  esac
  sleep 2
done
[ "$STATE" = device ] || fail "Keine Verbindung. ADB-Debugging am Stick an? Mac im selben WLAN? IP richtig?"
echo "$IP" > "$DATA/letzte-ip"
ok "Verbunden: $(sh_ getprop ro.product.model) · $(sh_ getprop ro.build.version.name)"
SDK=$(sh_ getprop ro.build.version.sdk)
# Fire OS 6 (Android 7) und älter: Amazon lässt dort keinen Wächter zu
OLD_FIREOS=""
[ "${SDK:-0}" -lt 28 ] && OLD_FIREOS=1

# ---------- Gerätename (erscheint so in der Admin-Fernbedienung) ----------
echo
CURNAME=$(sh_ settings get global device_name)
read -r -p "   Gerätename [Enter = „$CURNAME“ lassen, z. B. Schlafzimmer]: " NAME
NAME=$(echo "$NAME" | tr -cd "[:alnum:] äöüÄÖÜß.-")
RENAMED=""
if [ -n "$NAME" ] && [ "$NAME" != "$CURNAME" ]; then
  sh_ settings put global device_name "\"$NAME\"" >/dev/null
  RENAMED=1
  ok "Gerätename: $NAME"
fi

# ---------- 2) App ----------
installed() { sh_ dumpsys package $PKG | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
echo
echo "2) App"
INST=$(installed)
APK=""
if [ -n "$1" ]; then
  APK="$1"
  [ -f "$APK" ] || fail "Datei nicht gefunden: $APK"
  echo "   Installiere Datei $(basename "$APK") …"
else
  ONLINE=$(curl -fsS "${WEB}app-version.txt?t=$(date +%s)" 2>/dev/null | tr -dc 0-9)
  if [ -n "$INST" ] && [ -n "$ONLINE" ] && [ "$INST" -ge "$ONLINE" ]; then
    ok "App ist aktuell (Version $INST)"
  else
    echo "   Lade die neueste App (Version ${ONLINE:-?}) …"
    APK="$DATA/app.apk"
    curl -fL# -o "$APK" "${WEB}app.apk?t=$(date +%s)" || fail "Download fehlgeschlagen (Internet?)."
  fi
fi
if [ -n "$APK" ]; then
  OUT=$(adb -s "$DEV" install -r "$APK" 2>&1)
  case "$OUT" in
    *Success*) ok "App installiert (Version $(installed))" ;;
    *SIGNATURE*|*UPDATE_INCOMPATIBLE*) fail "Auf dem Stick ist eine fremde Fassung der App. Dort erst deinstallieren, dann erneut starten." ;;
    *) fail "Installation fehlgeschlagen: $OUT" ;;
  esac
fi

# ---------- 3) Rechte ----------
echo
echo "3) Rechte"
grant() {   # $1 = Text, Rest = Befehl
  local text="$1"; shift
  local out; out=$(sh_ "$@" 2>&1)
  if [ -z "$out" ]; then ok "$text"; else warn "$text: $out"; fi
}
grant "Autostart (über anderen Apps einblenden)" appops set $PKG SYSTEM_ALERT_WINDOW allow
if [ "${SDK:-0}" -ge 26 ]; then
  grant "Updates installieren"                   appops set $PKG REQUEST_INSTALL_PACKAGES allow
else
  grant "Updates installieren (Apps unbekannter Herkunft)" settings put secure install_non_market_apps 1
fi
grant "Wächter selbst ein-/ausschalten"          pm grant $PKG android.permission.WRITE_SECURE_SETTINGS

# ---------- 4) Wächter ----------
echo
echo "4) Wächter und Autostart"
echo "   Für Senioren: Fernsehen startet von selbst, Home-Taste führt zurück, Updates ohne Klick."
read -r -p "   Einschalten? j = ja (Enter), n = nein (selbst bedienen): " WA
case "$WA" in n|N) KIOSK=0 ;; *) KIOSK=1 ;; esac
if [ "$KIOSK" = 0 ]; then
  CUR=$(sh_ settings get secure enabled_accessibility_services)
  NEW=$(echo "$CUR" | tr ':' '\n' | grep -v "$PKG" | grep -v '^null$' | paste -sd: -)
  if [ -n "$NEW" ]; then sh_ settings put secure enabled_accessibility_services "$NEW"; else sh_ settings delete secure enabled_accessibility_services >/dev/null; fi
  ok "Wächter und Autostart bleiben aus"
else
SVC="$PKG/$PKG.Waechter"
CUR=$(sh_ settings get secure enabled_accessibility_services)
case "$CUR" in
  *"$SVC"*|*"$PKG/.Waechter"*) NEW="" ;;
  ""|null) NEW="$SVC" ;;
  *) NEW="$CUR:$SVC" ;;
esac
[ -n "$NEW" ] && sh_ settings put secure enabled_accessibility_services "$NEW"
sh_ settings put secure accessibility_enabled 1
ok "Wächter eingeschaltet"
fi

# ---------- 5) Liste und Einstellungen ----------
echo
echo "5) Liste"
# Listen aus dem Editor: Nummer wählen (Link BENUTZER/GIST/name wird daraus gebaut)
LISTS=$(python3 -I - "$ROOT/editor" <<'PY' 2>/dev/null
import json, os, re, sys
d = sys.argv[1]
st = json.load(open(os.path.join(d, 'data', 'state.json')))
se = json.load(open(os.path.join(d, 'data', 'settings.json')))
def slug(s):   # wie slugify() im Editor
    s = (s or '').lower().translate(str.maketrans({'ä': 'ae', 'ö': 'oe', 'ü': 'ue', 'ß': 'ss'}))
    return re.sub(r'[^a-z0-9]+', '-', s).strip('-') or 'liste'
for p in st.get('playlists', []):
    if se.get('login') and se.get('gistId'):
        print(f"{p.get('name')}|{se['login']}/{se['gistId']}/{slug(p.get('slug') or p.get('name') or p['id'])}")
PY
)
LINK=""
if [ -n "$LISTS" ]; then
  i=0
  while IFS='|' read -r n l; do i=$((i+1)); echo "   $i = $n"; done <<< "$LISTS"
  read -r -p "   Liste wählen (Nummer, l = Link einfügen, Enter = so lassen): " NR
  if [ "$NR" = l ] || [ "$NR" = L ]; then
    read -r -p "   Link: " LINK
  elif [ -n "$NR" ]; then
    LINK=$(sed -n "${NR}p" <<< "$LISTS" | cut -d'|' -f2)
    [ -n "$LINK" ] || warn "Keine Liste $NR – Liste bleibt"
  fi
else
  echo "   Einrichtungs-Link aus dem Editor einfügen (Enter = Liste so lassen):"
  read -r -p "   Link: " LINK
fi
LINK=$(echo "$LINK" | tr -d " '\"")

echo
echo "   Einstellungen (Enter = Vorschlag in [ ]; später jederzeit in der Admin-Fernbedienung ⚙️ änderbar)"
read -r -p "   Zeitplan: täglich AUS um (HH:MM) [kein]: " SOFF
read -r -p "   Zeitplan: täglich AN um (HH:MM) [kein]: " SON
read -r -p "   Schlaf-Timer: Stunden ohne Taste bis Stopp (0 = aus) [3]: " SLP
read -r -p "   Eigene Listen am Stick bearbeiten (OK lang)? j/n [n]: " OWN
read -r -p "   Gleichmäßige Lautstärke? j/n [j]: " NV
valid_time() { [[ "$1" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]]; }
valid_time "$SOFF" || { [ -n "$SOFF" ] && warn "Zeit „$SOFF“ ungültig – kein Aus"; SOFF=""; }
valid_time "$SON" || { [ -n "$SON" ] && warn "Zeit „$SON“ ungültig – kein An"; SON=""; }
[[ "$SLP" =~ ^[0-9]{1,2}$ ]] || SLP=3

EXTRAS=""
if [ "$KIOSK" = 0 ]; then EXTRAS=" --es autostart 0 --es home 0 --es waechter 0"; else EXTRAS=" --es autostart 1 --es waechter 1"; fi
EXTRAS="$EXTRAS --es ansicht senioren --es schedOff '$SOFF' --es schedOn '$SON' --es sleepH $SLP"
case "$OWN" in j|J) EXTRAS="$EXTRAS --es ownlists 1" ;; *) EXTRAS="$EXTRAS --es ownlists 0" ;; esac
case "$NV" in n|N) EXTRAS="$EXTRAS --es normvol 0" ;; *) EXTRAS="$EXTRAS --es normvol 1" ;; esac
if true; then
  [ -n "$LINK" ] && EXTRAS="$EXTRAS --es link '$LINK'"
  if sh_ "am broadcast -f 32 -n $PKG/.SetupReceiver$EXTRAS" | grep -q vorgemerkt; then
    ok "Einrichtung übertragen"
  else
    warn "Einrichtung nicht übertragen – dann am Fernseher ☰ 3 Sekunden halten."
  fi
else
  ok "bleibt wie es ist"
fi

# ---------- 6) Start und Kontrolle ----------
echo
echo "6) App starten"
[ -n "$RENAMED" ] && sh_ am force-stop $PKG >/dev/null   # neuer Gerätename gilt ab dem nächsten App-Start
sh_ am start -n $PKG/.MainActivity >/dev/null
sleep 5
# Android 9+: „services:{Service[label=Fernsehen …“, älter: „Bound services:{…}“
if [ "$KIOSK" = 0 ]; then
  ok "App gestartet (Wächter und Autostart aus)"
elif sh_ dumpsys accessibility | grep -iE "services:\{.*(label=Fernsehen|$PKG)" >/dev/null; then
  ok "Wächter läuft"
elif [ -n "$OLD_FIREOS" ]; then
  warn "Älterer Stick (Fire OS 6): Amazon lässt hier keinen Wächter zu. Die App läuft trotzdem;"
  warn "Home-Rückkehr wie bisher nach 1,5 s, Updates brauchen hier einen Klick vor Ort."
else
  warn "Wächter läuft noch nicht – in einer Minute in der App prüfen (☰ 3 s → Weitere Einstellungen)."
fi
# ---------- 7) Bericht ----------
echo
echo "7) Bericht"
REP=""
for _ in $(seq 1 10); do   # Handy-Link braucht die Fernwartung (Liste geladen) – bis ~30 s warten
  REP=$(sh_ "am broadcast -f 32 -n $PKG/.SetupReceiver --es report 1" | sed -n 's/.*data="\(.*\)"$/\1/p')
  case "$REP" in *'"phone":"https'*) break ;; esac
  sleep 3
done
if [ -z "$REP" ] || [ "$REP" = "{}" ]; then
  warn "Kein Bericht (ältere App ohne Bericht?) – Stand in der Admin-Fernbedienung ansehen."
else
  python3 -I - "$REP" "$DATA" "${NAME:-$CURNAME}" <<'PY'
import json, sys, os, html
r, data, name = json.loads(sys.argv[1]), sys.argv[2], sys.argv[3]
def yn(b): return '✅' if b else '❌'
s = r.get('sched') or {}
print(f"   App-Version {r.get('ver')}")
print(f"   {yn(r.get('overlay'))} Autostart-Erlaubnis   {yn(r.get('install'))} Updates erlaubt   {yn(r.get('waechter'))} Wächter läuft")
print(f"   Autostart {'an' if r.get('autostart') else 'aus'} · Home-Taste {'an' if r.get('home') else 'aus'} · "
      f"Lautstärke {'gleichmäßig' if r.get('normvol') else 'normal'} · eigene Listen {'erlaubt' if r.get('ownlists') else 'aus'}")
print(f"   Zeitplan: aus {s.get('off') or '–'}, an {s.get('on') or '–'} · Schlaf-Timer {s.get('sleepH', 3) or 'aus'} h")
link = r.get('phone') or ''
if not link:
    print('   📱 Handy-Link noch nicht bereit (Fernwartung lädt) – später in der Admin-Fernbedienung ⚙️')
    sys.exit()
# QR-Code als kleine Seite auf der SSD (editor/data ist nicht im Repo) und im Browser öffnen
page = os.path.join(data, 'handy-qr.html')
open(page, 'w').write(f"""<!doctype html><meta charset="utf-8"><title>Handy-Fernbedienung {html.escape(name)}</title>
<body style="font:18px -apple-system,sans-serif;text-align:center;padding:30px">
<h2>📱 Handy-Fernbedienung „{html.escape(name)}“</h2><div id="q"></div>
<p>Mit der Handy-Kamera scannen, Link öffnen, „Zum Home-Bildschirm“.</p>
<p style="font-size:13px;word-break:break-all;color:#666">{html.escape(link)}</p>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcode-generator/1.4.4/qrcode.min.js"></script>
<script>var q=qrcode(0,'M');q.addData({json.dumps(link)});q.make();document.getElementById('q').innerHTML=q.createSvgTag({{cellSize:7,margin:2}});</script>""")
print('   📱 QR-Code der Handy-Fernbedienung öffnet sich im Browser')
os.system(f'open "{page}"')
PY
fi
adb disconnect "$DEV" >/dev/null 2>&1

echo
echo "Fertig! Bitte kurz testen:"
echo " • Home-Taste drücken → „Fernsehen“ bleibt bzw. kommt sofort zurück (wenn „Home-Taste holt die App zurück“ an ist)"
echo " • Stecker ziehen und wieder einstecken → „Fernsehen“ startet von selbst"
echo " • Admin-Fernbedienung: Gerät erscheint in der Auswahlliste (⚙️: Wächter, Zeitplan …)"
echo "ADB-Debugging kann am Stick an bleiben (nur bestätigte Computer kommen hinein)."
echo
read -r -p "Enter drücken zum Schließen …" _ || true
