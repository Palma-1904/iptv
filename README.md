# IPTV-Webapp

Statische Web-App für iPad/iPhone (Safari), die Streams aus einer M3U-Playlist in **Outplayer** öffnet.

- `index.html` – Hauptversion: Tabs Live TV / Filme / Serien, Suche über alles, aufklappbare Gruppen
- `senioren.html` – Seniorenversion: große Kacheln mit laufender Sendung, keine Suche
- `player.html` – eingebauter Player für Browser ohne externen Player (Fire TV Silk, Computer)
- `liste.m3u` – gemeinsame Playlist für beide Versionen
- `config.js` – Standard-Playlist, Player-Link-Vorlagen, Senderauswahl für Senioren
- `editor/` – Playlist-Editor für den Mac (wird nicht veröffentlicht)

## Einrichtung

1. Repo auf GitHub anlegen und diesen Ordner auf `main` pushen.
2. **Settings → Pages → Source: GitHub Actions** auswählen.
3. Jeder Push auf `main` deployt automatisch (`.github/workflows/deploy.yml`).
4. Auf dem iPad die Seite in Safari öffnen → Teilen → „Zum Home-Bildschirm“.

## Konfiguration (`config.js`)

| Feld | Bedeutung |
|---|---|
| `m3uUrl` | `liste.m3u` (Datei im Repo) oder eine GitHub-Raw-URL |
| `player` | Link-Vorlage je Gerät (`ios`, `tv`, `app`, `android`, `web`) |
| `androidPackage` | Android-Player für Fire TV/Android, Standard VLC (`org.videolan.vlc`) |
| `senioren` | Senderliste für die Seniorenversion, in Anzeigereihenfolge |

Die Playlist wird bei jedem Start geladen und zusätzlich beim Zurückkehren in die App
(frühestens alle 5 Minuten) auf Änderungen geprüft. Nutzer müssen nichts tun.

## Ansicht umschalten (versteckt)

Oben steht die Uhrzeit. 5× schnell auf die Uhr tippen (Fire TV: Uhr auswählen, 5× OK)
öffnet ein Code-Feld:

- `1904` → Hauptansicht (komplett)
- `04` → Seniorenansicht

Das Gerät merkt sich die Wahl. Codes in `config.js` unter `codes` – kein echter Schutz,
nur gegen versehentliches Umschalten. Die Einrichtungs-Links aus dem Editor setzen die
Ansicht gleich mit (`&ansicht=komplett` / `&ansicht=senioren`).

## Liste auf ein Gerät bringen

Im Editor „Veröffentlichen“ – danach gibt es je Playlist Links für „Komplett“ und „Senioren“.
Den passenden Link **einmal** auf dem Gerät öffnen; das Gerät merkt sich Liste und Ansicht.

| Phase | Link | Voraussetzung |
|---|---|---|
| Jetzt testen, im WLAN | `http://<Mac-IP>:8765/#m3u=lokal/<name>.m3u` | `Start-Webapp` läuft auf dem Mac |
| Dauerbetrieb, überall | `https://<benutzer>.github.io/iptv/index.html#liste=…` | GitHub Pages + Token im Editor |

Auf dem Fire TV den Link im Silk-Browser eingeben (einmalig), danach als Lesezeichen speichern.

## Geräte

| Gerät | Erkennung | Wiedergabe |
|---|---|---|
| iPad/iPhone | automatisch | Outplayer |
| Fire TV (Silk-Browser) | automatisch, TV-Modus | eingebauter Player (`player.html`), Knopf „In VLC öffnen“ |
| Android | automatisch | VLC per Intent |
| Computer | – | eingebauter Player |

**TV-Modus:** Bedienung per Pfeiltasten und OK, deutliche Fokus-Markierung, nach „Zurück“
steht die Auswahl wieder auf dem zuletzt gesehenen Sender. Zum Testen am Computer:
`index.html?tv=1` (bleibt gespeichert, `?tv=0` schaltet zurück).

**Grenzen des Browser-Players:** Auf der https-Seite können keine `http://`-Streams
abgespielt werden (Browser-Sperre). Streams über hls.js/mpegts.js brauchen außerdem
CORS-Freigabe vom Stream-Server. In diesen Fällen hilft „In VLC öffnen“ bzw. die Fire-TV-App.

Zum Testen einer anderen Playlist ohne Änderung: `index.html?m3u=https://…/andere.m3u`

## Erkennung Filme / Serien

Reihenfolge der Prüfung je Eintrag:

1. Attribut `tvg-type="live|movie|series"` in der M3U (hat Vorrang)
2. **Serie**: URL enthält `/series/`, Gruppe enthält „Serie(n)“, „Staffel“, „Season“, „TV Shows“ oder Titel enthält `S01E02`/`1x02`
3. **Film**: URL enthält `/movie/`, Gruppe enthält „Film(e)“, „Movie(s)“, „VOD“, „Kino“ oder Datei endet auf `.mp4`, `.mkv`, …
4. sonst **Live TV**

## Programme starten (Mac)

Im Projektordner per Doppelklick:

