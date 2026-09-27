/**
 * توليد أيقونات تطبيق أندرويد (لاسِلكي) + شاشة البداية
 * الاستخدام: node make-android-icons.mjs  (من داخل مجلد mobile-android)
 */
import sharp from 'sharp'
import { existsSync } from 'fs'
import { dirname, join } from 'path'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const RES = join(__dirname, '..', 'mobile-android', 'android', 'app', 'src', 'main', 'res')
const ICON = join(__dirname, 'icon-512.png') // أيقونة المشروع الجاهزة

// ترميز الجليف نفسه المستخدم في أيقونات PWA (بخلفية شفافة للـ adaptive foreground)
const glyphSvg = (size) => `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 512 512">
  <g fill="#FFFFFF">
    <rect x="249" y="74" width="15" height="76" rx="7.5"/>
    <rect x="199" y="138" width="114" height="248" rx="30"/>
    <rect x="306" y="204" width="16" height="50" rx="8"/>
  </g>
  <rect x="220" y="168" width="72" height="46" rx="11" fill="#92400E" opacity="0.92"/>
  <circle cx="238" cy="242" r="8.5" fill="#92400E" opacity="0.92"/>
  <circle cx="274" cy="242" r="8.5" fill="#92400E" opacity="0.92"/>
  <circle cx="238" cy="272" r="8.5" fill="#92400E" opacity="0.92"/>
  <circle cx="274" cy="272" r="8.5" fill="#92400E" opacity="0.92"/>
  <rect x="220" y="304" width="72" height="17" rx="8.5" fill="#92400E" opacity="0.92"/>
  <path d="M 168 96 a 74 74 0 0 1 34 -56" stroke="#FFFFFF" stroke-width="15" fill="none" stroke-linecap="round"/>
  <path d="M 128 132 a 132 132 0 0 1 60 -102" stroke="#FFFFFF" stroke-width="15" fill="none" stroke-linecap="round" opacity="0.7"/>
</svg>`

const densities = {
  mdpi: 48,
  hdpi: 72,
  xhdpi: 96,
  xxhdpi: 144,
  xxxhdpi: 192,
}
const fgSizes = {
  mdpi: 108,
  hdpi: 162,
  xhdpi: 216,
  xxhdpi: 324,
  xxxhdpi: 432,
}

if (!existsSync(ICON)) {
  console.error('icon-512.png غير موجودة بجانب السكربت')
  process.exit(1)
}

const iconBuf = await sharp(ICON).toBuffer()

for (const [dpi, size] of Object.entries(densities)) {
  const dir = join(RES, `mipmap-${dpi}`)
  if (!existsSync(dir)) continue
  // أيقونة قياسية
  await sharp(iconBuf).resize(size, size).png().toFile(join(dir, 'ic_launcher.png'))
  // أيقونة دائرية (قناع دائري)
  const circle = Buffer.from(
    `<svg width="${size}" height="${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" rect=""/></svg>`,
  )
  await sharp(iconBuf)
    .resize(size, size)
    .composite([{ input: circle, blend: 'dest-in' }])
    .png()
    .toFile(join(dir, 'ic_launcher_round.png'))
  console.log(`✓ mipmap-${dpi}/ic_launcher(+round).png`)
}

// أيقونة adaptive foreground: جليف أبيض داخل المنطقة الآمنة على خلفية شفافة
for (const [dpi, size] of Object.entries(fgSizes)) {
  const dir = join(RES, `mipmap-${dpi}`)
  if (!existsSync(dir)) continue
  const glyphPx = Math.round(size * 0.62)
  const glyph = await sharp(Buffer.from(glyphSvg(glyphPx))).png().toBuffer()
  await sharp({ create: { width: size, height: size, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
    .composite([{ input: glyph, gravity: 'centre' }])
    .png()
    .toFile(join(dir, 'ic_launcher_foreground.png'))
  console.log(`✓ mipmap-${dpi}/ic_launcher_foreground.png`)
}

// خلفية adaptive: كهرماني
const bgXml = `<resources>\n    <color name="ic_launcher_background">#F59E0B</color>\n</resources>`
await sharp(Buffer.from('<svg width="1" height="1"></svg>')).png().toFile('/dev/null').catch(() => {})
const { writeFileSync } = await import('fs')
writeFileSync(join(RES, 'values', 'ic_launcher_background.xml'), bgXml)
console.log('✓ values/ic_launcher_background.xml → #F59E0B')

// شاشة البداية: خلفية داكنة + الجليف في المنتصف (نحافظ على أبعاد كل ملف موجود)
const splashDirs = (await import('fs')).readdirSync(RES).filter((d) => d.startsWith('drawable') && d !== 'drawable-v24')
for (const d of splashDirs) {
  const p = join(RES, d, 'splash.png')
  if (!existsSync(p)) continue
  const meta = await sharp(p).metadata()
  const { width, height } = meta
  const glyphPx = Math.round(Math.min(width, height) * 0.24)
  const glyph = await sharp(Buffer.from(glyphSvg(glyphPx))).png().toBuffer()
  await sharp({ create: { width, height, channels: 4, background: '#171412' } })
    .composite([{ input: glyph, gravity: 'centre' }])
    .png()
    .toFile(p)
  console.log(`✓ ${d}/splash.png (${width}x${height})`)
}

console.log('تم توليد كل أيقونات أندرويد')
