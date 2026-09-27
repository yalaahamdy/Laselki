/**
 * عامل خدمة "لاسِلكي" — PWA
 * ---------------------------
 * الهدف: تثبيت موثوق كتطبيق مستقل على أندرويد (Chrome) وويندوز (Edge/Chrome)
 * مع تحميل فوري للواجهة. لا يتعامل مع WebSockets أو طلبات الإشارة إطلاقًا.
 */
const VERSION = 'walkie-v1'
const SHELL_CACHE = `${VERSION}-shell`
const ASSET_CACHE = `${VERSION}-assets`

const SHELL_ASSETS = [
  '/',
  '/manifest.webmanifest',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-maskable-512.png',
]

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      .then((cache) => cache.addAll(SHELL_ASSETS))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((k) => !k.startsWith(VERSION))
            .map((k) => caches.delete(k)),
        ),
      )
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return

  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return

  // لا تتدخل في: الإشارة (socket.io / البوابة)، الـ API، ومعاينة as-new
  const q = url.search
  if (
    q.includes('XTransformPort') ||
    q.includes('EIO=') ||
    q.includes('as-new') ||
    url.pathname.startsWith('/api/')
  ) {
    return
  }

  // الأصول المبنية (chunk ثابتة الاسم) → cache-first
  if (
    url.pathname.startsWith('/_next/static/') ||
    url.pathname.startsWith('/icons/') ||
    url.pathname === '/manifest.webmanifest'
  ) {
    event.respondWith(
      caches.match(req).then(
        (hit) =>
          hit ??
          fetch(req).then((res) => {
            const copy = res.clone()
            caches.open(ASSET_CACHE).then((c) => c.put(req, copy))
            return res
          }),
      ),
    )
    return
  }

  // التنقل بين الصفحات → network-first مع سقوط للغلاف المخزّن (وضع بلا اتصال)
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone()
          caches.open(SHELL_CACHE).then((c) => c.put(req, copy))
          return res
        })
        .catch(() => caches.match('/')),
    )
  }
})
