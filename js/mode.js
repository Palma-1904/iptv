// Ansicht umschalten per verstecktem Code: 5x schnell auf die Uhr oben tippen -> Code-Feld.
// Codes stehen in config.js (codes.main = Hauptansicht, codes.senior = Seniorenansicht).
// Ein Einrichtungs-Link kann die Ansicht direkt setzen: "#…&ansicht=senioren" bzw. "ansicht=komplett".
// Die gewählte Ansicht wird auf dem Gerät gespeichert; die jeweils andere Seite leitet dorthin um.
// Läuft im <head>, damit die Umleitung vor dem ersten Zeichnen passiert.
(function () {
  'use strict';

  var KEY = 'iptv-mode';
  var PAGES = { main: 'index.html', senior: 'senioren.html' };
  var page = /senioren\.html$/.test(location.pathname) ? 'senior' : 'main';

  function getMode() {
    try { return localStorage.getItem(KEY); } catch (err) { return null; }
  }
  function setMode(m) {
    try { localStorage.setItem(KEY, m); } catch (err) { /* privat-Modus: gilt nur für diesen Aufruf */ }
  }

  var wanted = new URLSearchParams(location.hash.slice(1)).get('ansicht');
  if (wanted === 'senioren') setMode('senior');
  if (wanted === 'komplett') setMode('main');

  var saved = getMode();
  if (saved && saved !== page && PAGES[saved]) {
    location.replace(PAGES[saved] + location.search + location.hash); // Einrichtungs-Link (#liste=…) mitnehmen
    return;
  }

  var TAPS_NEEDED = 5;
  var TAP_GAP = 1500; // ms zwischen zwei Tipps, sonst beginnt die Zählung neu
  var taps = 0, tapTimer, overlay;

  function onTap() {
    taps++;
    clearTimeout(tapTimer);
    tapTimer = setTimeout(function () { taps = 0; }, TAP_GAP);
    if (taps >= TAPS_NEEDED) { taps = 0; openDialog(); }
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text) e.textContent = text;
    return e;
  }

  function buildDialog() {
    overlay = el('div', 'pin-overlay');
    overlay.setAttribute('role', 'dialog');
    overlay.setAttribute('aria-modal', 'true');
    overlay.hidden = true;

    var form = el('form', 'pin-box');
    var label = el('label', 'pin-label', 'Code eingeben');
    label.htmlFor = 'pin-input';
    var input = el('input', 'pin-input');
    input.id = 'pin-input';
    input.type = 'password';
    input.inputMode = 'numeric';
    input.pattern = '[0-9]*';
    input.autocomplete = 'off';
    input.maxLength = 8;
    var error = el('p', 'pin-error', 'Falscher Code');
    error.hidden = true;
    var actions = el('div', 'pin-actions');
    var cancel = el('button', 'pin-cancel', 'Abbrechen');
    cancel.type = 'button';
    var ok = el('button', 'pin-ok', 'OK');
    ok.type = 'submit';
    actions.appendChild(cancel);
    actions.appendChild(ok);
    form.appendChild(label);
    form.appendChild(input);
    form.appendChild(error);
    form.appendChild(actions);
    overlay.appendChild(form);
    document.body.appendChild(overlay);

    cancel.addEventListener('click', closeDialog);
    overlay.addEventListener('keydown', function (ev) {
      if (ev.key === 'Escape') closeDialog();
    });
    input.addEventListener('input', function () { error.hidden = true; });

    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var codes = (window.IPTV_CONFIG && window.IPTV_CONFIG.codes) || {};
      var target = null;
      Object.keys(PAGES).forEach(function (m) {
        if (codes[m] && input.value === String(codes[m])) target = m;
      });
      if (!target) {
        error.hidden = false;
        input.value = '';
        input.focus();
        return;
      }
      setMode(target);
      if (target === page) closeDialog();
      else location.replace(PAGES[target]);
    });
  }

  function openDialog() {
    if (!overlay) buildDialog();
    overlay.hidden = false;
    var input = overlay.querySelector('.pin-input');
    input.value = '';
    overlay.querySelector('.pin-error').hidden = true;
    input.focus();
  }

  function closeDialog() {
    overlay.hidden = true;
    var trigger = document.getElementById('secret');
    if (trigger) trigger.focus();
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  // Uhr im Titel, aktualisiert pünktlich zum Minutenwechsel.
  function startClock(el) {
    var suffix = el.getAttribute('data-suffix') || '';
    (function tick() {
      var d = new Date();
      el.textContent = pad(d.getHours()) + ':' + pad(d.getMinutes()) + suffix;
      setTimeout(tick, 60000 - d.getSeconds() * 1000 - d.getMilliseconds() + 50);
    })();
  }

  document.addEventListener('DOMContentLoaded', function () {
    var trigger = document.getElementById('secret');
    if (!trigger) return;
    startClock(trigger);
    trigger.addEventListener('click', onTap);
  });
})();
