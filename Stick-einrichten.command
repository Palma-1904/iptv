#!/bin/bash
# Doppelklick: richtet einen Fire-TV-Stick für „Fernsehen“ ein – einmalig vor Ort, Mac im selben WLAN.
# Installiert bzw. aktualisiert die App, setzt die Rechte (Autostart, Updates, Wächter), schaltet den
# Wächter ein und überträgt auf Wunsch Einrichtungs-Link und Ansicht.
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
grant "Updates installieren"                     appops set $PKG REQUEST_INSTALL_PACKAGES allow
grant "Wächter selbst ein-/ausschalten"          pm grant $PKG android.permission.WRITE_SECURE_SETTINGS

# ---------- 4) Wächter ----------
echo
echo "4) Wächter"
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

# ---------- 5) Liste und Ansicht ----------
echo
echo "5) Liste und Ansicht"
echo "   Einrichtungs-Link aus dem Editor einfügen (Enter = Liste so lassen):"
read -r -p "   Link: " LINK
LINK=$(echo "$LINK" | tr -d " '\"")
read -r -p "   Ansicht – s = Senioren, k = Komplett (Enter = so lassen): " V
case "$V" in s|S) VIEW=senioren ;; k|K) VIEW=komplett ;; *) VIEW="" ;; esac
if [ -n "$LINK$VIEW" ]; then
  EXTRAS=""
  [ -n "$LINK" ] && EXTRAS="$EXTRAS --es link '$LINK'"
  [ -n "$VIEW" ] && EXTRAS="$EXTRAS --es ansicht $VIEW"
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
sh_ am start -n $PKG/.MainActivity >/dev/null
sleep 5
if sh_ dumpsys accessibility | grep -i "bound services" | grep -qE "$PKG|Fernsehen"; then
  ok "Wächter läuft"
else
  warn "Wächter läuft noch nicht – in einer Minute in der App prüfen (☰ 3 s → Weitere Einstellungen)."
fi
adb disconnect "$DEV" >/dev/null 2>&1

echo
echo "Fertig! Bitte kurz testen:"
echo " • Home-Taste drücken → „Fernsehen“ bleibt bzw. kommt sofort zurück"
echo "   (wenn „Home-Taste holt die App zurück“ an ist – in der Seniorenansicht automatisch)"
echo " • Stecker ziehen und wieder einstecken → „Fernsehen“ startet von selbst"
echo " • Editor → Fernwartung: Gerät erscheint mit „Wächter: AN“"
echo "ADB-Debugging kann am Stick an bleiben (nur bestätigte Computer kommen hinein)."
echo
read -r -p "Enter drücken zum Schließen …" _
