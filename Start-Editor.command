#!/bin/bash
# Doppelklick: startet den Playlist-Editor (neu) und öffnet ihn im Browser.
# Ein bereits laufender Editor wird vorher beendet, damit immer der aktuelle Stand läuft.
# Das Terminal-Fenster muss offen bleiben, solange der Editor benutzt wird.
cd "$(dirname "$0")/editor" || exit 1
PORT=8790

OLD=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null)
if [ -n "$OLD" ]; then
  echo "Beende laufenden Editor …"
  kill $OLD 2>/dev/null
  for i in 1 2 3 4 5 6 7 8 9 10; do
    lsof -nP -iTCP:$PORT -sTCP:LISTEN -t >/dev/null 2>&1 || break
    sleep 0.3
  done
  lsof -nP -iTCP:$PORT -sTCP:LISTEN -t >/dev/null 2>&1 && kill -9 $OLD 2>/dev/null
fi

echo "Playlist-Editor startet: http://localhost:$PORT/"
echo "Dieses Fenster offen lassen. Beenden: Fenster schließen oder Ctrl+C."
echo
exec python3 server.py
