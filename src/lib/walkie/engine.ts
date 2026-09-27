/**
 * محرك اللاسلكي — WebRTC Push-to-Talk منخفض الكمون
 * --------------------------------------------------
 * المبادئ:
 *  - قناة صوت مباشرة بين الجهازين (P2P) عبر WebRTC/Opus.
 *  - زر الحديث يفعّل المسار الصوتي فورًا (track.enabled) بدون إعادة تفاوض — استجابة لحظية.
 *  - DataChannel للتحكم (بدء/إيقاف الحديث، قياس زمن الاستجابة).
 *  - خادم الإشارة يُستخدم فقط لاكتشاف الأجهزة وتمرير إشارات الاتصال الأولية.
 *  - إعادة اتصال تلقائية (ICE Restart) عند انقطاع الشبكة المؤقت.
 */

import { io, type Socket } from 'socket.io-client'
import { toast } from '@/hooks/use-toast'
import {
  isAsNewMode,
  loadIdentity,
  saveIdentity,
  type Identity,
} from './identity'
import { signalingUrl } from './net'
import {
  initAudioContext,
  setSoundsEnabled,
  sfxBusy,
  sfxConnected,
  sfxDeclined,
  sfxDisconnected,
  sfxIncomingLoop,
  sfxRemoteTalk,
  sfxStatic,
  sfxTalkOff,
  sfxTalkOn,
  vibrate,
} from './sound'
import { useWalkie } from './store'
import {
  CALL_TIMEOUT_MS,
  MAX_TALK_MS,
  type PublicDevice,
  type RtcSignal,
} from './types'

/* ------------------------------------------------------------------ */
/* أدوات مساعدة                                                        */
/* ------------------------------------------------------------------ */

/** خوادم STUN تُستخدم كملاذ أخير فقط عند فشل الاتصال المباشر على الشبكة المحلية؛
 *  الصوت لا يمر عبرها أبدًا. على نفس شبكة Wi-Fi يتم الاتصال مباشرة عبر العناوين المحلية. */
const ICE_SERVERS: RTCIceServer[] = [
  { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] },
]

/** ضبط ترميز Opus: جودة كلام ممتازة + كتم أخطاء الشبكة (FEC) + زمن استجابة أدنى */
function tuneOpusSdp(sdp: string): string {
  const m = sdp.match(/a=rtpmap:(\d+) opus\/48000\/2/)
  if (!m) return sdp
  const pt = m[1]
  const fmtp = `a=fmtp:${pt} minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=32000;stereo=0`
  if (sdp.includes(`a=fmtp:${pt} `)) {
    return sdp.replace(new RegExp(`a=fmtp:${pt} [^\\r\\n]*`), fmtp)
  }
  return sdp.replace(
    new RegExp(`(a=rtpmap:${pt} opus/48000/2\\r\\n)`),
    `$1${fmtp}\r\n`,
  )
}

function qualityOf(ms: number | null): 'excellent' | 'good' | 'weak' | 'unknown' {
  if (ms == null) return 'unknown'
  if (ms < 60) return 'excellent'
  if (ms < 140) return 'good'
  return 'weak'
}

/* ------------------------------------------------------------------ */
/* المحرك                                                              */
/* ------------------------------------------------------------------ */

class WalkieEngine {
  private socket: Socket | null = null
  private identity: Identity | null = null

  private localStream: MediaStream | null = null
  private localTrack: MediaStreamTrack | null = null
  private localAnalyser: AnalyserNode | null = null

  private pc: RTCPeerConnection | null = null
  private dc: RTCDataChannel | null = null
  private remoteStream: MediaStream | null = null
  private remoteAnalyser: AnalyserNode | null = null
  private audioEl: HTMLAudioElement | null = null
  private audioCtx: AudioContext | null = null

  private peer: PublicDevice | null = null
  private iceRestartAttempts = 0
  private pendingOfferFrom: string | null = null
  private callTimeout: ReturnType<typeof setTimeout> | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private autoReconnectTimer: ReturnType<typeof setTimeout> | null = null
  private talkWatchdog: ReturnType<typeof setInterval> | null = null
  private talkStartedAt = 0
  private ringingInterval: ReturnType<typeof setInterval> | null = null

  private wakeLock: WakeLockSentinel | null = null
  private disposed = false
  private initialized = false

