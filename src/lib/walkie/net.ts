/**
 * أدوات الشبكة — تحديد رابط خدمة الإشارة حسب بيئة التشغيل
 * ----------------------------------------------------------
 * يعمل التطبيق في عدة بيئات:
 *
 * 1) خلف بوابة (Caddy) كما في بيئة المعاينة السحابية:
 *    الصفحة تُخدَم عبر HTTPS على منفذ قياسي (80/443) بلا منفذ ظاهر،
 *    والوصول إلى خدمة الإشارة (منفذ 3003) يتم عبر مسار نسبي + XTransformPort.
 *
 * 2) على الشبكة المحلية الحقيقية (تطبيق ويندوز / متصفح يشير إلى خادم محلي):
 *    الصفحة تُخدَم من http://<عنوان-الجهاز>:3000،
 *    فتصل خدمة الإشارة مباشرة إلى نفس المضيف على المنفذ 3003.
 *
 * 3) تطبيق أندرويد المغلَّف (Capacitor):
 *    يفتح واجهة الخادم مع معامل ?signal=<host> — نحفظه محليًا ونستخدمه دائمًا،
 *    لأن الصفحة لا تعمل من نفس أصل الخادم في هذه الحالة.
 */

const SIGNAL_HOST_KEY = 'walkie.signal.host'

/** قراءة مضيف الإشارة المتجاوز: من المعامل ?signal= (ويُحفظ) أو من التخزين المحلي */
export function readSignalHostOverride(): string | null {
  if (typeof window === 'undefined') return null
  try {
    const param = new URLSearchParams(window.location.search).get('signal')
    if (param) {
      localStorage.setItem(SIGNAL_HOST_KEY, param)
      return param
    }
    return localStorage.getItem(SIGNAL_HOST_KEY)
  } catch {
    return null
  }
}

export function signalingUrl(): string {
  if (typeof window === 'undefined') return '/?XTransformPort=3003'

  // 3) تجاوز صريح بمضيف محدد (تطبيق أندرويد)
  const override = readSignalHostOverride()
  if (override) {
    const withProto = /^https?:\/\//i.test(override) ? override : `http://${override}`
    try {
      const u = new URL(withProto)
      return `${u.protocol}//${u.hostname}:${u.port || '3003'}`
    } catch {
      /* عنوان غير صالح → نكمل بالمنطق الافتراضي */
    }
  }

  const { protocol, hostname, port } = window.location
  const viaGateway = port === '' || port === '80' || port === '443'

  // 1) خلف البوابة
  if (viaGateway) return '/?XTransformPort=3003'

  // 2) تشغيل حقيقي على الشبكة المحلية: خدمة الإشارة على نفس مضيف الصفحة
  return `${protocol}//${hostname}:3003`
}
