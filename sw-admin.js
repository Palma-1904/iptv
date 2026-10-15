// Service Worker nur für die Admin-Fernbedienung (Scope „fernbedienung…“, die Webapp bleibt unberührt):
// empfängt den (leeren) Web-Push vom Mac, holt Titel/Text aus Firebase (<kanal>/alerts) und zeigt
// Banner + rote Zahl am Symbol. Fängt keine Seitenabrufe ab (kein fetch-Handler).
'use strict';
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('activate', function (e) { e.waitUntil(self.clients.claim()); });

function cfg() {
  return caches.open('admin-cfg').then(function (c) { return c.match('cfg'); })
    .then(function (r) { return r ? r.json() : null; });
}

self.addEventListener('push', function (e) {
  e.waitUntil(cfg().then(function (c) {
    if (!c) return null;
    return fetch('https://' + c.db + '/' + c.k + '/alerts.json', { cache: 'no-store' }).then(function (r) { return r.json(); });
  }).catch(function () { return null; }).then(function (a) {
    var n = (a && a.n) || 0;
    var last = (a && a.last) || {};
    var badge = self.navigator && self.navigator.setAppBadge
      ? (n ? self.navigator.setAppBadge(n) : self.navigator.clearAppBadge()).catch(function () {})
      : Promise.resolve();
    // iOS verlangt zu jedem Push eine sichtbare Mitteilung
    return badge.then(function () {
      return self.registration.showNotification(last.title || 'TV-Fernbedienung', {
        body: last.text || 'Neue Meldung – öffnen für Details',
        tag: last.id || 'iptv', icon: 'icons/apple-touch-icon.png', data: { url: 'fernbedienung.html' }
      });
    });
  }));
});

self.addEventListener('notificationclick', function (e) {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (list) {
    for (var i = 0; i < list.length; i++) if (list[i].url.indexOf('fernbedienung') >= 0) return list[i].focus();
    return self.clients.openWindow('fernbedienung.html');
  }));
});
