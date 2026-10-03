// Caches only the app shell (index.html + icons). Exchange data (REST, WebSocket),
// CDN scripts and fonts are never intercepted and always go to the network.
const CACHE = 'heatmap-v1';
const SHELL = ['./', './index.html', './icon-192.png', './icon-512.png'];
const SHELL_URLS = new Set(SHELL.map(p => new URL(p, self.location).href));

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  url.search = ''; url.hash = '';
  if (!SHELL_URLS.has(url.href)) return;

  // Network first so a new index.html is picked up; fall back to cache offline.
  e.respondWith(
    fetch(req)
      .then(res => {
        if (res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(url.href, copy)); }
        return res;
      })
      .catch(() => caches.match(url.href))
  );
});
