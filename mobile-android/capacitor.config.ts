import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'app.laselki.walkie',
  appName: 'لاسِلكي',
  webDir: 'www',
  server: {
    // المحتوى المحلي يُخدَم عبر https://localhost (سياق آمن للميكروفون)
    androidScheme: 'https',
    // نسمح بالانتقال داخل WebView إلى خادم اللاسلكي على الشبكة المحلية
    allowNavigation: ['*'],
  },
  android: {
    allowMixedContent: true,
  },
}

export default config
