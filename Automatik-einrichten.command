#!/bin/bash
# Doppelklick (einmal je Mac): Hintergrunddienst „IPTV-Automatik“ ein- oder ausschalten.
# Solange der Mac an ist, schaut er stündlich nach: Ist die SSD dran und das Programm (EPG) älter als 6 Stunden,
# werden alle Listen neu veröffentlicht – auch ohne geöffneten Editor. Ohne SSD tut er nichts.
# Protokoll: ~/Library/Logs/iptv-automatik.log
cd "$(dirname "$0")" || exit 1
ROOT="$PWD"
LABEL=de.iptv.automatik
SUPPORT="$HOME/Library/Application Support/IPTV"
LAUNCHER="$SUPPORT/automatik.py"
# Das eigentliche Programm hinter python3 (macOS fragt Rechte für diese App ab, nicht für „python3“)
PYAPP="$(/usr/bin/python3 -c 'import sys; print(sys.base_prefix)')/Resources/Python.app"
PYBIN="$PYAPP/Contents/MacOS/Python"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/iptv-automatik.log"

echo
echo "IPTV-Automatik (Programm und Listen automatisch aktuell halten)"
echo "==============================================================="
# Alte Fassung (Start über bash, ohne SSD-Zugriff) oder Neu-Einrichten gewünscht: neu anlegen
OLDVER=""
grep -q "/bin/bash" "$PLIST" 2>/dev/null && OLDVER=1
if [ -z "$OLDVER" ] && launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1; then
  echo "Status: EINGESCHALTET auf diesem Mac."
  echo
  read -r -p "a = ausschalten, t = jetzt testen, Enter = so lassen: " A
  case "$A" in
    a|A)
      launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null
      rm -f "$PLIST"
      echo "✅ Ausgeschaltet."
      ;;
    t|T)
      echo "Teste über den Hintergrunddienst (veröffentlicht nur, wenn das Programm älter als 6 Stunden ist) …"
      launchctl kickstart -k "gui/$(id -u)/$LABEL"
      sleep 8
      tail -n 8 "$LOG"
      ;;
  esac
  echo
  read -r -p "Enter drücken zum Schließen …" _
  exit 0
fi

[ -n "$OLDVER" ] && echo "Status: ältere Fassung eingerichtet (ohne Zugriff auf die SSD) – wird jetzt erneuert." || echo "Status: aus."
echo
echo "Eingeschaltet schaut der Mac stündlich nach und veröffentlicht alle Listen neu, sobald das Programm"
echo "älter als 6 Stunden ist – nur wenn diese SSD angesteckt ist. Sie müssen dafür nichts öffnen."
echo
read -r -p "Einschalten? j = ja (Enter), n = nein: " J
case "$J" in n|N) echo "Nichts geändert."; read -r -p "Enter drücken zum Schließen …" _; exit 0 ;; esac

mkdir -p "$SUPPORT" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
# Startprogramm auf dem Mac selbst (nicht auf der SSD): prüft, ob die SSD da ist, dann auto_publish.py
[ -x "$PYBIN" ] || { echo "❌ Python nicht gefunden ($PYBIN) – zuerst Einrichten-neuer-Mac.command."; read -r -p "Enter …" _; exit 1; }
cat > "$LAUNCHER" <<EOF
# IPTV-Automatik – angelegt von Automatik-einrichten.command
import os, runpy, sys, time
P = """$ROOT/editor"""
LOG = """$LOG"""
out = open(LOG, 'a')
sys.stdout = sys.stderr = out
if not os.path.isdir(os.path.dirname(os.path.dirname(P))):
    sys.exit(0)                                   # SSD nicht angesteckt: nichts tun
try:
    os.listdir(P)
except PermissionError:
    print(time.strftime('%d.%m. %H:%M:%S'), 'Kein Zugriff auf die SSD – Systemeinstellungen → Datenschutz & Sicherheit → '
          'Festplattenvollzugriff → „Python“ einschalten (Automatik-einrichten.command zeigt, wie).', flush=True)
    sys.exit(0)
except OSError:
    sys.exit(0)
os.chdir(P)
sys.path.insert(0, P)
sys.argv = ['auto_publish.py']
runpy.run_path('auto_publish.py', run_name='__main__')
EOF
rm -f "$SUPPORT/automatik.sh"   # alte Fassung (über bash) – macOS ließ sie nicht auf die SSD

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>$PYBIN</string><string>$LAUNCHER</string></array>
  <key>StartInterval</key><integer>3600</integer>
  <key>RunAtLoad</key><true/>
  <key>LowPriorityIO</key><true/>
  <key>Nice</key><integer>10</integer>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null
if launchctl bootstrap "gui/$(id -u)" "$PLIST"; then
  echo
  echo "✅ Eingeschaltet."
  echo
  echo "Jetzt EINMALIG den Zugriff auf die SSD erlauben (macOS fragt Hintergrunddienste nicht selbst):"
  echo "  1. Es öffnen sich die Systemeinstellungen (Festplattenvollzugriff) und ein Finder-Fenster mit „Python“."
  echo "  2. „Python“ aus dem Finder-Fenster in die Liste ziehen (oder unten „+“ und Python wählen)."
  echo "  3. Den Schalter bei „Python“ einschalten (Passwort/Touch ID bestätigen)."
  echo "  Hinweis: Das gilt für das Python der Entwicklerwerkzeuge – damit darf die Automatik die SSD lesen."
  open "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_AllFiles" 2>/dev/null \
    || open "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles"
  open -R "$PYAPP"
  echo
  read -r -p "Wenn erledigt: Enter drücken – dann teste ich gleich …" _
  launchctl kickstart -k "gui/$(id -u)/$LABEL"
  sleep 10
  echo
  tail -n 6 "$LOG" 2>/dev/null
  if tail -n 3 "$LOG" 2>/dev/null | grep -qE "Kein Zugriff|not permitted"; then
    echo; echo "⚠️  Noch kein Zugriff – Schalter bei „Python“ prüfen, dann dieses Skript erneut starten und „t“ wählen."
  else
    echo; echo "✅ Läuft. Ab jetzt stündlich von selbst."
  fi
else
  echo "❌ Konnte den Dienst nicht starten."
fi
echo
read -r -p "Enter drücken zum Schließen …" _
