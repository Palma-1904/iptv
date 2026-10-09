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
  Für Sender ohne Bild sucht die Prüfung andere Fassungen desselben Senders im Katalog (`channel_key` wie
  `sortKey`/TV_ALIAS, oder gleiche tvg-id; gleiches Länderkürzel zuerst, HEVC/4K zuletzt), spielt bis zu 4 an und
  bietet „Ersetzen“ / „Alle ersetzen“ an (Platz, Gruppe, eigener Name bleiben). „Doppelte“ zeigt ✓/✕ der letzten
  Prüfung, behält die beste funktionierende Fassung und kann nur die doppelten Sender prüfen (`/api/check` mit `keys`,
  Ergebnis wird zusammengeführt). Test: Test-Editor mit Kopie der Daten (DATA/STATE_FILE umbiegen), nie echte state.json.
  Schnell: nur 16 KB anspielen, bei „belegt“ 3 s warten (max. 6×). **Nur über den eigenen Zugang der Playlist**
  (Thomas: Zugänge TKH/ASW/KMH nie vermischen; Playlist X nutzt nur Zugang X). Nachts (Einstellung
  „automatisch“): erst alle Playlists prüfen, dann veröffentlichen.
- **Player-Umschalten** (`PlayerActivity`): libVLC `stop()` blockiert, wenn der Anbieter die Verbindung nicht sauber
  schließt → früher ANR. Jetzt: jeder Start bekommt einen frischen `MediaPlayer` (`newPlayer`), der alte wird im
  Thread „VLC-Abbau“ gestoppt/freigegeben (`dispose`); der neue startet erst, wenn der alte weg ist (max. 4 s, 1 Verbindung).
  Ton über `--aout=android_audiotrack` (OpenSL ES stürzt bei zwei Playern ab: „theOneTrueRefCount“).
  Trennzeilen des Anbieters („##### … #####“, `SEPARATOR`) filtert `load_catalog`; `remove_separators()` beim Start.
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
  `<kanal>-status`, empfängt Befehle von `<kanal>-cmd` über eine offen gehaltene Verbindung (Stream, ntfy-Keepalive 45 s):
  play (norm-Adresse), message, stop, reload, update, view, setting, pause, watch. Senderliste fürs Umschalten aus
  `data/out/<liste>.m3u`. **ntfy.sh anonym: 250 Nachrichten/Tag je IP** (Abfragen frei) – darum meldet ein Stick sich
  von selbst nur alle 3 h (+ beim Start/Listenwechsel, Antworten auf Befehle); Änderungen nur, solange der Editor
  zuschaut: Fernwartung öffnen → `watch` (900 s) an alle, alle 10 min erneut. Vorher (alle 5 min) war das Kontingent
  mit 2 Sticks am selben Anschluss täglich erschöpft (HTTP 429 „daily message quota reached“).
  Update per Fernwartung: still laden (`Updater.remoteUpdate`), dann nur Fire-OS-„Installieren“; `UpdateReceiver`
  (MY_PACKAGE_REPLACED) öffnet die App danach wieder. Einmalig nötig: „Unbekannte Apps installieren“ für die App
  erlauben – Knopf in der Einrichtung (☰ 3 s).
- **Fernwartung über Firebase** (ab App 57; Thomas' Projekt „iptv-fernwartung“, Spark kostenlos, Realtime Database
  `https://iptv-fernwartung-default-rtdb.europe-west1.firebasedatabase.app/`, Regeln: nur Pfade ≥ 24 Zeichen lesbar/
  schreibbar, Wurzel gesperrt). `fernwartung.json` im Gist enthält `topic` + `firebase`; settings.json `remoteFirebase`.
  Stick: Status per PUT `<kanal>/status/<gerät>` (bei Änderung, alle 5 min), Befehle live per SSE aus
  `<kanal>/inbox/<gerät>/<id>` (nach Ausführen DELETE). Editor: `fb_devices`, `remote_cmd` schreibt in die Inboxen
  (all / list:x aufgelöst), ntfy.sh nur noch für Geräte mit älterer App. Kein Tageslimit mehr.
- **Weitere App-Funktionen (App 57)**: Hänger-Wächter (15 s ohne Fortschritt → Sender neu, 3× in 3 min → wie Fehler),
  Ersatz-Fassungen `x-alt` (Editor schreibt bis zu 2 Adressen je Live-Sender; App wechselt bei Fehler), gleichmäßige
  Lautstärke (`normvol`, Einstellung/Fernwartung), Erinnerungen (⏯ in der Liste auf einem Sender = an nächste Sendung;
  `Reminders.java`), Zustandsbericht (`Diag.java`: WLAN-RSSI, Hänger/Fehler/Ersatz heute → `diag` im Status),
  nächtliches Auffrischen 3–5 Uhr (Webapp neu laden + Updates, nicht wenn jemand schaut; `quietUntil` verhindert
  Autostart eines Senders), Update-Prüfung beim Wiederaufrufen (≤ alle 6 h).
- **Fernseher aus** (Stick bleibt an, HDMI meldet `ACTION_HDMI_AUDIO_PLUG` = 0 länger als 20 s): Player hält an
  (Status `tvoff`, gibt die einzige Verbindung frei), beim Wiedereinschalten läuft es von selbst weiter. Der
  Anfangszustand zählt nicht; kurze Aussetzer (Tonformat-Wechsel) werden ignoriert. Ungetestet am echten TV.
- Player passt die Bildwiederholrate an (preferredDisplayModeId, 50 Hz bei 25/50 fps), VLC clock-jitter/synchro aus.
- Player-Puffer: Live 5 s, Filme 8 s; HLS live 30 s hinter live, bis 60 s Vorrat (Anbieter liefert 10-s-Stücke teils langsamer als Echtzeit).
- **Auffrischen im Player**: alle 2 h ruft der Player `MainActivity.requestRefresh()` → Webapp `IPTV.nativeRefresh()`
  lädt Liste+EPG neu und schickt einen neuen Baum; der Player übernimmt ihn ohne Unterbrechung (`adopt`).
- **Zugang je Playlist (1:1, Thomas' Wunsch: nie vermischen)**: jede Playlist hat `pl.source` (beim Start aus den
  Einträgen ermittelt); keine Quellen-Auswahl mehr – Playlist wählen = Senderangebot ihres Zugangs (`selectSource`).
  Chip „Zugang: X“; Menü ⋯: Senderangebot aktualisieren, Zugangsdaten bearbeiten (Xtream-Daten), anderen
  (freien) Zugang zuordnen (`/api/switch-source`, gleiche Stream-Nummer, sonst Name), neue Playlist nur mit freiem
  Zugang, neuer Zugang → passende Playlist anlegen. `addToPlaylist` nimmt nur Sender des eigenen Zugangs.
  Kennungen ohne Zugang: x-work = `typ:id`, App-Speicher `Memory.norm(url)` → Favoriten/Weiterschauen bleiben.
- **Editor-Extras**: „★ Meine Sender“ je Playlist (`pl.myChannels`, ☆ in der Zeile; beim Veröffentlichen erste Gruppe
  „★ Meine Sender“, Sender bleiben auch in ihren Gruppen). Tägliche Sicherung `data/backup/state-JJJJ-MM-TT.json`
  vor der ersten Änderung des Tages (21 behalten), ⋯ → „Frühere Fassung wiederherstellen“. Abo-Erinnerung
  (`#expiry-warn`, ≤ 30 Tage). Gerätenamen/Notizen der Fernwartung in settings.json `deviceNotes`.
  „Nur deutsche Fassungen“ je Playlist (`pl.germanOnly`, wirkt beim Veröffentlichen; ASW 5,1 → 1,4 MB).
  „Trotzdem prüfen“: Geräte der Liste per Fernwartung `stop`, danach `reload`.
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
TRX (Xtream, Server heute `line.smrtrx.cfd`): **nur 1 gleichzeitige Verbindung je Zugang**. Abos (Stand 8.10.2026):
TKH bis 26.10.2027, ASW bis 04.10.2027, KMH bis 04.10.2027 (player_api für KMH/ASW zeitweise HTTP 404, Streams laufen).
Fehlerhafte Adressen (`line.trx-ott.com`, `line.smart-ultra.cc`) existieren nicht (DNS NXDOMAIN).

## Stand / nächste Schritte (5.10.2026)
- Online: App **39** (alles bis „Fehlermeldung eines Auftrags“). Lokal committet, **noch nicht gepusht**:
  Editor-Anzeige App-Stand (`/api/app-status`, Knopf `#appver`: grün online, gelb wird gebaut/veröffentlicht, rot
  fehlgeschlagen, „n nicht gepusht“ per `git rev-list @{u}..HEAD`; GitHub-API ohne Token), Server VERSION 15.
  → Thomas: Editor neu starten (Start-Editor.command), dann pushen (wird App 40).
- Editor-Fix: `followJob` liest `/api/job` ohne `api()` (Feld „error“ eines fehlgeschlagenen Auftrags ist kein
  Abruffehler) – vorher hing das Fenster bei „Start …“, z. B. wenn der Zugang belegt war.
- Senderprüfung TKH (4.10. abends): 225 geprüft, 1 ohne Bild: „DE| DAZN 1 ᴴᴰ“ → Ersatz „DE| DAZN 1 SD“ läuft
  (in „Sender prüfen“ → Ersetzen, noch nicht übernommen). ProSieben in TKH wurde gegen „DE| PROSIEBEN“ getauscht.
- **Stand 8.10. abends – Sticks zu Hause alle Liste TKH, Ansicht Komplett, Wächter/Autostart/Home-Rückkehr AUS**
  (Thomas bedient selbst): Wohnzimmer 192.168.6.112 (AFTMM, Fire OS 6), Küche .110, Schlafzimmer .47 (AFTSSS,
  „Thomas' 2. FireTVStick“, App neu installiert). „Max' Fire TV Stick“ .111 (Fire OS 8, Liste kmh, Senioren, Wächter an).
  SetupReceiver kann zusätzlich `autostart`/`home`/`waechter` = 0/1; Stick-einrichten fragt „Wächter und Autostart?“.
- Ältere Notiz – Sticks zu Hause (ADB-Debugging an, Mac freigegeben; Schlüssel in editor/data/adb):
  Wohnzimmer 192.168.6.56 = Fire TV Stick 4K 2018 (AFTMM, **Fire OS 6** → kein Wächter, Liste tkh, Senioren);
  Küche 192.168.6.110 = „Thomas' FireTVStick“, Stick 3. Gen. 2020 (AFTSSS, Fire OS 7.7, Liste asw, Senioren,
  **Wächter getestet: läuft**, Home → nach ~1 s Abdeckung, nach ~5 s App zurück); Schlafzimmer .47 = AFTSSS
  Fire OS 7 (AirReceiver, ohne unsere App, nicht freigegeben). Kein Gerät mit Fire OS 8 zu Hause.
- **Generalprobe Auto-Update bestanden** (Küche 36 → 37 ohne Klick, ~45 s bis der Sender wieder läuft).
  Wohnzimmer (Fire OS 6) fragt wie bisher. Noch zu bestätigen: „Fernseher aus“ am echten TV; kein
  „Bild – schwarz – Bild“ mehr beim Umschalten (Bildrate gemerkt/abgedeckt, ab App 38).
- Hardware: Thomas bestellt **Fire TV Stick 4K Max** (Senioren) und **4K Plus** (zu Hause, Ersatz für Wohnzimmer),
  beide Fire OS 8. Bei Ankunft: Fire TV einrichten, ADB-Debugging an, IP nennen → `Stick-einrichten.command`,
  Wächter auf Fire OS 8 testen, dann 4K Max vor Ort bei den Senioren (ASW, 130 km) mit dem Mac einrichten.
- ntfy.sh: 250 Nachrichten/Tag je Anschluss (anonym) – am 4.10. zu Hause erschöpft, ab App 37 sparsam.
- Zugänge: TKH, ASW, KMH (je 1 Verbindung). Playlist ASW nutzt evtl. noch Zugang TKH → im Editor „Zugang“ prüfen.
- Offen: Samsung: Smarters zeigt aus M3U keine Serien/Filme → Fire-TV-Stick oder Smarters per Xtream-Login.
  iPad mit Outplayer noch ungetestet.
- Jeder Push = neue App-Version (auch bei reinen Editor-/Notiz-Änderungen) → Änderungen gesammelt pushen.

## Arbeitsweise über mehrere Macs (MacBook Neo, Büro-Mac, …)
- **Die SSD ist die einzige Arbeitskopie.** Projekt, `.git`, `editor/data/` liegen darauf; GitHub dient nur zum
  Veröffentlichen (Pages + App-Bau), nicht zum Abgleich zwischen Macs. Kein Pull nötig, solange niemand auf github.com ändert.
- Neuer Mac: `Einrichten-neuer-Mac.command` (Command Line Tools, safe.directory, GitHub Desktop, VLC-Check).
- Git-Identität steht im Repo (`.git/config`): „Max Mustermann <mieten.riffe3y@icloud.com>“ – nicht ändern.
- **Claude committet lokal** (mit Co-Authored-By), **Thomas pusht** in GitHub Desktop („Push origin“) oder im
  Editor (App-Anzeige ▾ → „Jetzt pushen“, `/api/git-push`: Token aus settings.json, braucht Scopes **repo + workflow**;
  pusht `HEAD:main` per https mit Token, setzt danach `refs/remotes/origin/main`; Fehlertext ohne Token).
  „Pushen und danach alle Geräte aktualisieren“ wartet auf den Bau (`update_after_build`) und schickt `update` an alle.
  Claude hat keine GitHub-Zugangsdaten auf der Kommandozeile.
- Nach „Veröffentlichen“ schickt der Editor `refresh {rev}` an `list:<slug>`: Geräte laden die Gist-Revision
  (`…/raw/<rev>/datei`, sofort, ohne 5-min-Cache; `IPTV.refreshTo`, 20 min gültig) im Hintergrund ohne Unterbrechung.
- Vor dem Abziehen der SSD: Terminal-Fenster von Editor/Webapp und die Claude-Sitzung schließen.

## Testen lokal
`Start-Webapp.command` (Port 8765, auch im WLAN), `Start-Editor.command` (Port 8790, beendet alten Editor vorher).
