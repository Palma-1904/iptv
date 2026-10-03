#!/bin/bash
# Doppelklick auf einem neuen Mac (z. B. MacBook Neo, Büro-Mac): prüft und richtet alles ein,
# was Editor, Webapp-Test und GitHub Desktop brauchen. Kann gefahrlos mehrfach ausgeführt werden.
cd "$(dirname "$0")" || exit 1
PROJ="$(pwd)"
OK="  ✓"; TODO="  →"
echo "Einrichtung für dieses Mac: $(scutil --get ComputerName 2>/dev/null)"
echo "Projektordner: $PROJ"
echo

# 1. Apple-Entwicklerwerkzeuge (bringen Python 3 und Git mit)
if xcode-select -p >/dev/null 2>&1 && python3 --version >/dev/null 2>&1 && git --version >/dev/null 2>&1; then
  echo "$OK Python $(python3 --version 2>&1 | cut -d' ' -f2) und Git sind vorhanden"
else
  echo "$TODO Python/Git fehlen – macOS öffnet gleich ein Fenster „Befehlszeilenentwickler-Tools“."
  echo "     Dort auf „Installieren“ klicken. Danach diese Datei NOCHMAL doppelklicken."
  xcode-select --install 2>/dev/null
  echo; read -n 1 -s -r -p "Taste drücken zum Schließen …"; exit 0
fi

# 2. Startdateien startbar machen (Download-Sperre entfernen, Ausführrecht setzen)
xattr -dr com.apple.quarantine "$PROJ"/*.command 2>/dev/null
chmod +x "$PROJ"/*.command
echo "$OK Startdateien sind startbar"

# 3. Git: Projekt auf dieser SSD als vertrauenswürdig markieren (SSD hat keine festen Besitzer)
git config --global --get-all safe.directory 2>/dev/null | grep -qxF "$PROJ" || git config --global --add safe.directory "$PROJ"
echo "$OK Git kennt den Projektordner ($(git -C "$PROJ" config user.name) · Branch $(git -C "$PROJ" branch --show-current))"

# 4. GitHub Desktop
if [ -d "/Applications/GitHub Desktop.app" ]; then
  echo "$OK GitHub Desktop ist installiert – Projekt wird dort eingetragen"
  "/Applications/GitHub Desktop.app/Contents/Resources/app/static/github.sh" "$PROJ" >/dev/null 2>&1 &
  echo "     Falls gefragt: „Add Repository“ klicken. Anmelden mit Konto Palma-1904."
else
  echo "$TODO GitHub Desktop fehlt – Download-Seite wird geöffnet."
  echo "     Installieren, mit Palma-1904 anmelden, dann diese Datei NOCHMAL doppelklicken."
  open "https://desktop.github.com/download/"
fi

# 5. VLC (optional, für „VLC“-Knopf im Editor)
if [ -d "/Applications/VLC.app" ]; then
  echo "$OK VLC ist installiert"
else
  echo "  ○ VLC fehlt (optional, nur für den VLC-Knopf im Editor): https://www.videolan.org/vlc/"
fi

# 6. Editor-Daten vorhanden?
if [ -f "$PROJ/editor/data/state.json" ]; then
  echo "$OK Editor-Daten (Quellen, Playlists) sind auf der SSD"
else
  echo "  ○ Noch keine Editor-Daten – beim ersten Start des Editors eine Quelle anlegen"
fi

cat <<'EOF'

Fertig. So geht's weiter:
  • Editor:  „Start-Editor“ doppelklicken
  • Webapp-Test:  „Start-Webapp“ doppelklicken
  • Claude:  Claude-App → Code → neue Sitzung → Ordner „iptv“ auf der SSD wählen
             und schreiben: „Lies CLAUDE.md und mach weiter.“
  • Änderungen hochladen:  GitHub Desktop → „Push origin“

Vor dem Abziehen der SSD: Terminal-Fenster von Editor/Webapp und die Claude-Sitzung schließen.
EOF
echo; read -n 1 -s -r -p "Taste drücken zum Schließen …"
