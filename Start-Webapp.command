#!/bin/bash
# Doppelklick: startet die Webapp zum Testen und öffnet sie im Browser.
# Auch iPad, iPhone und Fire TV im selben WLAN können sie dann aufrufen (Adresse siehe unten).
# Das Terminal-Fenster muss offen bleiben, solange getestet wird.
cd "$(dirname "$0")" || exit 1
PORT=8765
PAGE="${1:-index.html}"   # Start-Seniorenansicht übergibt senioren.html
IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)

if lsof -nP -iTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Die Webapp läuft bereits – Browser wird geöffnet."
  open "http://localhost:$PORT/$PAGE"
  exit 0
fi

echo "Webapp startet."
echo
echo "  Auf diesem Mac:          http://localhost:$PORT/$PAGE"
if [ -n "$IP" ]; then
  echo "  iPad / iPhone / Fire TV: http://$IP:$PORT/$PAGE   (im selben WLAN)"
fi
echo
echo "Dieses Fenster offen lassen. Beenden: Fenster schließen oder Ctrl+C."
echo
(sleep 1; open "http://localhost:$PORT/$PAGE") &
exec python3 -m http.server $PORT
