# Projekt: IPTV für Familie (Webapp, Playlist-Editor, Fire-TV-App)

Nutzer: Thomas (GitHub: **Palma-1904**). Sprache: Deutsch, einfache Erklärungen, Schritt für Schritt.

## Aufbau
- **Webapp** (statisch, `index.html`, `senioren.html`, `player.html`, `js/`, `css/`, `config.js`):
  online unter https://palma-1904.github.io/iptv/ (GitHub Pages, Workflow `.github/workflows/deploy.yml`).
  - Uhr oben: 5× tippen → Code `1904` = Hauptansicht, `04` = Seniorenansicht (`config.js` → `codes`).
  - Geräteerkennung in `js/m3u.js` (ios → Outplayer, mac → IINA `iina://weblink?url=` (iina.io; VLC-mac spielt vlc://-Links nicht),
    tv/web → `player.html`, app → eingebauter Player).
  - Einrichtungs-Link `#liste=BENUTZER/GIST/name` bzw. `#m3u=…` + `&ansicht=komplett|senioren`, wird im localStorage gespeichert.
  - Reiter Live/Filme/Serien/Programm (EPG aus `<liste>.epg.json`), Sprachfassungen über `x-work`/`x-lang`,
    HEVC → automatisch HD-Fassung im Browser-Player.
- **Playlist-Editor** (`editor/`, Python-Standardbibliothek, Start: `Start-Editor.command`, Port 8790):
  Quellen (Xtream/M3U), Länderfilter, Playlists mit Gruppen, Sprach-Zusammenfassung, Qualitätstest,
  Verbindungs-Anzeige, Player (▶, Durchleitung über `/api/play`), „Streams beenden“.
  Veröffentlichen → `editor/data/out/` + `lokal/` (WLAN-Test) + **Secret Gist** (Token in Editor-Einstellungen).
  EPG: Anbieter zuerst, Lücken aus `EXTRA_EPG` (epgshare01 DE1, open-epg germany) per tvg-id oder Sendername
  (`epg_name_keys` + `EPG_ALIASES`); Sender ohne tvg-id bekommen `x:<id>`. ASW: 197 → 324 von 506 Sendern.
  Zusätzlich `<liste>.xml` (XMLTV) im Gist + `url-tvg` in der M3U → für andere Apps (IPTV Smarters, TiviMate).
  Aufgaben laufen im Hintergrund (`start_job`, `/api/job` mit Fortschritt): „Veröffentlichen“ (gewählte Playlist),
  „Alle“, „Sender prüfen“ (nacheinander, Verbindungslimit, Ergebnis `data/check_<id>.json`, ✕ in der Playlist).
  Nachts automatisch veröffentlichen: Einstellungen (läuft nur, solange der Editor läuft).
  Testen ohne Hochladen/Streams: Test-Editor auf Port 8791 mit gestubbtem `gist_upload`/`measure_stream`.
  `VERSION` in `server.py` und `SERVER_VERSION` in `editor.js` bei Änderungen an beiden erhöhen.
- **Fire-TV-App** (`firetv/`, Java/WebView): lädt die Webapp; Streams spielt der eingebaute Player
  `PlayerActivity` (**libVLC 3.6.5** – ExoPlayer konnte MP2/AC3/E-AC3 der Sender nicht; 3.7.x braucht compileSdk 36;
  APK ~44 MB, nur armeabi-v7a/arm64. Webapp ruft `IPTVNative.play(json)` mit allen Live-Gruppen auf).
  Liste = Baum der ganzen Playlist (`js/tree.js`, gemeinsam für beide Ansichten; Senioren: gleiche Ebenen; „Meine Sender“ nur bei Auswahl aus config.js; gleiche Schriftgröße)
  (Übersicht › Live TV/Filme/Serien/🔍 Suche › Gruppe › Eintrag), von der Webapp
  über `IPTVNative.setTree(version, json)` nur bei Änderung/stündlich übertragen, Start über `playPath`.
  Live: ▲▼ Sender, ◀▶ Liste (links, Bild verkleinert rechts + Programmvorschau; in der Liste ◀ = eine Ebene hoch),
  OK Info. Filme/Serien: ◀▶ spulen, OK Pause, ▲▼ Folge. Fehler → OK = VLC. Escape = Zurück (Handy-Fernbedienung).
  App-Symbol/Banner als PNG (`mipmap-*/icon.png`, `drawable-xhdpi/banner.png`) – Fire-TV-Startseite zeigt keine Vektorgrafik.
  Übersicht auf dem TV: nur eine Gruppe offen, Zurück klappt sie zu (`IPTV.handleBack`).
  Menü-Taste = Einrichtung, Autostart nach Boot. Wird im GitHub-Workflow gebaut → https://palma-1904.github.io/iptv/app.apk
  Signatur: `firetv/signing/iptv.p12` (fest, damit Updates drüber installieren).
  **Lokal bauen** (Werkzeuge in `werkzeuge/`, gitignored; `firetv/local.properties` zeigt aufs SDK):
  `cd firetv && JAVA_HOME=../werkzeuge/jdk/Contents/Home GRADLE_USER_HOME=../werkzeuge/gradle-home ../werkzeuge/gradle-8.7/bin/gradle --no-daemon assembleRelease -PstartUrl=https://palma-1904.github.io/iptv/`
  (sdkmanager-Skript scheitert am Leerzeichen im SSD-Namen → Java direkt mit `SdkManagerCli` aufrufen).
- **Fassungen im Editor**: je Sprache UND Qualität eine Fassung ("DE", "DE 4K", "DE 4K HDR"; normale zuerst),
  beim Start werden bestehende Playlist-Einträge neu ermittelt. Webapp zeigt "Deutsch 4K".
- **Seniorenansicht in der App** = nur Player: startet beim Öffnen mit dem letzten Sender (`lastSeniorUrl` in
  SharedPreferences, sonst erster), Zurück verlässt den Player nicht, ☰ kurz = Senderliste, ☰ 3 s = Einrichtung
  (überall in der App, auch auf dem Startbildschirm). Im Browser bleibt die Kachelansicht.
  Ohne Live-Sender (z. B. nur Serien) öffnet der Player die Übersicht zur Auswahl (Pfad zu einer Ebene statt Eintrag).
- **Gerätespeicher** (`Memory.java`, SharedPreferences): Weiterschauen-Stellen (ab 1 min, ✓ ab 95 %, nächste Folge
  wird in der Liste vorgewählt), „🕘 Zuletzt gesehen“ (12) und „★ Lieblingssender“ (OK lange in der Liste) oben in der Übersicht.
  Zifferntasten = Sendernummer in der Gruppe. Schlaf-Timer: 3 h ohne Taste → Vorwarnung, nach 1 min Stopp.
- **Auto-Update** (`Updater.java`): Workflow schreibt `app-version.txt` (= run_number = versionCode); App prüft bei jedem Start (im Betrieb alle 6 h),
  lädt app.apk und öffnet den Installer (FileProvider `de.iptv.firetv.files`, Recht „unbekannte Apps installieren“).
- **Auffrischen im Player**: alle 2 h ruft der Player `MainActivity.requestRefresh()` → Webapp `IPTV.nativeRefresh()`
  lädt Liste+EPG neu und schickt einen neuen Baum; der Player übernimmt ihn ohne Unterbrechung (`adopt`).
- **Zugang je Playlist**: Auswahlfeld „Zugang“ in der Toolbar stellt alle Einträge einer Playlist auf eine andere
  Quelle um (`/api/switch-source`, gleiche Stream-Nummer, sonst Name). Jeder Zugang = eigene Verbindung.
  Kennungen ohne Zugang: x-work = `typ:id`, App-Speicher `Memory.norm(url)` → Favoriten/Weiterschauen bleiben.
- **Favoriten** (Filme = Werk, Serien = Gruppe) im localStorage `iptv-fav`; TV: OK lange drücken, sonst ☆ antippen.

## Geheimnisse – nie ins Repo
`editor/data/` (Zugangsdaten, GitHub-Token, Caches) und `lokal/` (Listen mit Zugangsdaten) sind in `.gitignore`.
Vor jedem Commit prüfen, dass keine Stream-Adressen/Zugangsdaten in versionierten Dateien stehen.
Alte öffentliche Repos `tv`/`tv2` wurden gelöscht; deren Zugänge existieren nicht mehr.

## Anbieter
TRX (Xtream, Server `http://line.trxdnscloud.ru`): **nur 1 gleichzeitige Verbindung**. Abo bis 26.10.2026.
Fehlerhafte Adressen (`line.trx-ott.com`, `line.smart-ultra.cc`) existieren nicht (DNS NXDOMAIN).

## Stand / nächste Schritte (3.10.2026)
1. GitHub-Token (nur „gist“) im Editor eintragen, Webapp-Adresse `https://palma-1904.github.io/iptv/`, Playlist „ASW“ veröffentlichen.
2. Fire TV: VLC + Downloader installieren, `palma-1904.github.io/iptv/app.apk` laden, Einrichtungs-Link eingeben.
   App wurde gebaut und geprüft, aber **noch nie auf echtem Stick getestet** → gemeinsam testen.
3. Offen/ideen: EPG täglich automatisch veröffentlichen (EPG reicht 36 h); iPad mit Outplayer testen
   (`outplayer://`-Link noch unbestätigt).

## Arbeitsweise über mehrere Macs (MacBook Neo, Büro-Mac, …)
- **Die SSD ist die einzige Arbeitskopie.** Projekt, `.git`, `editor/data/` liegen darauf; GitHub dient nur zum
  Veröffentlichen (Pages + App-Bau), nicht zum Abgleich zwischen Macs. Kein Pull nötig, solange niemand auf github.com ändert.
- Neuer Mac: `Einrichten-neuer-Mac.command` (Command Line Tools, safe.directory, GitHub Desktop, VLC-Check).
- Git-Identität steht im Repo (`.git/config`): „Max Mustermann <mieten.riffe3y@icloud.com>“ – nicht ändern.
- **Claude committet lokal** (mit Co-Authored-By), **Thomas pusht** in GitHub Desktop („Push origin“).
  Claude hat keine GitHub-Zugangsdaten auf der Kommandozeile.
- Vor dem Abziehen der SSD: Terminal-Fenster von Editor/Webapp und die Claude-Sitzung schließen.

## Testen lokal
`Start-Webapp.command` (Port 8765, auch im WLAN), `Start-Editor.command` (Port 8790, beendet alten Editor vorher).
