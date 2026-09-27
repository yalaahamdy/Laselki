/**
 * متجر الحالة المركزي — يربط محرك الاتصال بالواجهة
 */
import { create } from 'zustand'
import type { Identity } from './identity'
import type { PublicDevice, SessionState, WalkieSettings } from './types'

export type AppPhase = 'boot' | 'onboarding' | 'ready'

export interface SessionInfo {
  peer: PublicDevice
  state: SessionState
  talkingLocal: boolean
  talkingRemote: boolean
  startedAt: number | null
  latencyMs: number | null
  quality: 'excellent' | 'good' | 'weak' | 'unknown'
  declineReason?: string
}

export interface WalkieStore {
  phase: AppPhase
  identity: Identity | null
  isAsNew: boolean

  signalConnected: boolean
  networkOnline: boolean
  devices: PublicDevice[]
  micGranted: boolean | null // null = لم يُسأل بعد
  micDenied: boolean

  session: SessionInfo | null
  incomingFrom: PublicDevice | null
  settingsOpen: boolean

  settings: WalkieSettings

  // أفعال محلية (المحرك يحدّث الحالة مباشرة أيضًا)
  setPhase: (p: AppPhase) => void
  setIdentity: (id: Identity | null) => void
  setAsNew: (v: boolean) => void
  setSignalConnected: (v: boolean) => void
  setNetworkOnline: (v: boolean) => void
  setDevices: (d: PublicDevice[]) => void
  setMicGranted: (v: boolean) => void
  setMicDenied: (v: boolean) => void
  setSession: (s: SessionInfo | null) => void
  patchSession: (p: Partial<SessionInfo>) => void
  setIncomingFrom: (d: PublicDevice | null) => void
  setSettingsOpen: (v: boolean) => void
  updateSettings: (p: Partial<WalkieSettings>) => void
}

const SETTINGS_KEY = 'walkie.settings.v1'

function loadSettings(): WalkieSettings {
  if (typeof window !== 'undefined') {
    try {
      const raw = localStorage.getItem(SETTINGS_KEY)
      if (raw) {
        const p = JSON.parse(raw)
        return {
          sounds: p.sounds !== false,
          askPermission: p.askPermission === true,
        }
      }
    } catch {
      /* تجاهل */
    }
  }
  return { sounds: true, askPermission: false }
}

function persistSettings(s: WalkieSettings) {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(s))
  } catch {
    /* تجاهل */
  }
}

export const useWalkie = create<WalkieStore>((set, get) => ({
  phase: 'boot',
  identity: null,
  isAsNew: false,

  signalConnected: false,
  networkOnline: true,
  devices: [],
  micGranted: null,
  micDenied: false,

  session: null,
  incomingFrom: null,
  settingsOpen: false,

  settings: loadSettings(),

  setPhase: (phase) => set({ phase }),
  setIdentity: (identity) => set({ identity }),
  setAsNew: (isAsNew) => set({ isAsNew }),
  setSignalConnected: (signalConnected) => set({ signalConnected }),
  setNetworkOnline: (networkOnline) => set({ networkOnline }),
  setDevices: (devices) => set({ devices }),
  setMicGranted: (micGranted) => set({ micGranted }),
  setMicDenied: (micDenied) => set({ micDenied }),
  setSession: (session) => set({ session }),
  patchSession: (patch) => {
    const cur = get().session
    if (cur) set({ session: { ...cur, ...patch } })
  },
  setIncomingFrom: (incomingFrom) => set({ incomingFrom }),
  setSettingsOpen: (settingsOpen) => set({ settingsOpen }),
  updateSettings: (patch) => {
    const next = { ...get().settings, ...patch }
    persistSettings(next)
    set({ settings: next })
  },
}))