  /* ---------------------- التهيئة ---------------------- */

  init(identity: Identity) {
    if (this.initialized) {
      this.setIdentity(identity)
      return
    }
    this.initialized = true
    this.identity = identity
    const store = useWalkie.getState()
    store.setIdentity(identity)
    store.setAsNew(isAsNewMode())
    store.setNetworkOnline(navigator.onLine)
    setSoundsEnabled(store.settings.sounds)

    this.attachGlobalListeners()
    this.connectSignaling()
  }

  setIdentity(identity: Identity) {
    this.identity = identity
    useWalkie.getState().setIdentity(identity)
    this.socket?.emit('hello', {
      deviceId: identity.deviceId,
      name: identity.name,
      avatar: identity.avatar,
      model: identity.model,
    })
  }

  updateSettings() {
    setSoundsEnabled(useWalkie.getState().settings.sounds)
  }

  private attachGlobalListeners() {
    window.addEventListener('online', () =>
      useWalkie.getState().setNetworkOnline(true),
    )
    window.addEventListener('offline', () => {
      useWalkie.getState().setNetworkOnline(false)
    })
    window.addEventListener('pointerdown', () => initAudioContext(), {
      passive: true,
    })
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        if (this.pc && this.pc.connectionState === 'connected') {
          this.requestWakeLock()
        }
        this.socket?.emit('list-devices')
      }
    })
    window.addEventListener('beforeunload', () => {
      if (this.peer) this.socket?.emit('call-end', { to: this.peer.deviceId })
      this.socket?.disconnect()
    })
  }

  /* ---------------------- خدمة الإشارة ---------------------- */

  private connectSignaling() {
    if (this.socket) return
    const store = useWalkie.getState()

    const socket = io(signalingUrl(), {
      path: '/',
      transports: ['websocket', 'polling'],
      reconnection: true,
      reconnectionDelay: 800,
      reconnectionDelayMax: 4000,
      timeout: 8000,
    })
    this.socket = socket

    socket.on('connect', () => {
      useWalkie.getState().setSignalConnected(true)
      const id = this.identity
      if (id) {
        socket.emit('hello', {
          deviceId: id.deviceId,
          name: id.name,
          avatar: id.avatar,
          model: id.model,
        })
      }
    })

    socket.on('disconnect', (reason) => {
      useWalkie.getState().setSignalConnected(false)
      if (reason === 'io server disconnect') socket.connect()
    })

    socket.on('welcome', ({ devices }: { devices: PublicDevice[] }) => {
      useWalkie.getState().setDevices(devices)
    })

    socket.on('devices', ({ devices }: { devices: PublicDevice[] }) => {
      useWalkie.getState().setDevices(devices)
      this.handlePresenceChange(devices)
    })

    socket.on('replaced', () => {
      socket.disconnect()
    })

    socket.on('call-request', ({ from }: { from: PublicDevice }) => {
      this.handleIncoming(from)
    })

    socket.on(
      'call-response',
      ({
        accepted,
        reason,
        from,
      }: {
        accepted: boolean
        reason?: string
        from: PublicDevice
      }) => {
        if (!this.peer || this.peer.deviceId !== from.deviceId) return
        if (accepted) {
          // قَبِل الطرف الآخر → نبدأ عرض الاتصال (نحن من ينشئ قناة التحكم)
          useWalkie.getState().patchSession({ state: 'connecting' })
          this.createPeerConnection(true).then(() => this.makeOffer())
        } else {
          this.clearCallTimeout()
          sfxDeclined()
          useWalkie
            .getState()
            .patchSession({ state: 'failed', declineReason: reason ?? 'declined' })
        }
      }
    )

    socket.on('call-ended', () => {
      if (this.peer) this.teardownSession(false)
    })

    socket.on(
      'signal',
      ({ from, data }: { from: PublicDevice; data: RtcSignal }) => {
        if (!this.peer || this.peer.deviceId !== from.deviceId) return
        this.handleSignal(data)
      }
    )

    socket.on('talk-state', ({ from, talking }: { from: PublicDevice; talking: boolean }) => {
      if (!this.peer || this.peer.deviceId !== from.deviceId) return
      // تُستخدم فقط إذا كان DataChannel غير جاهز بعد
      if (this.dc?.readyState !== 'open') {
        this.setRemoteTalking(talking)
      }
    })

    socket.on('call-error', ({ reason }: { reason: string }) => {
      if (reason === 'peer-offline' && this.peer) {
        useWalkie.getState().patchSession({ state: 'failed', declineReason: 'peer-offline' })
      }
    })

    store.setSignalConnected(socket.connected)
  }

  private handlePresenceChange(devices: PublicDevice[]) {
    const session = useWalkie.getState().session
    if (!session) return
    const peerNow = devices.find((d) => d.deviceId === session.peer.deviceId)
    if (!peerNow && this.peer) {
      // الطرف الآخر غادر الشبكة
      useWalkie.getState().patchSession({ state: 'reconnecting' })
      this.scheduleAutoRecall()
    } else if (peerNow && session.state === 'reconnecting' && !this.pc) {
      // عاد الجهاز إلى الشبكة → إعادة اتصال تلقائية صامتة
      this.clearTimer(this.autoReconnectTimer)
      this.autoReconnectTimer = setTimeout(() => {
        if (
          useWalkie.getState().session?.state === 'reconnecting' &&
          !this.pc
        ) {
          this.internalCall(peerNow, true)
        }
      }, 1200)
    }
  }

  /* ---------------------- الميكروفون ---------------------- */

  async ensureMic(): Promise<boolean> {
    if (this.localStream && this.localTrack?.readyState === 'live') {
      useWalkie.getState().setMicGranted(true)
      return true
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1,
        },
        video: false,
      })
      this.localStream = stream
      const track = stream.getAudioTracks()[0]
      track.contentHint = 'speech'
      track.enabled = false // يُفعَّل فقط عند الضغط على زر الحديث
      this.localTrack = track

      const actx = initAudioContext()
      this.audioCtx = actx
      if (actx) {
        const src = actx.createMediaStreamSource(stream)
        const analyser = actx.createAnalyser()
        analyser.fftSize = 256
        analyser.smoothingTimeConstant = 0.75
        src.connect(analyser)
        this.localAnalyser = analyser
      }

      useWalkie.getState().setMicGranted(true)
      useWalkie.getState().setMicDenied(false)
      return true
    } catch {
      useWalkie.getState().setMicGranted(false)
      useWalkie.getState().setMicDenied(true)
      return false
    }
  }

  // دالات مربوطة عمدًا (class fields بشكل سهم) — تُمرَّر كمرجع إلى مكوّن الموجة
  // الصوتية وتُستدعى داخل حلقة requestAnimationFrame، فلا يجوز أن تفقد this.
  // كما تُعيد null بأمان إن لم يكن المحلل جاهزًا بعد بدل undefined.
  getLocalAnalyser = (): AnalyserNode | null => this.localAnalyser ?? null
  getRemoteAnalyser = (): AnalyserNode | null => this.remoteAnalyser ?? null

  /* ---------------------- بدء / ردّ الاتصال ---------------------- */

  async call(target: PublicDevice) {
    if (useWalkie.getState().session) return
    if (target.busy || target.talking) {
      sfxBusy()
      toast({
        title: 'الجهاز مشغول الآن',
        description: `${target.name} في محادثة أخرى — جرّب بعد قليل.`,
      })
      return
    }
    const ok = await this.ensureMic()
    if (!ok) {
      toast({
        title: 'الميكروفون غير مفعّل',
        description:
          'اسمح بالوصول إلى الميكروفون من إعدادات المتصفح لتتمكن من التحدث.',
        variant: 'destructive',
      })
      return
    }

    this.peer = target
    useWalkie.getState().setSession({
      peer: target,
      state: 'calling',
      talkingLocal: false,
      talkingRemote: false,
      startedAt: null,
      latencyMs: null,
      quality: 'unknown',
    })
    this.startRingingLoop(false)

    this.socket?.emit('call-request', { to: target.deviceId })
    this.setBusy(true)
    this.clearTimer(this.callTimeout)
    this.callTimeout = setTimeout(() => {
      const s = useWalkie.getState().session
      if (s && s.state === 'calling') {
        useWalkie.getState().patchSession({
          state: 'failed',
          declineReason: 'timeout',
        })
        this.stopRingingLoop()
      }
    }, CALL_TIMEOUT_MS)
  }

  private async internalCall(target: PublicDevice, auto: boolean) {
    // إعادة اتصال تلقائية صامتة بعد عودة الجهاز للشبكة
    const ok = await this.ensureMic()
    if (!ok) return
    this.peer = target
    useWalkie.getState().setSession({
      peer: target,
      state: 'connecting',
      talkingLocal: false,
      talkingRemote: false,
      startedAt: null,
      latencyMs: null,
      quality: 'unknown',
    })
    void auto
    this.socket?.emit('call-request', { to: target.deviceId })
    this.setBusy(true)
    this.clearTimer(this.callTimeout)
    this.callTimeout = setTimeout(() => {
      const s = useWalkie.getState().session
      if (s && (s.state === 'calling' || s.state === 'connecting')) {
        useWalkie.getState().patchSession({ state: 'failed', declineReason: 'timeout' })
      }
    }, CALL_TIMEOUT_MS)
  }

  private handleIncoming(from: PublicDevice) {
    const store = useWalkie.getState()
    if (store.session) {
      // مشغول → رفض تلقائي مهذب
      this.socket?.emit('call-response', {
        to: from.deviceId,
        accepted: false,
        reason: 'busy',
      })
      return
    }
    if (store.settings.askPermission) {
      store.setIncomingFrom(from)
      this.startRingingLoop(true)
      setTimeout(() => {
        if (useWalkie.getState().incomingFrom?.deviceId === from.deviceId) {
          this.declineIncoming(true)
        }
      }, 25000)
    } else {
      // وضع تلقائي: قبول فوري — جوهر تجربة اللاسلكي السريع
      this.peer = from
      useWalkie.getState().setSession({
        peer: from,
        state: 'connecting',
        talkingLocal: false,
        talkingRemote: false,
        startedAt: null,
        latencyMs: null,
        quality: 'unknown',
      })
      this.socket?.emit('call-response', { to: from.deviceId, accepted: true })
      this.setBusy(true)
    }
  }

  async acceptIncoming() {
    const from = useWalkie.getState().incomingFrom
    if (!from) return
    const ok = await this.ensureMic()
    if (!ok) return
    this.stopRingingLoop()
    useWalkie.getState().setIncomingFrom(null)
    this.peer = from
    useWalkie.getState().setSession({
      peer: from,
      state: 'connecting',
      talkingLocal: false,
      talkingRemote: false,
      startedAt: null,
      latencyMs: null,
      quality: 'unknown',
    })
    this.socket?.emit('call-response', { to: from.deviceId, accepted: true })
    this.setBusy(true)
  }

  declineIncoming(silent = false) {
    const from = useWalkie.getState().incomingFrom
    if (!from) return
    this.stopRingingLoop()
    useWalkie.getState().setIncomingFrom(null)
    this.socket?.emit('call-response', {
      to: from.deviceId,
      accepted: false,
      reason: silent ? 'timeout' : 'declined',
    })
  }

  cancelCall() {
    const s = useWalkie.getState().session
    if (s) this.socket?.emit('call-end', { to: s.peer.deviceId })
    this.teardownSession(true)
  }

  endSession() {
    const s = useWalkie.getState().session
    if (s) this.socket?.emit('call-end', { to: s.peer.deviceId })
    this.teardownSession(true)
  }

  private scheduleAutoRecall() {
    // تجربة استعادة الاتصال لفترة قصيرة، ثم إنهاء مهذب
    this.clearTimer(this.autoReconnectTimer)
    this.autoReconnectTimer = setTimeout(() => {
      const s = useWalkie.getState().session
      if (s && s.state === 'reconnecting') {
        this.teardownSession(false)
        sfxStatic()
      }
    }, 20000)
  }

  /* ---------------------- اتصال WebRTC ---------------------- */

  private async createPeerConnection(asCaller = false) {
    if (this.pc) return
    const pc = new RTCPeerConnection({
      iceServers: ICE_SERVERS,
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require',
    })
    this.pc = pc
    this.iceRestartAttempts = 0

    if (this.localTrack && this.localStream) {
      pc.addTrack(this.localTrack, this.localStream)
    }

    // الطرف المتصل (Caller) ينشئ قناة التحكم، والطرف الآخر يلتقطها عبر ondatachannel
    if (asCaller) {
      this.wireDataChannel(pc.createDataChannel('ctrl', { ordered: true }))
    } else {
      pc.ondatachannel = (ev) => {
        this.wireDataChannel(ev.channel)
      }
    }

    pc.onicecandidate = (ev) => {
      if (ev.candidate && this.peer) {
        this.socket?.emit('signal', {
          to: this.peer.deviceId,
          data: {
            kind: 'ice',
            candidate: ev.candidate.toJSON(),
          } satisfies RtcSignal,
        })
      }
    }

    pc.ontrack = (ev) => {
      this.remoteStream = ev.streams[0]
      this.setupRemoteAudio(this.remoteStream)
    }

    pc.onconnectionstatechange = () => {
      const state = pc.connectionState
      const store = useWalkie.getState()
      if (!store.session) return

      if (state === 'connected') {
        this.iceRestartAttempts = 0
        this.clearTimer(this.reconnectTimer)
        this.clearTimer(this.callTimeout)
        this.stopRingingLoop()
        useWalkie.getState().patchSession({
          state: 'connected',
          startedAt: useWalkie.getState().session?.startedAt ?? Date.now(),
        })
        sfxConnected()
        this.requestWakeLock()
      } else if (state === 'disconnected') {
        useWalkie.getState().patchSession({ state: 'reconnecting' })
        this.clearTimer(this.reconnectTimer)
        this.reconnectTimer = setTimeout(() => {
          if (pc.connectionState === 'disconnected') {
            this.tryIceRestart()
          }
        }, 1500)
      } else if (state === 'failed') {
        this.tryIceRestart()
      } else if (state === 'closed') {
        this.stopPingLoop()
      }
    }
  }

  private wireDataChannel(dc: RTCDataChannel) {
    this.dc = dc
    dc.onopen = () => {
      this.startPingLoop()
      this.startTalkWatchdog()
    }
    dc.onclose = () => {
      if (this.dc === dc) this.dc = null
      this.stopPingLoop()
    }
    dc.onmessage = (ev) => {
      this.handleCtrlMessage(ev.data)
    }
  }

  private async makeOffer() {
    const pc = this.pc
    if (!pc || !this.peer) return
    try {
      const offer = await pc.createOffer()
      offer.sdp = tuneOpusSdp(offer.sdp ?? '')
      await pc.setLocalDescription(offer)
      this.socket?.emit('signal', {
        to: this.peer.deviceId,
        data: { kind: 'offer', sdp: offer.sdp } satisfies RtcSignal,
      })
    } catch (err) {
      console.error('makeOffer failed', err)
    }
  }

  private async handleSignal(data: RtcSignal) {
    try {
      if (data.kind === 'offer') {
        this.pendingOfferFrom = null
        if (!this.pc) await this.createPeerConnection(false)
        const pc = this.pc!
        await pc.setRemoteDescription(
          new RTCSessionDescription({ type: 'offer', sdp: data.sdp }),
        )
        const answer = await pc.createAnswer()
        answer.sdp = tuneOpusSdp(answer.sdp ?? '')
        await pc.setLocalDescription(answer)
        if (this.peer) {
          this.socket?.emit('signal', {
            to: this.peer.deviceId,
            data: { kind: 'answer', sdp: answer.sdp } satisfies RtcSignal,
          })
        }
      } else if (data.kind === 'answer') {
        const pc = this.pc
        if (!pc) return
        if (pc.signalingState === 'have-local-offer') {
          await pc.setRemoteDescription(
            new RTCSessionDescription({ type: 'answer', sdp: data.sdp }),
          )
        }
      } else if (data.kind === 'ice') {
        const pc = this.pc
        if (!pc) return
        await pc.addIceCandidate(new RTCIceCandidate(data.candidate))
      }
    } catch (err) {
      console.warn('signal handling warning', err)
    }
  }

  private tryIceRestart() {
    const pc = this.pc
    const store = useWalkie.getState()
    if (!pc || !store.session) return
    if (this.iceRestartAttempts >= 3) {
      this.teardownSession(false)
      sfxStatic()
      return
    }
    this.iceRestartAttempts++
    useWalkie.getState().patchSession({ state: 'reconnecting' })
    try {
      pc.restartIce()
      // إرسال عرض جديد مع ICE restart
      if (pc.signalingState === 'stable') {
        this.makeOffer()
      }
    } catch {
      /* تجاهل */
    }
  }

  /* ---------------------- الصوت البعيد ---------------------- */

  private setupRemoteAudio(stream: MediaStream) {
    if (!this.audioEl) {
      const el = document.createElement('audio')
      el.autoplay = true
      el.setAttribute('playsinline', 'true')
      el.style.position = 'fixed'
      el.style.opacity = '0'
      el.style.pointerEvents = 'none'
      el.style.width = '1px'
      el.style.height = '1px'
      el.id = 'walkie-remote-audio'
      document.body.appendChild(el)
      this.audioEl = el
    }
    this.audioEl.srcObject = stream
    this.audioEl.play().catch(() => {
      // سيُعاد المحاولة عند أول تفاعل
      const retry = () => {
        this.audioEl?.play().catch(() => {})
        window.removeEventListener('pointerdown', retry)
      }
      window.addEventListener('pointerdown', retry, { once: true })
    })

    // محلل للتصوّر الصوتي البعيد + كشف الحديث كاحتياط
    const actx = this.audioCtx ?? initAudioContext()
    if (actx) {
      this.audioCtx = actx
      try {
        const src = actx.createMediaStreamSource(stream)
        const analyser = actx.createAnalyser()
        analyser.fftSize = 256
        analyser.smoothingTimeConstant = 0.75
        src.connect(analyser)
        this.remoteAnalyser = analyser
      } catch {
        /* تجاهل */
      }
    }

    // تقليل زمن التشغيل المؤجل لأدنى حد (Chrome)
    for (const receiver of this.pc?.getReceivers() ?? []) {
      try {
        ;(receiver as any).playoutDelayHint = 0.0
      } catch {
        /* غير مدعوم */
      }
    }
  }

  /* ---------------------- زر الحديث PTT ---------------------- */

  async pttDown() {
    const store = useWalkie.getState()
    const session = store.session
    if (!session || session.state !== 'connected') return
    if (session.talkingLocal) return
    if (session.talkingRemote) {
      // القناة مشغولة — سلوك اللاسلكي الحقيقي
      sfxBusy()
      vibrate([60, 40, 60])
      return
    }
    const ok = await this.ensureMic()
    if (!ok) return

    this.talkingLocal = true
    if (this.localTrack) this.localTrack.enabled = true
    this.talkStartedAt = Date.now()

    this.sendCtrl({ t: 'talk-on' })
    if (this.peer)
      this.socket?.emit('talk-state', { to: this.peer.deviceId, talking: true })

    sfxTalkOn()
    vibrate(12)
    useWalkie.getState().patchSession({ talkingLocal: true })
  }

  pttUp() {
    const session = useWalkie.getState().session
    if (!session || !this.talkingLocal) return

    this.talkingLocal = false
    if (this.localTrack) this.localTrack.enabled = false

    this.sendCtrl({ t: 'talk-off' })
    if (this.peer)
      this.socket?.emit('talk-state', { to: this.peer.deviceId, talking: false })

    sfxTalkOff()
    vibrate(6)
    useWalkie.getState().patchSession({ talkingLocal: false })
  }

  private talkingLocal = false

  private startTalkWatchdog() {
    this.stopTalkWatchdog()
    this.talkWatchdog = setInterval(() => {
      if (this.talkingLocal && Date.now() - this.talkStartedAt > MAX_TALK_MS) {
        this.pttUp()
      }
    }, 1000)
  }
  private stopTalkWatchdog() {
    if (this.talkWatchdog) clearInterval(this.talkWatchdog)
    this.talkWatchdog = null
  }

  /* ---------------------- قناة التحكم ---------------------- */

  private sendCtrl(msg: Record<string, unknown>) {
    if (this.dc?.readyState === 'open') {
      try {
        this.dc.send(JSON.stringify(msg))
      } catch {
        /* تجاهل */
      }
    }
  }

  private handleCtrlMessage(raw: string) {
    try {
      const msg = JSON.parse(raw)
      if (msg.t === 'talk-on') this.setRemoteTalking(true)
      else if (msg.t === 'talk-off') this.setRemoteTalking(false)
      else if (msg.t === 'ping') {
        this.sendCtrl({ t: 'pong', ts: msg.ts })
      } else if (msg.t === 'pong') {
        const rtt = Math.max(0, Date.now() - msg.ts)
        useWalkie
          .getState()
          .patchSession({ latencyMs: rtt, quality: qualityOf(rtt) })
      }
    } catch {
      /* تجاهل */
    }
  }

  private setRemoteTalking(talking: boolean) {
    const session = useWalkie.getState().session
    if (!session) return
    if (talking && !session.talkingRemote) {
      sfxRemoteTalk()
      vibrate(8)
    }
    useWalkie.getState().patchSession({ talkingRemote: talking })
  }

  private pingTimer: ReturnType<typeof setInterval> | null = null
  private startPingLoop() {
    this.stopPingLoop()
    this.pingTimer = setInterval(() => {
      this.sendCtrl({ t: 'ping', ts: Date.now() })
    }, 2000)
  }
  private stopPingLoop() {
    if (this.pingTimer) clearInterval(this.pingTimer)
    this.pingTimer = null
  }

  /* ---------------------- التنظيف ---------------------- */

  private teardownSession(playSound: boolean) {
    this.clearTimer(this.callTimeout)
    this.clearTimer(this.reconnectTimer)
    this.clearTimer(this.autoReconnectTimer)
    this.stopRingingLoop()
    this.stopPingLoop()
    this.stopTalkWatchdog()
    this.releaseWakeLock()

    if (this.peer) this.socket?.emit('presence-update', { busy: false, talking: false })

    this.talkingLocal = false
    if (this.localTrack) this.localTrack.enabled = false

    try {
      this.dc?.close()
    } catch { /* تجاهل */ }
    this.dc = null

    try {
      this.pc?.close()
    } catch { /* تجاهل */ }
    this.pc = null

    if (this.audioEl) this.audioEl.srcObject = null
    this.remoteStream = null
    this.remoteAnalyser = null
    this.peer = null
    this.iceRestartAttempts = 0

    const store = useWalkie.getState()
    if (store.session) store.setSession(null)
    if (playSound) sfxDisconnected()
  }

  /* ---------------------- رنين ---------------------- */

  private startRingingLoop(incoming: boolean) {
    this.stopRingingLoop()
    if (incoming) {
      this.ringingInterval = setInterval(() => sfxIncomingLoop(), 1800)
      sfxIncomingLoop()
    } else {
      // نغمة انتظار خافتة جدًا للاتصال الصادر
      this.ringingInterval = setInterval(() => sfxIncomingLoop(), 3000)
    }
  }
  private stopRingingLoop() {
    if (this.ringingInterval) clearInterval(this.ringingInterval)
    this.ringingInterval = null
  }

  /* ---------------------- Wake Lock ---------------------- */

  private async requestWakeLock() {
    try {
      if ('wakeLock' in navigator && !this.wakeLock) {
        this.wakeLock = await (navigator as any).wakeLock.request('screen')
        this.wakeLock.addEventListener('release', () => {
          this.wakeLock = null
        })
      }
    } catch {
      /* غير مدعوم */
    }
  }
  private releaseWakeLock() {
    try {
      this.wakeLock?.release()
    } catch { /* تجاهل */ }
    this.wakeLock = null
  }

  /* ---------------------- أدوات ---------------------- */

  private clearTimer(t: ReturnType<typeof setTimeout> | null) {
    if (t) clearTimeout(t)
  }

  private setBusy(busy: boolean) {
    this.socket?.emit('presence-update', { busy, talking: false })
  }

  /** حفظ هوية جديدة (من الإعدادات) */
  saveIdentity(identity: Identity) {
    this.identity = identity
    saveIdentity(identity, isAsNewMode())
    useWalkie.getState().setIdentity(identity)
    this.socket?.emit('hello', {
      deviceId: identity.deviceId,
      name: identity.name,
      avatar: identity.avatar,
      model: identity.model,
    })
  }

  destroy() {
    this.disposed = true
    this.teardownSession(false)
    this.socket?.disconnect()
    void this.disposed
  }
}

/** تحميل هوية محفوظة عند الإقلاع (بدون تهيئة المحرك) */
export function bootstrapIdentity(): Identity | null {
  return loadIdentity()
}

export const engine = new WalkieEngine()
