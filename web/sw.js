// Offline shell: the app files come from the cache first and refresh in the background.
// Supabase and font requests always go to the network.
const CACHE = "diligent-web-v16"; // bump whenever web files change
const SHELL = ["./", "index.html", "css/style.css", "js/quotes.js", "js/store.js", "js/cloud.js", "js/plan.js", "js/coach.js", "js/profile.js", "js/fluid.js", "js/cursor.js", "js/app.js", "icon.svg", "manifest.webmanifest"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  e.respondWith(
    caches.open(CACHE).then(async (cache) => {
      const hit = await cache.match(e.request, { ignoreSearch: true });
      const fresh = fetch(e.request).then((res) => { if (res.ok) cache.put(e.request, res.clone()); return res; }).catch(() => hit);
      return hit || fresh;
    })
  );
});
