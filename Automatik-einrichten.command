#!/bin/bash
# Doppelklick (einmal je Mac): Hintergrunddienst „IPTV-Automatik“ ein- oder ausschalten.
# Solange der Mac an ist, schaut er stündlich nach: Ist die SSD dran und das Programm (EPG) älter als 6 Stunden,
# werden alle Listen neu veröffentlicht – auch ohne geöffneten Editor. Ohne SSD tut er nichts.
# Protokoll: ~/Library/Logs/iptv-automatik.log
cd "$(dirname "$0")" || exit 1
ROOT="$PWD"
LABEL=de.iptv.automatik
SUPPORT="$HOME/Library/Application Support/IPTV"
LAUNCHER="$SUPPORT/automatik.sh"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/iptv-automatik.log"

echo
echo "IPTV-Automatik (Programm und Listen automatisch aktuell halten)"
echo "==============================================================="
if launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1; then
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
      echo "Teste (veröffentlicht nur, wenn das Programm älter als 6 Stunden ist) …"
      /bin/bash "$LAUNCHER"
      tail -n 8 "$LOG"
      ;;
  esac
  echo
  read -r -p "Enter drücken zum Schließen …" _
  exit 0
fi

echo "Status: aus."
echo
echo "Eingeschaltet schaut der Mac stündlich nach und veröffentlicht alle Listen neu, sobald das Programm"
echo "älter als 6 Stunden ist – nur wenn diese SSD angesteckt ist. Sie müssen dafür nichts öffnen."
echo
read -r -p "Einschalten? j = ja (Enter), n = nein: " J
case "$J" in n|N) echo "Nichts geändert."; read -r -p "Enter drücken zum Schließen …" _; exit 0 ;; esac

mkdir -p "$SUPPORT" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
# Startskript auf dem Mac selbst (nicht auf der SSD): prüft, ob die SSD da ist
cat > "$LAUNCHER" <<EOF
#!/bin/bash
# IPTV-Automatik – angelegt von Automatik-einrichten.command
P="$ROOT"
[ -f "\$P/editor/auto_publish.py" ] || exit 0          # SSD nicht angesteckt: nichts tun
cd "\$P/editor" && exec /usr/bin/python3 auto_publish.py >> "$LOG" 2>&1
EOF
chmod +x "$LAUNCHER"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>/bin/bash</string><string>$LAUNCHER</string></array>
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
  echo "✅ Eingeschaltet. Erster Lauf jetzt – Protokoll:"
  sleep 4
  tail -n 6 "$LOG" 2>/dev/null
  echo
  echo "Hinweis: Fragt macOS, ob „python3“ auf einen Wechseldatenträger zugreifen darf → Erlauben."
else
  echo "❌ Konnte den Dienst nicht starten."
fi
echo
read -r -p "Enter drücken zum Schließen …" _
