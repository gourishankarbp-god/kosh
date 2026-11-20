// Offline shell: cache the app files; data lives in IndexedDB; Google/Gemini calls always go to the network.
const CACHE = 'kosh-v1'
self.addEventListener('install', e => self.skipWaiting())
self.addEventListener('activate', e => e.waitUntil(caches.keys().then(k => Promise.all(k.filter(n => n !== CACHE).map(n => caches.delete(n)))).then(() => self.clients.claim())))
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url)
  if (e.request.method !== 'GET' || u.origin !== location.origin) return
  e.respondWith(fetch(e.request).then(r => { const c = r.clone(); caches.open(CACHE).then(x => x.put(e.request, c)); return r }).catch(() => caches.match(e.request).then(r => r || caches.match('./'))))
})
