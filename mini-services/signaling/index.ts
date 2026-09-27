/**
 * Walkie-Talkie Signaling & Presence Service
 * -------------------------------------------
 * مسؤول فقط عن:
 *  1) اكتشاف الأجهزة على الشبكة المحلية (الحضور Presence)
 *  2) تمرير إشارات WebRTC (offer / answer / ice) بين الأجهزة
 *  3) طلبات الاتصال ونظام الأذونات
 *  4) حالات الحديث (احتياط عند تعذر DataChannel)
 *
 * الصوت نفسه لا يمر من هنا إطلاقًا — يتدفق مباشرة بين الأجهزة (P2P).
 */
import { createServer } from 'http'
import { Server, type Socket } from 'socket.io'

const PORT = 3003

interface DeviceProfile {
  deviceId: string
  name: string
  avatar: string
  model: string
}

interface DeviceEntry {
  profile: DeviceProfile
  socketId: string
  busy: boolean
  talking: boolean
  joinedAt: number
}

interface PublicDevice extends DeviceProfile {
  busy: boolean
  talking: boolean
  joinedAt: number
}

const devices = new Map<string, DeviceEntry>() // key: deviceId

const publicList = (): PublicDevice[] =>
  Array.from(devices.values()).map((d) => ({
    deviceId: d.profile.deviceId,
    name: d.profile.name,
    avatar: d.profile.avatar,
    model: d.profile.model,
    busy: d.busy,
    talking: d.talking,
    joinedAt: d.joinedAt,
  }))

const broadcastDevices = (io: Server) => {
  io.emit('devices', { devices: publicList() })
}

const sanitizeProfile = (raw: unknown): DeviceProfile | null => {
  if (!raw || typeof raw !== 'object') return null
  const p = raw as Record<string, unknown>
  const deviceId = typeof p.deviceId === 'string' ? p.deviceId.slice(0, 64) : ''
  const name = typeof p.name === 'string' ? p.name.slice(0, 32).trim() : ''
  const avatar = typeof p.avatar === 'string' ? p.avatar.slice(0, 24) : 'radio'
  const model = typeof p.model === 'string' ? p.model.slice(0, 48) : ''
  if (!deviceId || !name) return null
  return { deviceId, name, avatar, model }
}

const httpServer = createServer((req, res) => {
  // فحص صحة بسيط
  res.writeHead(200, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ ok: true, service: 'walkie-signaling', devices: devices.size }))
})

const io = new Server(httpServer, {
  // DO NOT change the path, it is used by Caddy to forward the request to the correct port
  path: '/',
  cors: { origin: '*', methods: ['GET', 'POST'] },
  pingTimeout: 20000,
  pingInterval: 10000,
  maxHttpBufferSize: 1e5,
})

// عدم إسقاط العملية إذا كان المنفذ مشغولًا (تشغيل نسختين مثلًا في تطبيق ويندوز)
httpServer.on('error', (err: NodeJS.ErrnoException) => {
  if (err.code === 'EADDRINUSE') {
    console.log(`⚠️ Port ${PORT} in use — another signaling instance is running`)
    return
  }
  console.error('signaling http error:', err.message)
})