| Datei | Startet |
|---|---|
| `Start-Editor.command` | Playlist-Editor |
| `Start-Webapp.command` | Webapp (Hauptansicht) zum Testen – zeigt auch die Adresse für iPad/iPhone/Fire TV im WLAN |
| `Start-Seniorenansicht.command` | Webapp direkt in der Seniorenansicht |

Es öffnet sich ein Terminal-Fenster, das offen bleiben muss, solange das Programm
benutzt wird. Fenster schließen beendet es. Läuft ein Programm schon, öffnet ein
erneuter Doppelklick nur den Browser. Falls macOS beim ersten Mal den Start verweigert:
Rechtsklick auf die Datei → „Öffnen“ → „Öffnen“.

## Playlist-Editor (Mac)

Doppelklick auf `Start-Editor.command` startet den Editor im Browser
(`http://localhost:8790`). Nichts zu installieren, nutzt das vorhandene Python.

1. **Quelle anlegen** (＋): Xtream-Zugang (Server, Benutzer, Passwort) oder M3U-Adresse/-Datei.
   Optional eigene EPG-Adresse (z. B. open-epg.com), falls das EPG des Anbieters schlecht ist.
2. **Länder ausblenden:** Kürzel-Chips links antippen (erkannt aus Gruppennamen wie `DE | …`).
3. **Playlist anlegen** (＋ bei Playlist), z. B. „Familie“, „Oma“.
4. **Sender hinzufügen:** Gruppe links wählen → Sender anhaken → „Zur Playlist →“, oder
   ＋ an einer Gruppe für die ganze Gruppe, oder Sender nach rechts ziehen.
5. **Ordnen:** Einträge und Gruppen rechts ziehen; Doppelklick benennt um; eine Gruppe
   anklicken macht sie zum Ziel für „Zur Playlist →“. Serien werden beim Veröffentlichen
   automatisch in ihre Episoden aufgelöst.
6. **Veröffentlichen:** erzeugt je Playlist `<name>.m3u` + `<name>.epg.json` (nur gewählte
   Sender, 36 Stunden) und lädt sie in einen **Secret Gist** hoch.

**Einmalig in den Einstellungen:** GitHub-Token mit nur der Berechtigung `gist` und die
Adresse der Webapp (GitHub Pages). Danach zeigt „Veröffentlichen“ je Playlist einen
**Einrichtungs-Link** (`…/#liste=BENUTZER/GIST/name`). Diesen Link einmal auf dem Gerät
öffnen – das Gerät merkt sich seine Playlist. Die Adresse steht nirgends öffentlich.

**Anbieter im Blick (Kasten links oben):** Abo-Status, Ablaufdatum, belegte/erlaubte
Verbindungen, Antwortzeit und Qualität. „Qualität testen“ spielt 3 Sender je ca. 5 Sekunden
an (bevorzugt aus deinen Playlists) und misst Startzeit und Datenrate; der Verlauf zeigt,
ob der Anbieter schlechter wird. Läuft gerade ein Stream und ist das Verbindungslimit
erreicht, wird nicht getestet, damit niemand rausfliegt. Meldet der Anbieter eine neue
Server-Adresse, kann sie per Knopf übernommen werden.

**Geräte pro Playlist:** Feld „Geräte“ neben der Playlist. Brauchen alle Playlists einer
Quelle zusammen mehr Geräte als der Anbieter Verbindungen erlaubt, erscheint eine Warnung.
Durchsetzen kann die Webapp das nicht – die Grenze setzt der Anbieter.

**Änderungen beim Anbieter:** „Aktualisieren“ meldet neue und entfernte Einträge. Hat der
Anbieter interne Nummern geändert, werden Playlist-Einträge automatisch über Name und
Gruppe neu zugeordnet.

**Abspielen im Editor:** ▶ an jedem Eintrag öffnet ein kleines Player-Fenster (Kopfzeile
ziehen = verschieben, Ecke unten rechts = Größe, „⧉ Fenster“ = eigenes Fenster,
Vollbild über die Video-Steuerung). Formate, die der Browser nicht kann (z. B. `.mkv`),
öffnet „VLC“. Achtung: Abspielen belegt eine Verbindung beim Anbieter.

**EPG aktuell halten:** Das EPG reicht 36 Stunden. Mindestens einmal täglich
„Veröffentlichen“ klicken, sonst fehlt die Anzeige „Jetzt läuft“.

Alle Editor-Daten (Zugangsdaten, Token, Caches) liegen in `editor/data/` und sind per
`.gitignore` vom Hochladen ausgeschlossen.

## Hinweise

- Ohne Einrichtungs-Link nutzt ein Gerät die Beispielliste `liste.m3u` aus dem Repo – die ist öffentlich, dort keine Zugangsdaten eintragen.
- Seniorenansicht: Mit eigener Playlist werden deren Live-Sender in Playlist-Reihenfolge gezeigt; ohne gilt die Liste `senioren` in `config.js`.
- Logos mit `http://`-Adressen werden auf der `https://`-Seite ggf. nicht angezeigt.
