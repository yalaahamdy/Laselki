'use client'

import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import {
  ChevronLeft,
  Info,
  MicOff,
  Plus,
  QrCode,
  Radio,
  RefreshCw,
  Settings2,
  Signal,
  Wifi,
  WifiOff,
} from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { AvatarBadge } from './avatar-badge'
import { JoinDialog } from './join-dialog'
import { engine } from '@/lib/walkie/engine'
import { useWalkie } from '@/lib/walkie/store'
import { cn } from '@/lib/utils'

function NetworkChip() {
  const { signalConnected, networkOnline } = useWalkie()
  const ok = signalConnected && networkOnline
  return (
    <div
      className={cn(
        'flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-semibold transition-colors',
        ok
          ? 'bg-emerald-500/12 text-emerald-700 dark:text-emerald-400'
          : 'bg-destructive/10 text-destructive',
      )}
      role="status"
    >
      {ok ? <Wifi size={13} /> : <WifiOff size={13} />}
      {ok ? 'الشبكة المحلية' : networkOnline ? 'جارٍ البحث…' : 'لا اتصال بالشبكة'}
    </div>
  )
}

function DeviceRow({
  device,
  index,
  isMe,
}: {
  device: {
    deviceId: string
    name: string
    avatar: string
    model: string
    busy: boolean
    talking: boolean
  }
  index: number
  isMe?: boolean
}) {
  const session = useWalkie((s) => s.session)
  const inSession = !!session
  const available = !device.busy && !device.talking

  return (
    <motion.div
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, scale: 0.96 }}
      transition={{ duration: 0.25, delay: Math.min(index * 0.05, 0.3) }}
      layout
    >
      <button
        type="button"
        disabled={isMe || inSession}
        onClick={() => !isMe && !inSession && void engine.call(device)}
        className={cn(
          'w-full flex items-center gap-3.5 p-3.5 rounded-2xl border bg-card text-right transition-all',
          !isMe && available && !inSession
            ? 'hover:border-amber-500/40 hover:bg-accent/50 active:scale-[0.99] cursor-pointer'
            : 'opacity-70 cursor-default',
        )}
      >
        <AvatarBadge avatar={device.avatar} size={48} talking={device.talking} />

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-bold text-[15px] truncate">{device.name}</span>
            {isMe && (
              <Badge variant="secondary" className="text-[10px] px-1.5 py-0 rounded-md bg-amber-500/15 text-amber-700 dark:text-amber-400 border-0">
                أنت
              </Badge>
            )}
          </div>
          <div className="flex items-center gap-1.5 mt-0.5">
            {device.talking ? (
              <span className="text-xs text-emerald-600 dark:text-emerald-400 font-medium flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
                يتحدث الآن…
              </span>
            ) : device.busy ? (
              <span className="text-xs text-muted-foreground">في محادثة أخرى</span>
            ) : isMe ? (
              <span className="text-xs text-muted-foreground truncate">{device.model}</span>
            ) : (
              <span className="text-xs text-muted-foreground truncate">
                {device.model || 'جهاز على الشبكة'} · متاح
              </span>
            )}
          </div>
        </div>

        {!isMe && (
          <div
            className={cn(
              'w-9 h-9 rounded-full flex items-center justify-center shrink-0 transition-colors',
              available && !inSession
                ? 'bg-amber-500/12 text-amber-600 dark:text-amber-400'
                : 'bg-muted text-muted-foreground/50',
            )}
          >
            <ChevronLeft size={18} />
          </div>
        )}
      </button>
    </motion.div>
  )
}

function EmptyState() {
  const { signalConnected, networkOnline } = useWalkie()
  const searching = signalConnected && networkOnline
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="flex flex-col items-center text-center gap-4 py-10 px-4"
    >
      {/* رادار بحث */}
      <div className="relative w-28 h-28 flex items-center justify-center">
        {searching && (
          <>
            <span className="absolute inset-0 rounded-full border-2 border-amber-500/30 animate-radar" />
            <span
              className="absolute inset-0 rounded-full border-2 border-amber-500/20 animate-radar"
              style={{ animationDelay: '1.2s' }}
            />
          </>
        )}
        <div className="w-16 h-16 rounded-3xl bg-muted flex items-center justify-center">
          <Radio
            size={30}
            className={cn(
              searching ? 'text-amber-500 animate-breathe' : 'text-muted-foreground',
            )}
          />
        </div>
      </div>
      <div className="space-y-1.5 max-w-[280px]">
        <h3 className="font-bold text-[15px]">
          {searching
            ? 'لم يتم العثور على أجهزة على شبكة Wi-Fi الحالية'
            : 'لا يوجد اتصال بالشبكة المحلية'}
        </h3>
        <p className="text-[13px] leading-relaxed text-muted-foreground">
          {searching
            ? 'افتح التطبيق على الهاتف الآخر وتأكد من اتصاله بنفس شبكة Wi-Fi — سيظهر هنا تلقائيًا.'
            : 'تحقق من اتصال هاتفك بشبكة Wi-Fi ثم سنعيد البحث تلقائيًا.'}
        </p>
      </div>
    </motion.div>
  )
}

