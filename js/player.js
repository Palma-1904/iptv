// Eingebauter Player für Browser (Fire TV Silk, Computer).
// .m3u8 -> nativ oder hls.js, .ts/Xtream-Live -> mpegts.js, Videodateien -> direkt.
(function () {
  'use strict';

  var HLS_JS = 'https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.20/hls.min.js';
  var MPEGTS_JS = 'https://cdn.jsdelivr.net/npm/mpegts.js@1.8.0/dist/mpegts.min.js';

  var params = new URLSearchParams(location.search);
  var url = params.get('url') || '';
  var name = params.get('name') || '';

  var video = document.getElementById('video');
  var overlay = document.getElementById('overlay');
  var message = document.getElementById('message');
  var back = document.getElementById('back');
  var external = document.getElementById('external');

  document.title = name || 'Wiedergabe';
  document.getElementById('title').textContent = name;

  // EPG: "Jetzt … · Danach …", jede Minute aktualisiert.
  var tvgId = params.get('id') || '';
  var epgLine = document.getElementById('epg');
  function showEpg() {
    var nn = IPTV.nowNext(tvgId);
    epgLine.hidden = !nn;
    if (!nn) return;
    epgLine.textContent = 'Jetzt: ' + nn.now[2] + ' (bis ' + IPTV.hhmm(nn.now[1]) + ')' +
      (nn.next ? '  ·  Danach: ' + IPTV.hhmm(nn.next[0]) + ' ' + nn.next[2] : '');
  }
  if (tvgId) {
    IPTV.loadEpg().then(showEpg);
    setInterval(showEpg, 60000);
  }

  function goBack() {
    if (history.length > 1) history.back();
    else location.href = 'index.html';
  }
  back.addEventListener('click', goBack);

  // Auf Android-Geräten (Fire TV) zusätzlich Übergabe an VLC anbieten.
  if (/Android/i.test(navigator.userAgent) && /^https?:\/\//i.test(url)) {
    external.href = IPTV.intentUrl(url);
    external.hidden = false;
  }

  // Mac: Browser sperren http-Streams auf der https-Seite und können .mkv/AC3 nicht –
  // daher Übergabe an IINA (Mac-Player, iina.io) und „Adresse kopieren“ (für VLC: Ablage → Netzwerk öffnen).
  var isMac = /Macintosh/.test(navigator.userAgent) && !(navigator.maxTouchPoints > 1);
  var macIina = document.getElementById('mac-iina');
  var copy = document.getElementById('copy');
  function showMacButtons() {
    if (!isMac || !/^https?:\/\//i.test(url)) return;
    macIina.href = 'iina://weblink?url=' + encodeURIComponent(url);
    macIina.hidden = copy.hidden = false;
  }
  copy.addEventListener('click', function () {
    navigator.clipboard.writeText(url).then(function () {
      showOverlay('Adresse kopiert – in VLC: Ablage → Netzwerk öffnen → einfügen.', true);
    });
  });

  var hideTimer;
  function showOverlay(text, sticky) {
    if (text) message.textContent = text;
    overlay.classList.remove('hidden');
    clearTimeout(hideTimer);
    if (!sticky) hideTimer = setTimeout(function () { overlay.classList.add('hidden'); }, 4000);
  }

  var mixed = false;

  function fail(text) {
    if (isMac) {
      showMacButtons();
      text = (mixed ? 'Der Browser darf diesen Sender auf dieser Seite nicht abspielen (unverschlüsselte Adresse). '
        : text + ' ') + 'Bitte „In IINA öffnen“ wählen (kostenlos: iina.io). Für VLC: „Adresse kopieren“, '
        + 'dann in VLC Ablage → Netzwerk öffnen → einfügen.';
      showOverlay(text, true);
      return;
    }
    if (mixed) {
      text = 'Dieser Sender nutzt eine unverschlüsselte Adresse (http), die der Browser auf dieser Seite sperrt. ' +
        (external.hidden ? 'Bitte auf iPad/iPhone (Outplayer), Fire TV (App) oder am Mac im Editor ansehen.' : 'Bitte „In VLC öffnen“ wählen.');
    }
    showOverlay(text, true);
    TV.focusFirst(external.hidden ? '#back' : '#external');
  }

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var s = document.createElement('script');
      s.src = src;
      s.onload = resolve;
      s.onerror = function () { reject(new Error('Player-Bibliothek konnte nicht geladen werden.')); };
      document.head.appendChild(s);
    });
  }

  function playing() {
    showOverlay('Läuft', false);
    video.focus();
    // Ton ohne Bild: typisch für HEVC (H.265), das Browser in diesem Format nicht anzeigen
    setTimeout(function () {
      if (!video.paused && video.videoWidth === 0) {
        fail('Kein Bild: Dieser Sender nutzt vermutlich HEVC (H.265), das der Browser nicht anzeigen kann. ' +
          'Bitte die HD-Fassung des Senders wählen' + (external.hidden ? '.' : ' oder „In VLC öffnen“.'));
      }
    }, 4000);
  }

  function start() {
    if (!/^https?:\/\//i.test(url)) return fail('Ungültige Stream-Adresse.');

    // http-Stream auf https-Seite: manche Browser sperren das (Mixed Content) – trotzdem versuchen,
    // bei Fehler erklärt fail() den Grund.
    mixed = location.protocol === 'https:' && /^http:/i.test(url);
    // Mac: Safari und Chrome sperren das sicher – gleich die Player-Knöpfe zeigen
    if (mixed && isMac) return fail('');

    var path = url.split('?')[0].toLowerCase();
    var p;
    if (/\.m3u8$/.test(path)) p = playHls();
    else if (/\.(mp4|m4v|webm|mov|mkv)$/.test(path)) p = playDirect();
    else p = playTs(); // .ts oder Xtream-Live ohne Endung
    p.catch(function (err) { fail(err.message); });
  }

  function playDirect() {
    video.src = url;
    return video.play().catch(function () { /* Autoplay evtl. blockiert – Steuerung ist sichtbar */ });
  }

  function playHls() {
    if (video.canPlayType('application/vnd.apple.mpegurl')) return playDirect();
    return loadScript(HLS_JS).then(function () {
      if (!window.Hls || !Hls.isSupported()) throw new Error('HLS wird von diesem Browser nicht unterstützt.');
      var hls = new Hls();
      hls.on(Hls.Events.ERROR, function (e, data) {
        if (data.fatal) fail('Stream konnte nicht abgespielt werden (' + data.details + ').');
      });
      hls.loadSource(url);
      hls.attachMedia(video);
      return video.play().catch(function () {});
    });
  }

  function playTs() {
    return loadScript(MPEGTS_JS).then(function () {
      if (!window.mpegts || !mpegts.isSupported()) throw new Error('Dieser Stream-Typ wird vom Browser nicht unterstützt.');
      var player = mpegts.createPlayer({ type: 'mpegts', isLive: true, url: url });
      player.on(mpegts.Events.ERROR, function (type, detail) {
        fail('Stream konnte nicht abgespielt werden (' + detail + ').');
      });
      player.attachMediaElement(video);
      player.load();
      return player.play();
    });
  }

  video.addEventListener('playing', playing);
  video.addEventListener('error', function () {
    if (video.src) fail('Stream konnte nicht abgespielt werden.');
  });

  // Fernbedienung: OK = Pause/Weiter, Zurück-Taste = zurück zur Liste (Silk macht das selbst).
  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape' || ev.key === 'Backspace') { ev.preventDefault(); goBack(); return; }
    if (document.activeElement === video && (ev.key === 'Enter' || ev.key === ' ')) {
      ev.preventDefault();
      if (video.paused) video.play(); else video.pause();
    }
    if (overlay.classList.contains('hidden')) showOverlay(null, false);
  });

  start();
})();
