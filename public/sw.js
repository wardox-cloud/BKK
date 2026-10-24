// Offline shell: the app opens without a connection (dictating still needs one).
const CACHE = 'murmur-v1';
const SHELL = ['./', './manifest.webmanifest', './icons/icon-192.png', './icons/apple-touch-icon.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);
  // API calls (ours and the providers') always go to the network.
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.includes('/api/')) return;

  const save = (response) => {
    if (response.ok) {
      const copy = response.clone();
      caches.open(CACHE).then((cache) => cache.put(request, copy));
    }
    return response;
  };

  if (request.mode === 'navigate') {
    // Fresh page when online, so updates show up; cached page when offline.
    event.respondWith(
      fetch(request)
        .then(save)
        .catch(() => caches.match(request).then((cached) => cached ?? caches.match('./'))),
    );
    return;
  }
  // Built assets have hashed names, so a cached copy is never stale.
  event.respondWith(caches.match(request).then((cached) => cached ?? fetch(request).then(save)));
});
