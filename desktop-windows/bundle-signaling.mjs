/**
 * تجميع خدمة الإشارة (socket.io + TypeScript) في ملف CJS واحد
 * يُشغَّل داخل عملية Electron الرئيسية بلا أي اعتماديات خارجية.
 * الاستخدام: node bundle-signaling.mjs
 */
import { build } from 'esbuild'

await build({
  entryPoints: ['../mini-services/signaling/index.ts'],
  bundle: true,
  platform: 'node',
  target: 'node18',
  format: 'cjs',
  outfile: 'resources/signaling.cjs',
  banner: { js: '/* walkie signaling service — bundled for Electron */' },
  logLevel: 'info',
})

console.log('✓ resources/signaling.cjs')
