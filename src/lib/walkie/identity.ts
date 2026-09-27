/**
 * هوية الجهاز — بدون حسابات أو تسجيل دخول
 * تُحفظ محليًا على الجهاز فقط (localStorage)،
 * مع دعم جلسات مستقلة للتجربة على نفس المتصفح (sessionStorage عند ?as-new=1).
 */
import type { DeviceProfile } from './types'

const LS_KEY = 'walkie.identity.v1'
const SS_KEY = 'walkie.identity.session.v1'

export const AVATARS = [
  'Panda',
  'Bird',
  'Cat',
  'Dog',
  'Rabbit',
  'Squirrel',
  'Turtle',
  'Rocket',
] as const

export type AvatarName = (typeof AVATARS)[number]

/** لوحة ألوان مرحة لكن راقية — بدون أزرق/نيلي */
export const AVATAR_COLORS: Record<AvatarName, { bg: string; fg: string }> = {
  Panda: { bg: '#10B981', fg: '#022C22' },
  Bird: { bg: '#14B8A6', fg: '#042F2E' },
  Cat: { bg: '#F43F5E', fg: '#4C0519' },
  Dog: { bg: '#D97706', fg: '#451A03' },
  Rabbit: { bg: '#F472B6', fg: '#500724' },
  Squirrel: { bg: '#84CC16', fg: '#1A2E05' },
  Turtle: { bg: '#14B8A6', fg: '#042F2E' },
  Rocket: { bg: '#F97316', fg: '#431407' },
}

export interface Identity extends DeviceProfile {
  createdAt: number
}

const ADJ = ['السريع', 'الفضي', 'الشجاع', 'الودود', 'الخاطف', 'الصقر', 'النجم', 'العاصف']

export function suggestName(): string {
  const a = ADJ[Math.floor(Math.random() * ADJ.length)]
  const n = 10 + Math.floor(Math.random() * 90)
  return `جهاز ${a} ${n}`
}

function randomId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export function deviceModel(): string {
  if (typeof navigator === 'undefined') return ''
  const ua = navigator.userAgent
  let os = 'جهاز'
  if (/iPhone/i.test(ua)) os = 'iPhone'
  else if (/iPad/i.test(ua)) os = 'iPad'
  else if (/Android/i.test(ua)) os = 'Android'
  else if (/Macintosh/i.test(ua)) os = 'Mac'
  else if (/Windows/i.test(ua)) os = 'Windows'
  else if (/Linux/i.test(ua)) os = 'Linux'
  const browser = /CriOS|Chrome/i.test(ua)
    ? 'Chrome'
    : /Firefox/i.test(ua)
      ? 'Firefox'
      : /Safari/i.test(ua)
        ? 'Safari'
        : ''
  return [os, browser].filter(Boolean).join(' · ')
}

function makeIdentity(): Identity {
  const names = AVATARS
  const avatar = names[Math.floor(Math.random() * names.length)]
  return {
    deviceId: randomId(),
    name: suggestName(),
    avatar,
    model: deviceModel(),
    createdAt: Date.now(),
  }
}

function validate(v: unknown): Identity | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Partial<Identity>
  if (typeof o.deviceId !== 'string' || !o.deviceId) return null
  if (typeof o.name !== 'string' || !o.name.trim()) return null
  return {
    deviceId: o.deviceId,
    name: o.name.slice(0, 32),
    avatar: (AVATARS as readonly string[]).includes(o.avatar ?? '')
      ? (o.avatar as AvatarName)
      : 'Panda',
    model: typeof o.model === 'string' ? o.model : deviceModel(),
    createdAt: typeof o.createdAt === 'number' ? o.createdAt : Date.now(),
  }
}

export function loadIdentity(): Identity | null {
  if (typeof window === 'undefined') return null

  // وضع "جهاز تجريبي مستقل" — نافذة ثانية على نفس المتصفح
  const asNew =
    typeof window !== 'undefined' &&
    new URLSearchParams(window.location.search).has('as-new')

  if (asNew) {
    const raw = sessionStorage.getItem(SS_KEY)
    if (raw) {
      const parsed = validate(JSON.parse(raw))
      if (parsed) return parsed
    }
    const fresh = makeIdentity()
    sessionStorage.setItem(SS_KEY, JSON.stringify(fresh))
    return fresh
  }

  const raw = localStorage.getItem(LS_KEY)
  if (raw) {
    const parsed = validate(JSON.parse(raw))
    if (parsed) return parsed
  }
  return null
}

export function saveIdentity(identity: Identity, asNew: boolean): void {
  const json = JSON.stringify(identity)
  if (asNew) sessionStorage.setItem(SS_KEY, json)
  else localStorage.setItem(LS_KEY, json)
}

export function isAsNewMode(): boolean {
  if (typeof window === 'undefined') return false
  return new URLSearchParams(window.location.search).has('as-new')
}

export function avatarColor(avatar: string): { bg: string; fg: string } {
  return (
    AVATAR_COLORS[avatar as AvatarName] ?? { bg: '#F59E0B', fg: '#451A03' }
  )
}
