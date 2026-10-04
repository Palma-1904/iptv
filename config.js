// Zentrale Konfiguration – wird von index.html und senioren.html geladen.
window.IPTV_CONFIG = {
  // Quelle der Playlist. Relativ ("liste.m3u") = Datei aus diesem Repo.
  // Alternativ eine GitHub-Raw-URL, z. B.:
  // "https://raw.githubusercontent.com/USER/REPO/main/liste.m3u"
  m3uUrl: "liste.m3u",

  // Link-Vorlage für den Player, je nach Gerät (wird automatisch erkannt).
  // Platzhalter: {url} roh, {urlEncoded}, {nameEncoded}, {idEncoded} = tvg-id (für EPG im Player),
  // {intent} = Android-Intent (siehe androidPackage)
  player: {
    // iPad/iPhone. Browser schreiben das intern zu "outplayer://https//…" um. Falls Outplayer
    // damit nicht startet: "outplayer://x-callback-url/play?url={urlEncoded}"
    ios: "outplayer://{url}",
    // Fire TV im Silk-Browser: eingebauter Player (Knopf "In VLC öffnen" inklusive)
    tv: "player.html?url={urlEncoded}&name={nameEncoded}&id={idEncoded}",
    // Eigene Fire-TV-App: direkt an VLC übergeben
    app: "{intent}",
    // Android-Handy/Tablet
    android: "{intent}",
    // Mac: direkt in IINA (kostenlos, iina.io – muss installiert sein). Browser spielen die
    // http-Streams der https-Seite nicht und können .mkv/AC3/MP2 nicht; VLC nimmt vlc://-Links
    // zwar an, spielt sie aber nicht ab.
    mac: "iina://weblink?url={urlEncoded}",
    // Computer
    web: "player.html?url={urlEncoded}&name={nameEncoded}&id={idEncoded}"
  },

  // Android-Player für {intent}. Leer lassen ("") = Android fragt, welcher Player.
  androidPackage: "org.videolan.vlc",

  // Versteckter Umschalter: 5x schnell auf die Uhr oben tippen, dann Code eingeben.
  // Das Gerät merkt sich die gewählte Ansicht. Kein Schutz – der Code steht hier im Klartext.
  codes: {
    main: "1904",   // Hauptansicht (komplett)
    senior: "04"    // Seniorenansicht
  },

  // Seniorenversion: Reihenfolge = Anzeigereihenfolge.
  // Ein Eintrag ist entweder ein Sendername oder { label, match }:
  //   label = angezeigter Name, match = Name/tvg-id/tvg-name in der M3U.
  // Groß-/Kleinschreibung, "HD", "FHD", "DE:"-Präfixe usw. werden ignoriert.
  senioren: [
    "Das Erste",
    "ZDF",
    "NDR",
    "WDR",
    "BR",
    "SWR",
    "MDR",
    "hr",
    "rbb",
    "3sat",
    "arte",
    "phoenix",
    { label: "Tagesschau 24", match: "tagesschau24" }
  ]
};
