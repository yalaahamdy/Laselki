/**
 * الأنواع المشتركة بين العميل وخدمة الإشارة
 */

export interface DeviceProfile {
  deviceId: string
  name: string
  avatar: string
  model: string
}

export interface PublicDevice extends DeviceProfile {
  busy: boolean
  talking: boolean
  joinedAt: number
}

export type RtcSignal =
  | { kind: 'offer'; sdp: string }
  | { kind: 'answer'; sdp: string }
  | { kind: 'ice'; candidate: RTCIceCandidateInit }

export type SessionState =
  | 'calling' // جارٍ الاتصال بـ…
  | 'incoming' // طلب وارد
  | 'connecting' // جارٍ إنشاء القناة
  | 'connected' // متصل — جاهز للحديث
  | 'reconnecting' // استعادة الاتصال
  | 'failed' // تعذر الاتصال

export interface WalkieSettings {
  sounds: boolean
  askPermission: boolean
}

export const MAX_TALK_MS = 120_000 // إعادة تلقائية بعد دقيقتين حديث متواصل
export const CALL_TIMEOUT_MS = 30_000
