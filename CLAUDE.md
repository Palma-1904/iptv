# Projekt: IPTV für Familie (Webapp, Playlist-Editor, Fire-TV-App)

Nutzer: Thomas (GitHub: **Palma-1904**). Sprache: Deutsch, einfache Erklärungen, Schritt für Schritt.

## Aufbau
- **Webapp** (statisch, `index.html`, `senioren.html`, `player.html`, `js/`, `css/`, `config.js`):
  online unter https://palma-1904.github.io/iptv/ (GitHub Pages, Workflow `.github/workflows/deploy.yml`).
  GitHub Pages: `cache-control: max-age=600` – Geräte zeigen bis zu 10 min alte Dateien. Der Workflow hängt
  `?v=<run_number>` an alle js/css/config.js-Verweise in den HTML-Dateien (nur in `_site`), damit nach dem Neuladen alles zusammenpasst.
  - Uhr oben: 5× tippen → Code `1904` = Hauptansicht, `04` = Seniorenansicht (`config.js` → `codes`).
  - Geräteerkennung in `js/m3u.js` (ios → Outplayer, mac → IINA `iina://weblink?url=` (iina.io; VLC-mac spielt vlc://-Links nicht),
    tv/web → `player.html`, app → eingebauter Player). Mac: erst `player.html` (Anbieter hat kein https → Browser sperrt,
    Seite erklärt: „In IINA öffnen“ / „IINA laden“ / „Immer in IINA öffnen“ / „Adresse kopieren“); nach „Immer …“
    (`iptv-iina-ok`) direkt `iina://` – automatisch erkennen geht nicht sicher (Safari-Meldung „Adresse ungültig“).
  - Einrichtungs-Link `#liste=BENUTZER/GIST/name` bzw. `#m3u=…` + `&ansicht=komplett|senioren`, wird im localStorage gespeichert.
  - Reiter Live/Filme/Serien/Programm (EPG aus `<liste>.epg.json`), Sprachfassungen über `x-work`/`x-lang`,
    HEVC → automatisch HD-Fassung im Browser-Player.
  - **Browser (iPad/iPhone/Mac, `SPLIT` = nicht TV und nicht App)**: links Kategorien (Gruppen; Filme/Serien mit
    „★ Meine Favoriten“), rechts Inhalt: Live = Zeilen mit jetzt/danach/Fortschritt, Filme = Kacheln (200 je Seite,
    „Weitere anzeigen“), Serien = Kacheln → Staffeln/Folgen mit Sprachleiste. iPhone (≤ 700 px): Kategorien hinter
    Knopf „☰ Kategorie · Anzahl ▾“ (`.catmenu`, klappt `.cats.open` auf; daneben tippen/Escape schließt). Gewählte Kategorie in `iptv-cat`. Serien-Kategorie aus `x-cat` (Editor: Gruppe der Playlist).
    Fire TV/App: unverändert aufklappbare Gruppen (Thomas: Stick-App nicht ändern).
    **Outplayer bequemer** (kein automatischer Rücksprung möglich – Outplayer hat kein x-callback): Schnell-Leiste
    oben „Zuletzt: X  ◀ · ▶ nochmal · ▶▶ / nächste Folge ✕“ (`iptv-lastplay`, 12 h), Kategorie „🕘 Zuletzt gesehen“
    je Reiter (`iptv-recent`, 36), Hinweis „oben links ◀“ die ersten 5 Male (iOS). Gewählte Kategorie bleibt.
    Outplayer fehlt? Nach dem Tippen 4 s warten: bleibt die Seite sichtbar → Dialog mit App-Store-Link (id1449923287);
    einmal erfolgreich geöffnet (Seite ging in den Hintergrund) → `iptv-outplayer-ok`, nie wieder prüfen.
    Link neben der Uhr (`#getapp`, `updateAppLink`): „⬇ Outplayer laden“ (iOS, App Store) bzw. „⬇ IINA laden“ (Mac,
    iina.io), bis `iptv-outplayer-ok`/`iptv-iina-ok` auf diesem Gerät gesetzt ist (je Gerät, localStorage).
    **iOS: Outplayer oder VLC** (`iptv-iosplayer`, Standard Outplayer; Knopf „mit X“ in der Schnell-Leiste bzw. Dialog
    „Startet nichts?“ → „Stattdessen … verwenden“). VLC über `config.js` `player.iosVlc` =
    `vlc-x-callback://x-callback-url/stream?url=…&x-success=<Webapp>` → springt nach dem Schließen zurück
    (auf echtem iPhone/iPad noch ungetestet; evtl. öffnet Safari dabei einen neuen Tab). `iptv-vlc-ok` wie bei Outplayer.
    **Im Browser keine Seniorenansicht und kein Reiter „Programm“** (Thomas 10.10.): `mode.js` leitet senioren.html
    im Browser auf index.html (ansicht=komplett) um, Code „04“ wirkt dort wie die Hauptansicht; Editor zeigt fürs
    iPhone/iPad nur noch einen QR-Code. App (UA „IPTVApp“) und Fire-TV-Browser behalten Senioren und „Programm“.
- **Playlist-Editor** (`editor/`, Python-Standardbibliothek, Start: `Start-Editor.command`, Port 8790):
  Quellen (Xtream/M3U), Länderfilter, Playlists mit Gruppen, Sprach-Zusammenfassung, Qualitätstest,
  Verbindungs-Anzeige, Player (▶, Durchleitung über `/api/play`), „Streams beenden“.
  Veröffentlichen → `editor/data/out/` + `lokal/` (WLAN-Test) + **Secret Gist** (Token in Editor-Einstellungen).
  EPG: Anbieter zuerst, Lücken aus `EXTRA_EPG` (epgshare01 DE1, open-epg germany) per tvg-id oder Sendername
  (`epg_name_keys` + `EPG_ALIASES`); Sender ohne tvg-id bekommen `x:<id>`. ASW: 197 → 324 von 506 Sendern.
  **Ab Editor 27 (15.10.)**: Quellen zusätzlich epgshare01 AT1/CH1 (Sky Cinema Special/Thriller/Premieren …), Namens-
  schlüssel streicht jedes Kürzel „XX| “ (WOW| …); ASW 173 → 220 Sender. **Inhaltstext**: Einträge `[start, ende,
  titel, text]` (`prog_entry`: Untertitel + desc, max. `EPG_DESC_MAX` 250 Zeichen; epg.json ASW ~1 MB, TKH ~600 KB),
  auch im XMLTV. Event-Sender (PPV): `fill_from_events` liest Sendung/Uhrzeit aus dem Namen (`event_programme`,
  Namen frisch per `get_live_streams`, Dauer `EVENT_HOURS`), tvg-id `x:ev:<stream>` – der Anbieter führt die Namen
  aber oft veraltet. Geräte bekommen im Baum Text nur für die ersten 4 Sendungen (`nativeEpg`). Anzeige: Webapp ⓘ in
  der Live-Zeile, App Info-Leiste (OK) + Vorschau in der Liste, Admin „Was läuft“ (Stick schickt Text der laufenden
  Sendung im Status-`epg`). epg.json `generated` > 24 h → Hinweis in der Webapp (`.stale`) und Warnung „🗓“ auf der
  Admin-Seite (`lists/<slug>.epg` aus `fb_sync_lists`). Automatisch per GitHub-Auftrag: Thomas will das noch nicht.
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
- **Schnellstart** (`js/m3u.js`): letzte Liste + EPG in IndexedDB (`iptv-schnellstart`, ein Eintrag je eingerichteter
  Adresse); Start sofort daraus, nach 1,5 s `nativeRefresh` (neue Liste im Hintergrund, Baum an die App).
- **Weitere App-Funktionen (App 57)**: Hänger-Wächter (15 s ohne Fortschritt → Sender neu, 3× in 3 min → wie Fehler),
  Ersatz-Fassungen `x-alt` (Editor schreibt bis zu 2 Adressen je Live-Sender; App wechselt bei Fehler), gleichmäßige
  Lautstärke (`normvol`, Einstellung/Fernwartung), Erinnerungen (⏯ in der Liste auf einem Sender = an nächste Sendung;
  `Reminders.java`), Zustandsbericht (`Diag.java`: WLAN-RSSI, Hänger/Fehler/Ersatz heute → `diag` im Status),
  nächtliches Auffrischen 3–5 Uhr (Webapp neu laden + Updates, nicht wenn jemand schaut; `quietUntil` verhindert
  Autostart eines Senders), Update-Prüfung beim Wiederaufrufen (≤ alle 6 h).
- **Handy-Fernbedienung** (`fernbedienung.html`, Link `#k=<schlüssel>&db=<firebase-host>`, im Handy gemerkt):
  zeigt Geräte live (EventSource auf `<k>/status`), was läuft + Programm jetzt/danach (`epg`, `norm` im Status),
  Senderliste `<k>/sender/<gerät>` („★ Meine Sender“, sonst Gruppe des laufenden Senders; vom Stick aus
  `PlayerActivity.treeRoot`, nur bei Änderung, ≤ 1×/min), Knöpfe ▲▼ (`zap` d=±1), ⏯ (`toggle`), ⏹, Nachricht, Neu laden.
  Befehle per POST in `<k>/inbox/<gerät>`. Zwei Schlüssel: Haupt-Link = Fernwartungs-Kanal (Editor → Fernwartung →
  „📱 Handy-Fernbedienung für alle Geräte“, QR über qrcodejs von cdnjs; zusätzlich „App aktualisieren“) und je Stick ein
  eigener `handyKey` (32 Zeichen, prefs; QR in ☰ 3 s → Weitere Einstellungen, zxing; „Neuer Code“ macht alte ungültig).
  Über den Geräteschlüssel nur `PHONE_ACTIONS` (play/zap/toggle/stop/message/reload/watch), Status ohne cfg/diag.
  Firebase speichert leere Arrays/Strings nicht (`epg` fehlt dann).
  **⚙️ Einstellungen** (nur Haupt-Link, aufklappbar, ⚠️ wenn Erlaubnis fehlt): Schalter autostart/home/normvol/waechter
  (`setting`), Wächter-Pause (`pause`), Häkchen Autostart/Updates erlaubt, WLAN/Hänger aus `diag`.
  **Einschalten/App öffnen** (`wake`, ab App 63): WakeLock ACQUIRE_CAUSES_WAKEUP + MainActivity starten; Remote läuft
  auch nur mit `AutostartService` (init dort, letzte Liste in prefs `remoteList`); Befehle ohne offene App öffnen sie
  und werden nach 8 s nachgereicht. Getestet Anita (Fire OS 8): 2 min Standby → 4 s bis wach, Sender läuft. Länger im
  Standby (Tiefschlaf) ungetestet. Status `screen` (false = Standby), `closed` = App zu. `restart` = Webapp neu + Update.
  Admin-Seite außerdem: eigene Namen/Notizen (`<kanal>/notes`), Abo-Ablauf (`<kanal>/accounts`, aus `cache/expires.json`,
  `fb_sync_meta`), Warnungen oben (offline > 30 min, WLAN < -72 dBm, Update verfügbar, Abo ≤ 30 Tage),
  Ansicht/Liste wechseln in ⚙️.
  **Aufbau der Handy-Seite** (aufgeräumt 10.10.): Warnungen · Reiter · „Was läuft“ (+ Knopf „Jetzt aktualisieren“) ·
  eine Fernbedienung (⏻ Ein/Aus · ☰ · ↩ · 📷 Livebild an/aus, Kreuz mit OK, ⏪ ⏯ ⏩, ⏹ Stopp/💬/↻) · ⚙️ Einstellungen &
  Wartung (neu starten, aktualisieren, Ansicht, Liste, Schalter) · 🔔 Erinnerungen · Alles auf diesem Fernseher.
  Die alten ▲▼ (zap) sind weg – das Kreuz schaltet im Player genauso um.
  **🎮 Steuerkreuz** (`key`): `key {k}` (up/down/left/right/ok/back/menu/playpause/rew/ff)
  → `Instrumentation.sendKeyDownUpSync` im eigenen Fenster (auch Dialoge); fremde Fenster (Fire-TV-Menü) gehen nicht
  (SecurityException → Rückmeldung). Getestet Anita: ☰ öffnet Senderliste, ▼ bewegt Auswahl.
  **⏾ Ausschalten** (`sleep`): Player stoppen (Verbindung frei), dann Wächter `GLOBAL_ACTION_LOCK_SCREEN` (API 28+) →
  Standby; ohne Wächter nur Stopp. Knopf nur bei laufendem Wächter. Getestet Anita: aus → 20 s → `wake` → Sender läuft.
  **📷 Livebild** (`LiveShot.java`, nur Haupt-Link, Knopf 📷 in der Fernbedienung, gemerkt): `shot {on}` (60 s gültig,
  Seite fragt alle 40 s), Stick schickt ~alle 1,5 s + 0,5 s nach jeder Taste ein JPEG 480×270 (~20 KB) nach
  `<kanal>/shot/<gerät>`. Video per PixelCopy aus der SurfaceView, Oberfläche per PixelCopy des Fensters (API 26+;
  Senderlogos sind Hardware-Bitmaps), Fire OS 6: nachzeichnen. Dialoge nicht mit drauf. Getestet Anita: Bild + Liste.
  **Anitas Fire TV Stick** (10.10.2026): 192.168.6.116, AFTMA08C15 (4K Max, Fire OS 8), Liste asw, Senioren, Wächter an.
  **Erinnerungen vom Handy**: 🔔 neben jedem Live-Sender klappt das Programm auf (Admin: aus epg.json; Geräte-Link: `p`
  = nächste 8 Sendungen je Eintrag in `sender/<gerät>`), Sendung antippen → `remind {norm, t, title, ch, on}`
  (`Reminders.set`, auch über den Geräteschlüssel erlaubt); Stick meldet `rem` im Status, Liste „🔔 Erinnerungen“ oben.
  Webapp übergibt den Baum auch nach dem Laden aus dem Netz (`loadNet` → `pushTree`), sonst fehlte er bis zum ersten Senderstart.
  **Admin-Ansicht** (nur Haupt-Link): Editor schreibt `<kanal>/lists/<slug>` = {name, link, rev} (`fb_sync_lists`, bei
  Fernwartung-Status und nach Veröffentlichen mit Gist-Revision). Die Seite lädt M3U + EPG der Liste des gewählten
  Sticks direkt aus dem Gist, baut mit `js/tree.js` denselben Baum wie die App (Live TV/Filme/Serien/Suche, Shim
  `IPTV.nativeLeaf` mit `norm`) und schickt `play {norm}`; laufender Sender markiert, Live mit laufender Sendung.
  Darstellung zum Auf-/Zuklappen (je Ebene ein Bereich offen, `nav` = Pfad, offene Köpfe bleiben oben stehen);
  im Admin-Modus keine extra „Meine Sender“-Liste (Thomas: nur „Alles auf diesem Fernseher“).
  **Ab 15.10.** (nur Haupt-Link, braucht `lists`): Karte „📡 Wer schaut gerade“ (`#conn`, `busyByList`: Stick spielt/
  pausiert, nicht Standby, Meldung < 15 min; Webapp-Geräte melden sich nicht) + Warnung bei Doppelbelegung einer
  Liste; aufklappbar „🔗 Webapp-Links senden“ (`#adm`, `renderAdm`): je Liste Teilen (navigator.share, Text mit
  Kurzanleitung), Kopieren, QR (qrcode-generator 1.4.4 von cdnjs, erst bei Bedarf). Aufklappkarten: Klasse `.fold`.
  **„Alles auf diesem Fernseher“ neu (15.10.)** – Admin UND Geräte-Link (Familie): Reiter 📺 Live / 🎬 Filme /
  📚 Serien, Auswahlliste der Gruppe (Serien: Kategorie x-cat; gemerkt `iptv-fernbedienung-tab/-grp`), Suche über
  alles, je 150 Einträge + „Weitere“; Filme mit Sprachfassungen → Sprache wählen; Serien aufklappen (große Liste: Folgen
  aus `<liste>.s<k>.json`, `loadEps`), Sprachleiste. Quelle: Admin `<kanal>/lists`, Geräte-Link: Stick meldet im
  Handy-Status `m3u` (Adresse der Liste, ab App 80) – vorher nur die eine Gruppe vom Stick (`sender/<gerät>`).
  Der alte Aufklapp-Baum (drawTree/dirButton) ist entfernt.
  „📋 Link kopieren“ kopiert nur den Link (Anleitungstext nur über „Teilen“). Die Karte heißt „🔗 Links senden“ und
  enthält auch die Handy-Fernbedienung je Fernseher (Geräte-Link `cfg.phone`, ab App 72; Admin-Link nie).
  **🖥 Editor aus der Ferne** (`#edi`, Editor ab VERSION 28): Editor-Thread `remote_editor_loop` holt alle 10 s
  `<kanal>/editor/inbox` ab (publish {ids}, check {id}, push {updateDevices}, ping; älter als 10 min = verworfen),
  löscht sie, schreibt `<kanal>/editor/status` (Mac-Name, Version, laufende Aufgabe, `ahead` = nicht gepushte
  Commits, Push-Stand, Online-Version/Bau höchstens alle 5 min wegen GitHub-Limit, Listen, Nacht-Ergebnis, letzte
  Rückmeldung `log`). Admin-Seite zeigt „🟢 läuft auf …/⚪ aus“, Knöpfe nur wenn online (Status < 90 s alt) und keine
  Aufgabe läuft. Getestet 15.10. mit Test-Editor (Veröffentlichen vom Handy ✓, Push gestubbt).
  **⏰ Zeitplan je Stick** (ab App 71, `Schedule.java`): ⚙️ → „Aus um / An um / Schlaf-Timer“ → Befehl `schedule
  {off, on, sleepH}` (Prefs schedOff/schedOn/sleepH, Status `cfg.sched`). AlarmManager RTC_WAKEUP (exakt, holt aus
  dem Standby), neu gestellt beim Hochfahren, App-/Dienst-Start und nach jedem Auslösen. Aus = Player stoppen +
  Wächter-Standby (`Remote.scheduledOff`, ohne Wächter nur Stopp), An = `Remote.wake()`. Schlaf-Timer 0/1/2/3/4/6 h
  (0 = aus, Standard 3; früher fest `SLEEP_MS`). Am echten Stick noch ungetestet.
  **Ab App 72** (Admin ⚙️): Stick-Auswahl als Auswahlliste `#devsel` (Zustand · Name · Liste) statt Reiter;
  „📱 Handy-Fernbedienung für diesen Fernseher“ (Status `cfg.phone` = Geräte-Link, nur im Haupt-Kanal) mit Teilen/
  Kopieren/QR und „♻ Neuer Code“ (Befehl `newcode` → `Remote.newHandyKey`); „⧉ Einstellungen übertragen“ auf alle /
  alle derselben Liste / einen Stick (setting autostart/home/normvol/ownlists + schedule; Wächter bleibt je Stick);
  „🔧 Fehlende Erlaubnisse über den Wächter setzen“ (Befehl `grant` → `Waechter.grant`: öffnet
  ACTION_MANAGE_OVERLAY_PERMISSION bzw. …UNKNOWN_APP_SOURCES für die App, legt nur den Schalter in der Nähe des
  Textes „Fernsehen“ um, Zurück, nächste; 90 s, Ergebnis per report; Fire OS zeigt die Seiten nicht überall –
  dann nur ADB/vor Ort). Was nur vor Ort/ADB geht: Overlay-, Installations-Erlaubnis, Ersteinrichtung Wächter.
  **Ab App 74**: „🧹 Zuletzt gesehen, Lieblingssender & Favoriten löschen“ (Befehl `forget`: Memory recent/favLive/
  resume + lastSeniorUrl, Webapp-localStorage iptv-fav/-recent/-lastplay/-cat, Seite neu); eigene Listen und
  Erinnerungen bleiben. Gedacht vor dem Wechsel eines Sticks auf eine andere Liste.
- **Selbst-ADB geht nicht** (getestet 9.10.2026, Wohnzimmer Fire OS 6): App verband sich mit dem eigenen adbd
  (127.0.0.1 und eigene WLAN-IP, Port 5555) → adbd schließt sofort (EOF), vom Mac aus klappt dasselbe Protokoll.
  Fire OS sperrt ADB-Verbindungen vom Gerät selbst. Wieder entfernt (Revert). Außerdem: Fire OS erlaubt nur EINE
  ADB-Verbindung gleichzeitig – nach Arbeiten vom Mac `adb disconnect`. Klickfreie Updates nur mit Wächter (Fire OS 7/8).
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
- **Länderfilter Filme/Serien**: Deutsch (DE/AT/CH) + MULTI vorausgewählt, andere Kürzel nur nach Antippen (`src.vodShown`),
  `hiddenCountries` gilt weiter für alle Reiter (`ccHidden`/`toggleCc`, nur Anzeige links; `variantsOf` unverändert).
  Neue Gruppen in der Playlist kommen zugeklappt an; zugeklappte Gruppen im localStorage `editor-collapsed`.
  Länder-Kürzel: zusätzlich „🇩🇪 nur Deutsch (+ MULTI)“ (= PREFERRED: DE/AT/CH/MULTI), „alle ausblenden“ /
  „alle einblenden“ (neben „alle umkehren“). TRX: Serien „|MULTI| NETFLIX/APPLE+/DISNEY+ …“ (2125); bei Filmen
  heißen dieselben Gruppen ohne Kürzel („NETFLIX MOVIES“, „DISNEY+ KIDS“, Titel „NF - …“) → `countryOf` wertet
  Gruppen, die mit (4K) NETFLIX/DISNEY+/APPLE+ beginnen, als MULTI (Filme 2992). Ob jede wirklich Deutsch hat:
  nur per Anspielen (Tonspuren) prüfbar. Über den Gruppen
  „＋ Alle sichtbaren übernehmen“ (`addAllVisible`): alle links sichtbaren Gruppen des Reiters auf einmal, jede als
  eigene Gruppe (nicht in die Ziel-Gruppe), nur Zugang der Playlist, Vorhandenes übersprungen (Test: 178 Gruppen/
  13 294 Sender DE+UK+US in Sekunden).
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
  **Ab 15.10.** (SetupReceiver-Extras ab App 73): fragt den **Gerätenamen** (`settings put global device_name`,
  danach App neu starten), **Liste per Nummer** (aus editor/data/state.json + settings: `login/gistId/slug`, `l` =
  Link einfügen), keine Ansicht-Frage mehr (immer senioren), **Zeitplan aus/an, Schlaf-Timer, eigene Listen,
  Lautstärke** (Extras schedOff/schedOn/sleepH/ownlists/normvol), am Ende **Bericht** (`--es report 1` → JSON:
  Version, Erlaubnisse, Wächter, Einstellungen, Handy-Link; wartet bis 30 s auf den Link) und öffnet
  `editor/data/adb/handy-qr.html` mit dem QR-Code der Handy-Fernbedienung. Kein Downloader nötig (APK per adb).
- **Assistent „📺 Neuen Stick einrichten“ im Editor** (Menü Geräte ▾, öffnet `editor/stick.html`; Programmteil
  `editor/stick_setup.py`, nur Standardbibliothek; Server-Route `/api/stick/<schritt>`, sonst nichts am Editor geändert):
  8 Schritte – Vorbereitung am TV (Ersteinrichtung, ADB-Debugging, IP) · Verbinden (IP oder „🔍 Im WLAN suchen“ =
  Port 5555 im /24, wartet auf „Immer zulassen“) · Gerätename · App installieren/aktualisieren (app.apk von
  GitHub Pages) · Erlaubnisse (appops + WRITE_SECURE_SETTINGS) · Wächter Senioren/selbst bedienen · Liste + Zeitplan/
  Schlaf-Timer/eigene Listen/Lautstärke (SetupReceiver, App startet) · Bericht mit QR der Handy-Fernbedienung,
  „Verbindung trennen“ (Fire OS: nur eine ADB-Verbindung). Gleiche Schritte wie `Stick-einrichten.command` (bleibt).
  Getestet 15.10.: Suche, Listen, Fehlerfälle echt; Durchklicken mit gespielten Antworten; am echten Stick offen.
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

## Stand / nächste Schritte (15.10.2026)
- **15.10. nachmittags**: Neuer Stick **„ERO“** (4K Max, Thomas, am Monitor) per Assistent eingerichtet, App 73,
  vorläufig Liste ASW (teilt Anitas Verbindung!), eigene Listen erlaubt – OK-lang-Menü funktioniert. Thomas legt im
  Editor eine neue Xtream-Liste für ERO an. Lokal: Befehl „forget“ (→ App 74), IPTV-Automatik (einmal einrichten).
- **15.10. Mittag**: **App 72 online, alles gepusht** (inkl. Admin-Auswahlliste, Handy-Code je Stick, Einstellungen
  übertragen, Erlaubnisse über den Wächter). Sticks noch auf altem Stand: Anita 65 (seit 10.10. offline!), Küche 69,
  Wohnzimmer 69 (Fire OS 6: Update braucht Klick vor Ort), Max 70, Schlafzimmer 62 (ohne Wächter, Autostart-Erlaubnis
  fehlt). Erlaubnisse: Anita/Küche/Max alles ✓; Wohnzimmer Overlay+Install ✓, Wächter unmöglich; Schlafzimmer
  Install ✓, Overlay ✗, Wächter aus (fernschaltbar) → nach App 72: Wächter an, dann 🔧 Erlaubnisse setzen.
- **15.10. ~11 Uhr**: Thomas hat gepusht → **App 71** (EPG-Ausbau, Admin: Wer schaut/Links/Editor aus der Ferne/
  Zeitpläne, eigene Listen A+B, Stick nur Senioren, Mini-Erklärung). Editor **VERSION 30** (ab Assistent; läuft noch 29 bis Neustart) (10:52 neu
  gestartet, Listen-Schlüssel angelegt). Lokal, noch nicht gepusht → **App 72**: Admin-Auswahlliste, Handy-Code je
  Stick, Einstellungen übertragen, Erlaubnisse über den Wächter. Offen: „Veröffentlichen → Alle“ (KMH-Programm
  noch vom 8.10.!). Ungetestet am echten Stick: Inhaltstext in der Info, Zeitplan, OK-lang-Menü; VLC auf iPhone.
- **15.10. mittags**: Online **App 70** (Webansicht: Mac-Player-Seite mit IINA, iPhone-Kategorien-Menü, App-Link
  neben der Uhr, VLC wählbar). Token im Editor hat jetzt Scopes gist+repo+workflow → „Jetzt pushen“ im Editor geht.
  Lokal committet, **noch nicht gepusht**: EPG-Ausbau (Editor 27, App-Info mit Inhaltstext) → wird App 71.
  → Thomas: Editor neu starten, „Veröffentlichen → Alle“ (KMH-Programm war seit 10.10. veraltet!), pushen.
- Zugänge je Playlist geprüft: ASW→ASW, KMH→KMH, TKH→TKH.
- **Geräte** (Schnappschuss 15.10. aus Firebase `<kanal>/status` – aktueller Stand immer dort bzw. Editor → Fernwartung;
  ein Stick im Standby meldet nichts, ein paar Stunden Stille sind normal):
  | Stick | Modell / Fire OS | IP | Liste | App | Wächter | Bemerkung |
  |---|---|---|---|---|---|---|
  | Wohnzimmer fire TV | AFTMM 4K 2018, Fire OS 6 | .112 | tkh | 69 | geht nicht | Updates fragen nach |
  | Thomas' FireTVStick (Küche) | AFTSSS 2020, Fire OS 7.7 | .110 | tkh | 69 | an | Autostart/Home aus |
  | Thomas' 2. FireTVStick (Schlafzimmer) | AFTSSS, Fire OS 7 | .47 | tkh | **62** | aus | „Über anderen Apps“ fehlt |
  | Max' Fire TV Stick (KMH, „Garten“) | Fire OS 8 | .111 | kmh | 69 | an | Autostart an |
  | Anitas Fire TV Stick (ASW, 130 km) | AFTMA08C15 4K Max, Fire OS 8 | .116 | asw | **65** | an | Autostart/Home an |
  IPs 192.168.6.x (Anita in ihrem eigenen WLAN). Status meldet bei allen `view` = senioren – die Notiz vom 8.10. sagte für
  die Sticks zu Hause „Komplett“ → prüfen. SetupReceiver kann `autostart`/`home`/`waechter` = 0/1.
- **Offen**:
  - Anita meldet sich seit 10.10. 15:27 nicht mehr (zuletzt beim Abspielen, WLAN -66 dBm) → vor Ort Strom/WLAN prüfen lassen.
  - Schlafzimmer: per Fernwartung „Update“ auf 69; Erlaubnis „Über anderen Apps einblenden“ (Knopf in ☰ 3 s).
  - Noch zu bestätigen: „Fernseher aus“ am echten TV; kein „Bild – schwarz – Bild“ beim Umschalten.
  - Samsung: Smarters zeigt aus M3U keine Serien/Filme → Fire-TV-Stick oder Smarters per Xtream-Login.
  - iPad mit Outplayer noch ungetestet.
- Auto-Update ohne Klick bestätigt (Küche, mit Wächter); Fire OS 6 fragt immer nach.
- Jeder Push = neue App-Version (auch bei reinen Editor-/Notiz-Änderungen) → Änderungen gesammelt pushen.

## Offene Aufgaben (Reihenfolge nach Thomas, Stand 15.10.)
- **15.10. abends – TKH zu groß**: Thomas hat per „Alle sichtbaren übernehmen“ (nur Deutsch + MULTI) Filme und
  Serien in TKH übernommen → Test-Veröffentlichen: 52 MB M3U, 160 275 Einträge (Serien 46,8 MB = 141 423 Folgen
  aus 5 194 Serien; |DE| SERIEN allein 15,7 MB). Zu groß für Gist-Upload und Sticks. Entscheidung offen:
  Serien eingrenzen oder Folgen erst beim Öffnen einer Serie nachladen (Umbau). Fehler beim Veröffentlichen
  („'list' object has no attribute 'get'“, Folgen bei manchen Serien als Liste von Listen) ist behoben.
  Thomas' Veröffentlichen um 16:00 schrieb lokal 50 MB, der Gist-Upload scheiterte (Gist blieb 0,2 MB) – Sticks ok.
  **Umbau „Folgen nachladen“ gebaut (Thomas' Wahl, App 79)**: siehe Abschnitt „Große Listen“. **Reihenfolge**: pushen →
  TKH-Sticks auf App ≥ 79 bringen (nachts/„Alle aktualisieren“) → erst dann TKH veröffentlichen (6,75 MB + 5 Folgen-
  Dateien à ~1 MB). Idee danach: Filme genauso erst je Kategorie laden (Filme = 4,7 der 6,75 MB, Baum fürs Gerät 5,3 MB).
Thomas' vier Bereiche: 1 App (Stick) · 2 Webansicht iPhone/iPad/Mac (**erledigt**, App 70) · 3 Admin-Webapp ·
4 EPG (**gebaut**: mehr Sender + Inhaltstexte; automatischer GitHub-Auftrag alle 6 h vorerst NICHT gewünscht).
- **Mini-Erklärung** in der Webapp (iPhone/iPad/Mac) – **umgesetzt**: „?“ neben der Uhr (`#help`, `showHelp`), beim
  ersten Besuch einmal von selbst (`iptv-help-seen`); Text passend zu iOS (Outplayer/VLC) bzw. Mac (IINA), ＋-Hinweis
  nur mit Freigabe.
- **Eigene Listen** (z. B. „⚽ Fussball“) – Entscheidung Thomas 15.10.: **Stick-App nur noch Seniorenansicht**
  (**umgesetzt**: `mode.js` leitet in der App (UA IPTVApp) immer auf senioren.html; Einrichtung am Stick nur noch
  „Übernehmen“, `applySetup` erzwingt senioren; Admin ⚙️ und Editor-Fernwartung ohne Komplett/Senioren-Wahl;
  Editor-Fernwartung hat dafür den Schalter „Eigene Listen bearbeiten“ (setting ownlists)).
  Editor-Fenster „Fernwartung“ und „Geräte-Links“: jeder Stick bzw. jede Karte auf-/zuklappbar, Start immer zu
  (`devOpen`, `foldCard`/`cardOpen`; Kopfzeile zeigt das Wichtigste, z. B. was läuft bzw. ⚠️ Hinweise). **Freigabe je Gerät** im Editor/Admin (⚙️ „Darf eigene
  Listen bearbeiten“, Standard aus – Schwiegermutter nein, Jüngere ja). Listen **zentral in Firebase** unter eigenem
  geheimen Schlüssel je Playlist, sofort auf allen Geräten der Playlist (oben in der Übersicht); Stick mit Freigabe:
  OK lang → „Zu Liste hinzufügen / ＋ Neue Liste“. Webapp zeigt sie als Kategorien, Bearbeiten nur wenn für die
  Playlist freigegeben. Admin-Webapp bearbeitet alle Listen.
  **Stufe A umgesetzt (Editor 29)**: je Playlist `pl.myListsKey` (32 Zeichen, `ensure_mylists_keys` beim
  Veröffentlichen/`fb_sync_lists`; `/api/state` behält ihn, falls die Oberfläche einen Stand ohne Schlüssel schickt).
  M3U-Kopf `x-mylists="https://<db>/<schlüssel>"` (+ `x-mylists-edit="1"` bei `pl.myListsWeb`), `<kanal>/lists/<slug>.my`.
  Daten `<schlüssel>.json` = `{l: {<id>: {n, o, i: [norm…]}}}` (norm = Adresse ohne Server/Zugangsdaten). Webapp
  (`m3u.js`: `readMyHeader`, EventSource live, Cache `iptv-mylists`, `myLists/myListNodes/myToggle/myCreate`):
  Kategorien links (Live-Sender), Baum der App ganz oben (senioren.js/app.js `first`). Admin „📋 Eigene Listen“
  (`renderMyl`): anlegen, umbenennen, ▲▼, löschen, Sender per Suche hinzufügen/✕. Getestet 15.10. (Test-Schlüssel,
  danach gelöscht). **Stufe B umgesetzt** (App 71): Freigabe je Stick per Fernwartung `setting ownlists`
  (Prefs `ownLists`, Status `cfg.ownlists`, Admin ⚙️ „Darf eigene Listen bearbeiten“, Standard aus). Mit Freigabe
  öffnet OK lang auf einem Sender in der ☰-Liste `MyLists.menu` (★ Lieblingssender · ☐/☑ Listen · ＋ Neue Liste mit
  Tastatur), schreibt per PUT nach Firebase; Adresse kommt von der Webapp (`IPTVNative.setMyLists`). Ohne Freigabe
  OK lang wie bisher (★). Webapp: „＋“ an jedem Live-Sender nur mit `x-mylists-edit` (Editor-Menü der Playlist
  „📋 Eigene Listen in der Webapp bearbeiten“, `pl.myListsWeb`). Webapp getestet 15.10.; Stick-Menü ungetestet.
- **Admin-Webapp** (Thomas 15.10., alle vier gewünscht; alle gebaut, (4) braucht App 71): (1) Links senden – Webapp-Link je Playlist per
  Teilen (WhatsApp/iMessage), QR, Kurzanleitung; (2) Wer schaut womit – Belegung je Zugang, Warnung bei
  Doppelbelegung; (3) Editor aus der Ferne – Veröffentlichen, Sender prüfen **und Pushen** vom Handy (Editor holt
  Aufträge über Firebase ab, nur solange er auf einem Mac läuft); (4) Zeitpläne je Stick (aus/an, Schlaf-Timer).
- ProSieben (TKH) am 15.10. gestört (HD schwarz ohne Ton, FHD nur Ton) → Editor „Sender prüfen“ → Ersetzen.
- Ungetestet: VLC auf iPhone/iPad (Rücksprung per x-success, evtl. neuer Safari-Tab), App 71 Inhaltstext am Stick.

## Arbeitsweise über mehrere Macs (MacBook Neo, Büro-Mac, …)
- **Feste Regel (Thomas 15.10.): Nach jedem fertigen Schritt committen UND diese CLAUDE.md aktualisieren**
  (Abschnitt „Stand / nächste Schritte“ + „Offene Aufgaben“), damit Thomas mit der SSD an jedem Mac sofort
  weitermachen kann – auch in einer neuen Claude-Sitzung ohne Vorwissen. Claudes eigene Notizen (memory) liegen
  auf dem jeweiligen Mac, nicht auf der SSD → alles Wichtige gehört hierher. Nichts Fertiges uncommittet lassen.
- **Die SSD ist die einzige Arbeitskopie.** Projekt, `.git`, `editor/data/` liegen darauf; GitHub dient nur zum
  Veröffentlichen (Pages + App-Bau), nicht zum Abgleich zwischen Macs. Kein Pull nötig, solange niemand auf github.com ändert.
- Neuer Mac: `Einrichten-neuer-Mac.command` (Command Line Tools, safe.directory, GitHub Desktop, VLC-Check).
- Git-Identität steht im Repo (`.git/config`): „Max Mustermann <mieten.riffe3y@icloud.com>“ – nicht ändern.
- **Claude committet lokal** (mit Co-Authored-By), **Thomas pusht** (bestätigt 15.10.: immer selbst, über den
  Editor bzw. künftig die Admin-Webapp; Claude erinnert am Ende eines Arbeitspakets) – früher auch GitHub Desktop
  („Push origin“) – oder im Editor (App-Anzeige ▾ → „Jetzt pushen“, `/api/git-push`: Token aus settings.json, braucht Scopes **repo + workflow**;
  pusht `HEAD:main` per https mit Token, setzt danach `refs/remotes/origin/main`; Fehlertext ohne Token).
  „Pushen und danach alle Geräte aktualisieren“ wartet auf den Bau (`update_after_build`) und schickt `update` an alle.
  Claude hat keine GitHub-Zugangsdaten auf der Kommandozeile.
- Nach „Veröffentlichen“ schickt der Editor `refresh {rev}` an `list:<slug>`: Geräte laden die Gist-Revision
  (`…/raw/<rev>/datei`, sofort, ohne 5-min-Cache; `IPTV.refreshTo`, 20 min gültig) im Hintergrund ohne Unterbrechung.
- Vor dem Abziehen der SSD: Terminal-Fenster von Editor/Webapp und die Claude-Sitzung schließen.

## IPTV-Automatik (macOS-Hintergrunddienst, ab 15.10.)
- `Automatik-einrichten.command` (einmal je Mac, an/aus/testen): legt `~/Library/LaunchAgents/de.iptv.automatik.plist`
  (StartInterval 3600, RunAtLoad, niedrige Priorität) und `~/Library/Application Support/IPTV/automatik.py` an (prüft,
  ob die SSD da ist; Pfad beim Einrichten eingetragen). Protokoll `~/Library/Logs/iptv-automatik.log`.
  **macOS-Rechte**: Hintergrunddienste dürfen ohne Erlaubnis nicht auf externe Laufwerke („Operation not permitted“,
  ein Fenster erscheint nicht). Darum startet der Dienst direkt `…/Python3.framework/Versions/3.9/Resources/Python.app`
  (`sys.base_prefix`), und Thomas schaltet für „Python“ einmal **Festplattenvollzugriff** ein (Skript öffnet die
  Einstellung + Finder). Erste Fassung über /bin/bash (15.10.) scheiterte daran; das Skript erkennt und erneuert sie.
- `editor/auto_publish.py [--force] [--dry-run]`: Programm (ältestes `generated` der `<liste>.epg.json`) älter als
  6 h → alle Listen veröffentlichen. Läuft der Editor (8790): Auftrag per `/api/publish` (beschäftigt → nächstes Mal),
  sonst selbst `server.publish([])` mit Sperre `editor/data/auto_publish.lock`; schreibt `autoResult`.
  Kurzlebig → blockiert das Auswerfen der SSD nicht. macOS fragt evtl. einmal nach Zugriff auf Wechseldatenträger.
  Getestet 15.10.: Probelauf echte Daten, Veröffentlichen in Test-Kopie, Übergabe an (Schein-)Editor frei/beschäftigt.

## Große Listen: Serienfolgen erst beim Öffnen (ab 15.10.; App 79)
- Editor (`build_playlist`): ab `SERIES_LAZY_MIN` = 3000 Folgen je Playlist eine M3U-Zeile je Serie(-Fassung):
  `tvg-type="series" … x-series="<id>" x-eps="<n>" x-cat … group-title="<Serie>"`, URL-Platzhalter
  `http://series.invalid/<id>`; Kopf `x-series-chunks="N"`. Folgen in `<slug>.s<k>.json` (k = id % N, je ~1,5 MB):
  `{b: ".../series/USER/PASS/", s: {id: [[staffel, folge, titel, folgen_id, endung], …]}}` – im Gist, OUT und lokal;
  nicht mehr gebrauchte Teile werden gelöscht. Kleine Listen (ASW 242, KMH 0 Folgen) bleiben im alten Format.
- Webapp (`m3u.js`): `e.lazy/e.sid/e.eps`, `seriesEpisodes`/`expandSeries` (Folgen-Einträge wie früher, Name
  „Serie S01E02 – Titel“), `nativeSeries` für die App. `app.js`: Kacheln zählen `x-eps`, Detail/TV-Gruppe laden beim
  Öffnen, Suchtreffer öffnen die Serie, „Zuletzt gesehen“ merkt `s` und lädt beim Start nach (`addEpisodes`).
- `tree.js`: Serie mit `ls` (Nummern), `ll` (Sprachen), `ne` (Folgen), `c: []`; `IPTV.seriesChildren` baut die Ebene.
- App: `Node.lazyIds/lazyEps`, Öffnen → `PlayerActivity.requestSeries` → `MainActivity.runJs(IPTV.nativeSeries)` →
  Bridge `setSeriesChildren` → einhängen, Liste neu. `Memory.seriesOf` (Folge → Serien-Nummern) für „Zuletzt
  gesehen“; `adopt` übernimmt neue Bäume auch während einer Folge; Handy-„play“ mit `s` lädt die Serie vorher.
  Admin-Seite lädt Folgen beim Aufklappen (`loadSeries`) und schickt `play {norm, s}`. Fire OS 6: kein String.join.
- Getestet 15.10.: TKH 52 → 6,75 MB, Kacheln/Detail/`nativeSeries` im Browser; App übersetzt; am Stick ungetestet.

## Warnungen aufs iPhone (Web-Push, ab 15.10.; Editor 31) – **getestet 15.10.: Banner + rote 1 kommen an**
- Thomas' iPhone (iOS 18.7) ist angemeldet. Automatik läuft auf dem MacBook mit Festplattenvollzugriff für Python
  (seit 15.10. 14:52). Offene Warnung: Anita offline (seit 10.10.).
- Nur die **Admin-Fernbedienung** (Haupt-Link) als Home-Bildschirm-App (iOS 16.4+): Karte „🔔 Benachrichtigungen“
  → Einschalten (Service Worker `sw-admin.js`, Scope `./fernbedienung`, kein fetch-Handler; Manifest
  `fernbedienung.webmanifest`; beides in deploy.yml mitkopiert). Anmeldung `<kanal>/push/subs/<id>` = {endpoint}.
- Mac sendet (`editor/webpush.py`): VAPID-Schlüssel `editor/data/vapid.pem` per `/usr/bin/openssl` (P-256, ES256,
  geprüft „Verified OK“), öffentlicher Schlüssel `<kanal>/push/vapid` (Editor legt ihn beim Start an). Push OHNE
  Inhalt; der Service Worker holt `<kanal>/alerts` (last, open, n) → Banner + rote Zahl (`setAppBadge`). 404/410 →
  Anmeldung gelöscht. Test: Admin → „Test-Nachricht“ → Editor-Auftrag `pushtest`.
- `editor/alerts.py` (stündlich aus `auto_publish.py`): Stick > 24 h ohne Meldung, Programm > 24 h, Abo ≤ 14 Tage
  (wöchentlich), App > 48 h alt; je Ereignis einmal, Erledigtes mit „✓“; Merker `editor/data/alerts.json`.
  `night_updates`: 3–5 Uhr Update an wache Sticks mit alter App, auf denen nicht geschaut wird (je Nacht einmal).
  ntfy wird dafür NICHT benutzt (Thomas: nur Firebase/Admin-App). Hilfe-Knopf für Senioren: nicht gewünscht.
- Admin-Hinweise oben (`#warn`): immer **zugeklappt** starten („⚠️ n Hinweise ▸“, `warnOpen`, nicht gemerkt).
- Admin „⬆️ Alle aktualisieren“ (bei Warnungen): fragt bei laufender Sendung „alle“ oder „nur nicht schauende“.
- App 75: „Fernseher aus“ (tvoff) gilt mit Wächter als ruhiger Moment für ein Update (nach 60 s).

## Testen lokal
`Start-Webapp.command` (Port 8765, auch im WLAN), `Start-Editor.command` (Port 8790, beendet alten Editor vorher).
