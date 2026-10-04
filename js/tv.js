// Fernbedienung (Fire TV): Pfeiltasten bewegen den Fokus zum nächsten Element in Pfeilrichtung.
// OK/Enter löst Links, Buttons und Gruppen-Überschriften nativ aus. Nur im TV-Modus aktiv.
(function () {
  'use strict';

  var SELECTOR = 'a[href], button:not([disabled]), summary, input, video[controls]';
  var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };

  function visible(el) {
    // In zugeklappten <details> ist nur deren Überschrift erreichbar. Neuere Browser melden den
    // Inhalt einer einmal geöffneten Gruppe sonst weiter als sichtbar -> Fokus bliebe hängen.
    for (var p = el.parentElement; p; p = p.parentElement) {
      if (p.tagName === 'DETAILS' && !p.open && !(el.tagName === 'SUMMARY' && el.parentElement === p)) return false;
    }
    if (!el.getClientRects().length) return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  // Ist ein Dialog offen (z. B. Code-Eingabe), bleibt der Fokus darin.
  function candidates() {
    var scope = document.querySelector('[aria-modal="true"]:not([hidden])') || document;
    return Array.prototype.filter.call(scope.querySelectorAll(SELECTOR), visible);
  }

  // Lücke zwischen zwei Bereichen auf einer Achse (0, wenn sie sich überlappen).
  function gap(a1, a2, b1, b2) {
    return Math.max(0, b1 - a2, a1 - b2);
  }

  // Abstand in Pfeilrichtung + stark gewichteter seitlicher Versatz -> bleibt in Spalte/Zeile.
  // Bei Überlappung (z. B. breite Gruppen-Überschrift über einem Raster) zählt die linke Kante.
  function score(from, to, dir) {
    var main, side, edge;
    var fx = (from.left + from.right) / 2, fy = (from.top + from.bottom) / 2;
    var tx = (to.left + to.right) / 2, ty = (to.top + to.bottom) / 2;
    if (dir === 'down' || dir === 'up') {
      if (dir === 'down' ? ty <= fy : ty >= fy) return Infinity;
      main = dir === 'down' ? to.top - from.bottom : from.top - to.bottom;
      side = gap(from.left, from.right, to.left, to.right);
      edge = Math.abs(to.left - from.left);
    } else {
      if (dir === 'right' ? tx <= fx : tx >= fx) return Infinity;
      main = dir === 'right' ? to.left - from.right : from.left - to.right;
      side = gap(from.top, from.bottom, to.top, to.bottom);
      edge = Math.abs(to.top - from.top);
    }
    return Math.max(main, 0) + side * 4 + edge * 0.1;
  }

  function focusEl(el) {
    el.focus({ preventScroll: true });
    el.scrollIntoView({ block: 'center', inline: 'nearest' });
  }

  function focusFirst(selector, element) {
    var el = element || (selector ? document.querySelector(selector) : null);
    if (!el || !visible(el)) el = candidates()[0];
    if (el) focusEl(el);
  }

  function move(dir) {
    var cur = document.activeElement;
    if (!cur || cur === document.body || !visible(cur)) { focusFirst(); return; }
    var from = cur.getBoundingClientRect();
    var best = null, bestScore = Infinity;
    candidates().forEach(function (el) {
      if (el === cur) return;
      var s = score(from, el.getBoundingClientRect(), dir);
      if (s < bestScore) { bestScore = s; best = el; }
    });
    if (best) focusEl(best);
  }

  function init() {
    if (!window.IPTV || !IPTV.isTv) return;
    document.addEventListener('keydown', function (ev) {
      var dir = DIRS[ev.key];
      if (!dir) return;
      var a = document.activeElement;
      // Im Suchfeld links/rechts für den Cursor lassen.
      if (a && a.tagName === 'INPUT' && (dir === 'left' || dir === 'right')) return;
      // Im Video links/rechts zum Spulen lassen.
      if (a && a.tagName === 'VIDEO' && (dir === 'left' || dir === 'right')) return;
      ev.preventDefault();
      move(dir);
    });
  }

  window.TV = { focusFirst: function (sel, el) { if (window.IPTV && IPTV.isTv) focusFirst(sel, el); } };
  init();
})();
