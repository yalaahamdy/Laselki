/**
 * توليد أيقونات تطبيق "لاسِلكي" بجميع المقاسات المطلوبة لـ PWA
 * الاستخدام: node scripts/make-icons.mjs
 */
import sharp from 'sharp'
import { mkdirSync } from 'fs'

const OUT = 'public/icons'
mkdirSync(OUT, { recursive: true })

// جهاز لاسلكي أبيض على خلفية كهرمانية متدرجة
const glyph = (scale = 1, cx = 256, cy = 256) => {
  const s = scale
  const t = (x, y) => `${cx + (x - 256) * s} ${cy + (y - 256) * s}`
  // نبني المجموعة بإحداثيات أصيلة 512 ثم نحولها scale/translate
  return `
  <g transform="translate(${cx} ${cy}) scale(${s}) translate(-256 -256)">
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
  </g>`
}

const bg = (rounded) =>
  rounded
    ? `<rect width="512" height="512" rx="118" fill="url(#g)"/>
       <rect x="14" y="14" width="484" height="484" rx="106" fill="none" stroke="#FFFFFF" stroke-opacity="0.16" stroke-width="6"/>`
    : `<rect width="512" height="512" fill="url(#g)"/>`

const svg = ({ rounded, scale, cx, cy }) => `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#FBBF24"/>
      <stop offset="0.55" stop-color="#F59E0B"/>
      <stop offset="1" stop-color="#B45309"/>
    </linearGradient>
  </defs>
  ${bg(rounded)}
  ${glyph(scale, cx, cy)}
</svg>`

const targets = [
  // أيقونة قياسية 512
  { file: 'icon-512.png', size: 512, opts: { rounded: true, scale: 1, cx: 268, cy: 276 } },
  // أيقونة قياسية 192
  { file: 'icon-192.png', size: 192, opts: { rounded: true, scale: 1, cx: 268, cy: 276 } },
  // maskable: خلفية كاملة بلا زوايا + المحتوى داخل المنطقة الآمنة (80%)
  { file: 'icon-maskable-512.png', size: 512, opts: { rounded: false, scale: 0.78, cx: 262, cy: 278 } },
  // apple-touch-icon: خلفية كاملة (آبل تقص الزوايا بنفسها)
  { file: 'apple-touch-icon.png', size: 180, opts: { rounded: false, scale: 0.84, cx: 262, cy: 276 } },
]

for (const t of targets) {
  await sharp(Buffer.from(svg(t.opts)))
    .resize(t.size, t.size)
    .png()
    .toFile(`${OUT}/${t.file}`)
  console.log(`✓ ${OUT}/${t.file}`)
}
console.log('تم توليد جميع الأيقونات')
