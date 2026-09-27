/**
 * مدير الصوت — نغمات قصيرة بأسلوب أجهزة اللاسلكي الحقيقية
 * مولّدة عبر Web Audio API (بدون ملفات صوتية) وخفيفة جدًا على الموارد.
 */

let ctx: AudioContext | null = null
let enabled = true
let masterGain: GainNode | null = null

export function initAudioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null
  if (!ctx) {
    const AC = window.AudioContext ?? (window as any).webkitAudioContext
    if (!AC) return null
    ctx = new AC()
    masterGain = ctx.createGain()
    masterGain.gain.value = 0.6
    masterGain.connect(ctx.destination)
  }
  if (ctx.state === 'suspended') ctx.resume().catch(() => {})
  return ctx
}

export function setSoundsEnabled(v: boolean) {
  enabled = v
}

function tone(
  freqStart: number,
  freqEnd: number,
  duration: number,
  delay = 0,
  type: OscillatorType = 'sine',
  volume = 0.16,
) {
  const c = initAudioContext()
  if (!c || !masterGain) return
  const osc = c.createOscillator()
  const gain = c.createGain()
  const t0 = c.currentTime + delay

  osc.type = type
  osc.frequency.setValueAtTime(freqStart, t0)
  if (freqEnd !== freqStart) osc.frequency.exponentialRampToValueAtTime(Math.max(freqEnd, 1), t0 + duration)

  gain.gain.setValueAtTime(0, t0)
  gain.gain.linearRampToValueAtTime(volume, t0 + 0.008)
  gain.gain.setValueAtTime(volume, t0 + duration - 0.02)
  gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration)

  osc.connect(gain)
  gain.connect(masterGain)
  osc.start(t0)
  osc.stop(t0 + duration + 0.05)
}

/** نقرة بدء الحديث — صافرة قصيرة حادة (PTT open) */
export function sfxTalkOn() {
  if (!enabled) return
  tone(1250, 1650, 0.09, 0, 'square', 0.07)
}

/** نقرة إيقاف الحديث (PTT close) */
export function sfxTalkOff() {
  if (!enabled) return
  tone(1400, 900, 0.1, 0, 'square', 0.06)
}

/** اتصال ناجح — نغمتان صاعدتان */
export function sfxConnected() {
  if (!enabled) return
  tone(660, 660, 0.09, 0, 'sine', 0.14)
  tone(990, 990, 0.12, 0.1, 'sine', 0.14)
}

/** انتهاء الاتصال — نغمتان هابطتان */
export function sfxDisconnected() {
  if (!enabled) return
  tone(880, 880, 0.09, 0, 'sine', 0.12)
  tone(587, 587, 0.14, 0.1, 'sine', 0.12)
}

/** الطرف الآخر بدأ الحديث — تنبيه خفيف */
export function sfxRemoteTalk() {
  if (!enabled) return
  tone(740, 980, 0.08, 0, 'triangle', 0.09)
}

/** القناة مشغولة — طنين مزدوج */
export function sfxBusy() {
  if (!enabled) return
  tone(220, 220, 0.09, 0, 'sawtooth', 0.1)
  tone(220, 220, 0.09, 0.14, 'sawtooth', 0.1)
}

/** رفض الاتصال */
export function sfxDeclined() {
  if (!enabled) return
  tone(520, 260, 0.28, 0, 'triangle', 0.12)
}

/** رنين طلب وارد */
export function sfxIncomingLoop() {
  if (!enabled) return
  tone(988, 988, 0.12, 0, 'sine', 0.13)
  tone(784, 784, 0.12, 0.16, 'sine', 0.13)
}

/** انقطاع الاتصال المفاجئ */
export function sfxStatic() {
  if (!enabled) return
  const c = initAudioContext()
  if (!c || !masterGain) return
  const bufferSize = c.sampleRate * 0.25
  const buffer = c.createBuffer(1, bufferSize, c.sampleRate)
  const data = buffer.getChannelData(0)
  for (let i = 0; i < bufferSize; i++) {
    data[i] = (Math.random() * 2 - 1) * (1 - i / bufferSize)
  }
  const src = c.createBufferSource()
  const gain = c.createGain()
  gain.gain.value = 0.05
  src.buffer = buffer
  src.connect(gain)
  gain.connect(masterGain)
  src.start()
}

export function vibrate(pattern: number | number[]) {
  try {
    if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      navigator.vibrate(pattern)
    }
  } catch {
    /* تجاهل */
  }
}