export function HomeView() {
  const store = useWalkie()
  const { identity, devices, session, micGranted, incomingFrom } = store
  const [joinOpen, setJoinOpen] = useState(false)
  const me = devices.find((d) => d.deviceId === identity?.deviceId)
  const others = devices.filter((d) => d.deviceId !== identity?.deviceId)
  const hidden = !!session || !!incomingFrom

  const retryMic = () => void engine.ensureMic()

  return (
    <div className="min-h-dvh flex flex-col">
      {/* الشريط العلوي */}
      <header className="sticky top-0 z-20 backdrop-blur-xl bg-background/70 border-b">
        <div className="max-w-lg mx-auto flex items-center justify-between px-4 h-16">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-amber-400 to-amber-600 flex items-center justify-center shadow-sm">
              <Radio className="text-white" size={19} strokeWidth={2} />
            </div>
            <div className="leading-none">
              <h1 className="font-extrabold text-[17px]">لاسِلكي</h1>
              <p className="text-[11px] text-muted-foreground mt-0.5">
                اتصال فوري · بدون إنترنت
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <NetworkChip />
            <Button
              variant="ghost"
              size="icon"
              className="rounded-xl"
              aria-label="انضم من جهاز آخر"
              onClick={() => setJoinOpen(true)}
            >
              <QrCode size={20} />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="rounded-xl"
              aria-label="الإعدادات"
              onClick={() => useWalkie.getState().setSettingsOpen(true)}
            >
              <Settings2 size={20} />
            </Button>
          </div>
        </div>
      </header>

      <main className="flex-1 w-full max-w-lg mx-auto px-4 pb-40 pt-5 space-y-6">
        {/* تنبيه الميكروفون */}
        {micGranted === false && (
          <motion.div
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            className="flex items-center gap-3 p-3.5 rounded-2xl bg-destructive/10 border border-destructive/20"
          >
            <MicOff className="text-destructive shrink-0" size={20} />
            <div className="flex-1 text-[13px] leading-relaxed">
              <span className="font-bold text-destructive">الميكروفون غير مفعّل.</span>{' '}
              <span className="text-muted-foreground">
                اسمح بالوصول من إعدادات المتصفح لتتمكن من التحدث.
              </span>
            </div>
            <Button size="sm" variant="outline" className="rounded-lg shrink-0" onClick={retryMic}>
              تفعيل
            </Button>
          </motion.div>
        )}

        {/* جهازي */}
        <section aria-label="جهازي">
          {me ? (
            <DeviceRow device={me} index={0} isMe />
          ) : identity ? (
            <DeviceRow
              device={{
                deviceId: identity.deviceId,
                name: identity.name,
                avatar: identity.avatar,
                model: identity.model,
                busy: false,
                talking: false,
              }}
              index={0}
              isMe
            />
          ) : null}
        </section>

        {/* الأجهزة القريبة */}
        <section aria-label="الأجهزة القريبة" className="space-y-3">
          <div className="flex items-center justify-between px-1">
            <h2 className="font-bold text-[15px] flex items-center gap-2">
              <Signal size={16} className="text-amber-500" />
              الأجهزة القريبة
            </h2>
            {others.length > 0 && (
              <span className="text-xs text-muted-foreground font-medium">
                {others.length} {others.length === 1 ? 'جهاز' : 'أجهزة'}
              </span>
            )}
          </div>

          <div className="space-y-2.5 min-h-[200px]">
            <AnimatePresence mode="popLayout">
              {others.length === 0 ? (
                <EmptyState key="empty" />
              ) : (
                others.map((d, i) => (
                  <DeviceRow key={d.deviceId} device={d} index={i} />
                ))
              )}
            </AnimatePresence>
          </div>
        </section>

        {/* تلميح الخصوصية */}
        <div className="flex items-center justify-center gap-2 text-[12px] text-muted-foreground pt-2">
          <Info size={13} />
          الصوت ينتقل مباشرة بين الأجهزة — لا يمر عبر أي خادم أو إنترنت
        </div>
      </main>

      {/* شريط سفلي: زر التجربة الثنائية */}
      <footer className="mt-auto sticky bottom-0 z-10 bg-gradient-to-t from-background via-background to-transparent pt-6 pb-[max(env(safe-area-inset-bottom),1rem)]">
        <div className="max-w-lg mx-auto px-4 space-y-2">
          <Button
            variant="outline"
            className="w-full h-12 rounded-2xl text-sm font-semibold border-dashed"
            onClick={() => setJoinOpen(true)}
          >
            <QrCode size={16} className="ms-0.5" />
            انضم من هاتف أو كمبيوتر آخر عبر رمز QR
          </Button>
          <Button
            variant="ghost"
            className="w-full h-10 rounded-2xl text-[13px] font-medium text-muted-foreground"
            onClick={() => window.open(`${location.pathname}?as-new=1`, '_blank')}
          >
            <Plus size={15} className="ms-0.5" />
            جرّب الآن: افتح جهازًا ثانيًا في نافذة جديدة
          </Button>
        </div>
      </footer>

      <JoinDialog open={joinOpen} onOpenChange={setJoinOpen} />
    </div>
  )
}
