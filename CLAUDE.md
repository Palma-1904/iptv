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
  Menü-Taste = Einrichtung. Autostart: `AutostartService` (Vordergrund-Dienst, SCREEN_ON = Aufwachen aus Standby)
  + `BootReceiver`; ab Fire OS 8 nötig: „Über anderen Apps einblenden“ (Knopf in der Einrichtung), an/aus dort.
  Home-Taste: nicht sperrbar – `onUserLeaveHint` → `AutostartService.bounceBack` holt die App nach 1,5 s zurück
  (Standard an bei Senioren, Schalter in der Einrichtung); nicht bei eigenen Wechseln/Installer/Einstellungen/VLC (`suppress`). Wird im GitHub-Workflow gebaut → https://palma-1904.github.io/iptv/app.apk
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
- **Fernwartung** (ntfy.sh, ohne Schlüssel auf Geräten): Editor → Einstellungs-Knopf „Fernwartung“ erzeugt geheimen
  Kanal (`remoteTopic` in settings.json) und legt `fernwartung.json` in den Gist. App (`Remote.java`) meldet Status an
  `<kanal>-status` (Start, Wechsel, alle 5 min), holt Befehle von `<kanal>-cmd` (alle 15 s): play (norm-Adresse),
  message, stop, reload, update, view. Senderliste fürs Umschalten aus `data/out/<liste>.m3u`.
  Update per Fernwartung: still laden (`Updater.remoteUpdate`), dann nur Fire-OS-„Installieren“; `UpdateReceiver`
  (MY_PACKAGE_REPLACED) öffnet die App danach wieder. Einmalig nötig: „Unbekannte Apps installieren“ für die App
  erlauben – Knopf in der Einrichtung (☰ 3 s).
- Player passt die Bildwiederholrate an (preferredDisplayModeId, 50 Hz bei 25/50 fps), VLC clock-jitter/synchro aus.
- Player-Puffer: Live 5 s, Filme 8 s; HLS live 30 s hinter live, bis 60 s Vorrat (Anbieter liefert 10-s-Stücke teils langsamer als Echtzeit).
- **Auffrischen im Player**: alle 2 h ruft der Player `MainActivity.requestRefresh()` → Webapp `IPTV.nativeRefresh()`
  lädt Liste+EPG neu und schickt einen neuen Baum; der Player übernimmt ihn ohne Unterbrechung (`adopt`).
- **Zugang je Playlist**: Auswahlfeld „Zugang“ in der Toolbar stellt alle Einträge einer Playlist auf eine andere
  Quelle um (`/api/switch-source`, gleiche Stream-Nummer, sonst Name). Jeder Zugang = eigene Verbindung.
  Kennungen ohne Zugang: x-work = `typ:id`, App-Speicher `Memory.norm(url)` → Favoriten/Weiterschauen bleiben.
- **Favoriten** (Filme = Werk, Serien = Gruppe) im localStorage `iptv-fav`; TV: OK lange drücken, sonst ☆ antippen.
- **Wächter** (`Waechter.java`, Bedienungshilfe/AccessibilityService, `res/xml/waechter.xml`): fängt Home/Einstellungen/
  Alle-Apps-Taste ab und holt die App sofort zurück, wenn ein fremder Bildschirm (Activity/Startseite) erscheint –
  nur wenn „Home-Taste holt die App zurück“ an ist; duldet systemui/android/Installer/Erlaubnis-/VPN-Dialoge; gibt bei
  >10 Rückholungen/min 5 min auf. Drückt bei eigenem Update im Installer „Installieren“ (`autoInstallUntil`, nur wenn
  „Fernsehen“ im Fenster steht). Mit Wächter fragt `Updater.check` nicht mehr, installiert aber nicht während jemand
  schaut (dann beim App-Start oder wenn der Schlaf-Timer anhält). Pause 10 min (☰ 3 s → Weitere Einstellungen oder
  Fernwartung `pause`). Einschalten nur per ADB; mit `WRITE_SECURE_SETTINGS` (per ADB erteilt) schaltet die App ihn
  selbst ein/aus (`Waechter.ensure` beim Start, z. B. nach „Beenden erzwingen“, das ihn in Android austrägt).
  Kein echter Kiosk möglich: Fire OS erlaubt keinen Geräteinhaber (device owner); Updates ohne Klick erst ab Android 12.
- **`Stick-einrichten.command`** (vor Ort, Mac im selben WLAN, adb aus `werkzeuge/`): verbindet (ADB-Schlüssel in
  `editor/data/adb/.android`, gilt an jedem Mac), installiert app.apk (oder Datei-Argument), setzt appops
  SYSTEM_ALERT_WINDOW + REQUEST_INSTALL_PACKAGES, `pm grant … WRITE_SECURE_SETTINGS`, trägt den Wächter ein,
  überträgt Einrichtungs-Link/Ansicht per `am broadcast -n de.iptv.firetv/.SetupReceiver` (nur ADB darf senden).
- **Neue Geräte**: nur Fire TV Stick 4K Plus/4K Max/Cube (Fire OS). Stick HD (2026), 4K Select und der neue
  Fire TV Stick 4K (2026) laufen mit Vega OS → keine APKs, App läuft dort nicht.

## Geheimnisse – nie ins Repo
`editor/data/` (Zugangsdaten, GitHub-Token, Caches) und `lokal/` (Listen mit Zugangsdaten) sind in `.gitignore`.
Vor jedem Commit prüfen, dass keine Stream-Adressen/Zugangsdaten in versionierten Dateien stehen.
Alte öffentliche Repos `tv`/`tv2` wurden gelöscht; deren Zugänge existieren nicht mehr.

## Anbieter
TRX (Xtream, Server `http://line.trxdnscloud.ru`): **nur 1 gleichzeitige Verbindung**. Abo bis 26.10.2026.
Fehlerhafte Adressen (`line.trx-ott.com`, `line.smart-ultra.cc`) existieren nicht (DNS NXDOMAIN).

## Stand / nächste Schritte (4.10.2026)
- Fire-TV-App Version 34 läuft auf Thomas' Stick (Fire OS 8): Player (libVLC), Senioren-Player mit Autostart,
  Home-Rückkehr, Fernwartung, Auto-Update, 50-Hz-Anpassung – laut Thomas funktioniert alles.
- Zugänge: TKH, ASW, KMH (je 1 Verbindung). Playlist ASW nutzt noch Zugang TKH → im Editor „Zugang“ auf ASW
  umstellen und veröffentlichen, damit ASW- und TKH-Geräte gleichzeitig schauen können.
- Offen: Senioren-Stick bei ASW (130 km) einmalig vor Ort: App-Update, „Autostart erlauben“, „Updates erlauben“.
  Fernwartung im Editor einschalten (falls noch nicht). Samsung: Smarters zeigt aus M3U keine Serien/Filme
  → Fire-TV-Stick oder Smarters per Xtream-Login. iPad mit Outplayer noch ungetestet.
- Änderungen an der App gesammelt pushen (jeder Push = Update-Frage auf allen Sticks).

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