io.on('connection', (socket: Socket) => {
  let myDeviceId: string | null = null

  const getEntry = (): DeviceEntry | null =>
    myDeviceId ? devices.get(myDeviceId) ?? null : null

  socket.on('hello', (raw: unknown) => {
    const profile = sanitizeProfile(raw)
    if (!profile) {
      socket.emit('hello-error', { reason: 'invalid-profile' })
      return
    }

    // إذا كان نفس الجهاز متصلاً من مقبس قديم (تحديث الصفحة مثلًا) → استبدله
    const existing = devices.get(profile.deviceId)
    if (existing && existing.socketId !== socket.id) {
      io.to(existing.socketId).emit('replaced')
      io.sockets.sockets.get(existing.socketId)?.disconnect(true)
    }

    myDeviceId = profile.deviceId
    socket.join(profile.deviceId)

    devices.set(profile.deviceId, {
      profile,
      socketId: socket.id,
      busy: false,
      talking: false,
      joinedAt: Date.now(),
    })

    socket.emit('welcome', { socketId: socket.id, devices: publicList() })
    broadcastDevices(io)
    console.log(`[+] ${profile.name} (${profile.deviceId.slice(0, 8)}) — total: ${devices.size}`)
  })

  // تحديث حالة الحضور (مشغول / يتحدث الآن)
  socket.on('presence-update', (data: { busy?: boolean; talking?: boolean }) => {
    const entry = getEntry()
    if (!entry) return
    if (typeof data?.busy === 'boolean') entry.busy = data.busy
    if (typeof data?.talking === 'boolean') entry.talking = data.talking
    broadcastDevices(io)
  })

  // طلب اتصال (نظام الأذونات)
  socket.on('call-request', (data: { to: string; from?: DeviceProfile }) => {
    const me = getEntry()
    if (!me || !data?.to) return
    const target = devices.get(data.to)
    if (!target) {
      socket.emit('call-error', { to: data.to, reason: 'peer-offline' })
      return
    }
    io.to(target.socketId).emit('call-request', { from: me.profile })
    console.log(`[call] ${me.profile.name} -> ${target.profile.name}`)
  })

  // رد على طلب الاتصال
  socket.on(
    'call-response',
    (data: { to: string; accepted: boolean; reason?: string }) => {
      const me = getEntry()
      if (!me || !data?.to) return
      const target = devices.get(data.to)
      if (!target) {
        socket.emit('call-error', { to: data.to, reason: 'peer-offline' })
        return
      }
      io.to(target.socketId).emit('call-response', {
        accepted: !!data.accepted,
        reason: data.reason,
        from: me.profile,
      })
      console.log(
        `[resp] ${me.profile.name} -> ${target.profile.name}: ${
          data.accepted ? 'ACCEPT' : 'DECLINE'
        }`,
      )
    }
  )

  // إنهاء الاتصال
  socket.on('call-end', (data: { to: string }) => {
    const me = getEntry()
    if (!me || !data?.to) return
    const target = devices.get(data.to)
    if (target) io.to(target.socketId).emit('call-ended', { from: me.profile })
  })

  // تمرير إشارات WebRTC (offer / answer / ice)
  socket.on('signal', (data: { to: string; data?: unknown }) => {
    const me = getEntry()
    if (!me || !data?.to || !data.data) return
    const target = devices.get(data.to)
    if (!target) {
      socket.emit('signal-error', { reason: 'peer-offline' })
      return
    }
    io.to(target.socketId).emit('signal', { from: me.profile, data: data.data })
  })

  // حالة الحديث (احتياط)
  socket.on('talk-state', (data: { to: string; talking: boolean }) => {
    const me = getEntry()
    if (!me || !data?.to) return
    const target = devices.get(data.to)
    if (target) {
      io.to(target.socketId).emit('talk-state', {
        from: me.profile,
        talking: !!data.talking,
      })
    }
  })

  // جلب القائمة عند الطلب
  socket.on('list-devices', () => {
    socket.emit('devices', { devices: publicList() })
  })

  socket.on('disconnect', (reason) => {
    const entry = getEntry()
    if (!entry) return
    // قد يكون هذا المقبس قديمًا وقد استُبدل — تحقق قبل الحذف
    const current = devices.get(entry.profile.deviceId)
    if (current && current.socketId === socket.id) {
      devices.delete(entry.profile.deviceId)
      console.log(`[-] ${entry.profile.name} (${reason}) — total: ${devices.size}`)
      broadcastDevices(io)
    }
  })

  socket.on('error', (err) => {
    console.error(`[err] ${socket.id}:`, err?.message ?? err)
  })
})

httpServer.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Walkie signaling service on :${PORT}`)
})

process.on('SIGTERM', () => {
  httpServer.close(() => process.exit(0))
})
process.on('SIGINT', () => {
  httpServer.close(() => process.exit(0))
})
