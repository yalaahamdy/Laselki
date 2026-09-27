'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { PhoneOff, RotateCw, SignalHigh, SignalLow, SignalMedium } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { AudioVisualizer } from './audio-visualizer'
import { AvatarBadge } from './avatar-badge'
import { PttButton } from './ptt-button'
import { engine } from '@/lib/walkie/engine'
import { useWalkie } from '@/lib/walkie/store'
import type { SessionInfo } from '@/lib/walkie/store'
import { cn } from '@/lib/utils'

function useElapsed(startedAt: number | null, tick: boolean) {
  const [, force] = useState(0)
  useEffect(() => {
    if (!tick) return
    const t = setInterval(() => force((n) => n + 1), 1000)
    return () => clearInterval(t)
  }, [tick])
  if (!startedAt) return null
  const s = Math.max(0, Math.floor((Date.now() - startedAt) / 1000))
  const m = Math.floor(s / 60)
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

function StatusChip({ session }: { session: SessionInfo }) {
  const peerName = session.peer.name

  const map = {
    calling: { text: `جارٍ الاتصال بـ${peerName}…`, cls: 'bg-amber-500/15 text-amber-700 dark:text-amber-400', dot: 'animate-ping bg-amber-500' },
    connecting: { text: 'جارٍ إنشاء القناة…', cls: 'bg-amber-500/15 text-amber-700 dark:text-amber-400', dot: 'animate-pulse bg-amber-500' },
    connected: session.talkingLocal
      ? { text: 'أنت تتحدث', cls: 'bg-amber-500 text-amber-950 shadow-md shadow-amber-500/30', dot: 'bg-amber-900' }
      : session.talkingRemote
        ? { text: `${peerName} يتحدث`, cls: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400', dot: 'animate-pulse bg-emerald-500' }
        : { text: 'متصل — القناة مفتوحة', cls: 'bg-emerald-500/12 text-emerald-700 dark:text-emerald-400', dot: 'bg-emerald-500' },
    reconnecting: { text: 'انقطع الاتصال — جارٍ الاستعادة…', cls: 'bg-orange-500/15 text-orange-700 dark:text-orange-400', dot: 'animate-ping bg-orange-500' },
    failed: { text: 'تعذّر الاتصال', cls: 'bg-destructive/12 text-destructive', dot: 'bg-destructive' },
  } as const

  const s = map[session.state]
  return (
    // بلا mode="wait": الانتقالات المتسلسلة تتجمد في التبويبات الخلفية
    // (Chromium يعلّق rAF) فيبقى النص القديم عالقًا — التزامن أأمن هنا
    <AnimatePresence initial={false}>
      <motion.div
        key={s.text}
        initial={{ opacity: 0, y: 6, scale: 0.96 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={{ opacity: 0, y: -6, scale: 0.96 }}
        transition={{ duration: 0.18 }}
        className={cn(
          'flex items-center gap-2 px-4 py-2 rounded-full text-sm font-bold',
          s.cls,
        )}
        role="status"
        aria-live="polite"
      >
        <span className={cn('w-2 h-2 rounded-full', s.dot)} />
        {s.text}
      </motion.div>
    </AnimatePresence>
  )
}

function QualityChip({ session }: { session: SessionInfo }) {
  const { latencyMs, quality, state } = session
  if (state !== 'connected' && state !== 'reconnecting') return null
  const Icon =
    quality === 'excellent' ? SignalHigh : quality === 'good' ? SignalMedium : SignalLow
  const label =
    quality === 'excellent'
      ? 'ممتاز'
      : quality === 'good'
        ? 'جيد'
        : quality === 'weak'
          ? 'ضعيف'
          : '…'
  return (
    <div className="flex items-center gap-1.5 text-[11px] font-semibold text-muted-foreground bg-muted/60 px-2.5 py-1 rounded-full">
      <Icon size={12} className={cn(quality === 'weak' && 'text-orange-500')} />
      {latencyMs != null ? `${latencyMs} مللي ثانية` : ''} · {label}
    </div>
  )
}

export function SessionView() {
  // قد تصبح الجلسة null أثناء انتقال الخروج — كل الـ hooks قبل أي return
  const session = useWalkie((s) => s.session)
  const connected = session?.state === 'connected'
  const elapsed = useElapsed(session?.startedAt ?? null, connected)

  // مسافة لوحة المفاتيح = زر الحديث (لسطح المكتب)
  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !e.repeat && !(e.target instanceof HTMLInputElement) && !(e.target instanceof HTMLTextAreaElement)) {
        e.preventDefault()
        void engine.pttDown()
      }
    }
    const up = (e: KeyboardEvent) => {
      if (e.code === 'Space') {
        e.preventDefault()
        engine.pttUp()
      }
    }
    window.addEventListener('keydown', down)
    window.addEventListener('keyup', up)
    return () => {
      window.removeEventListener('keydown', down)
      window.removeEventListener('keyup', up)
    }
  }, [])

  const talkingLocal = session?.talkingLocal ?? false
  const talkingRemote = session?.talkingRemote ?? false

  const visualizerColor = useMemo(
    () => (talkingLocal ? '#F59E0B' : talkingRemote ? '#10B981' : '#A1A1AA'),
    [talkingLocal, talkingRemote],
  )

  // استدعاء صريح مربوط — لا تمرير مرجع الدالة الخام من المحرك أبدًا
  // (كان هذا سبب انهيار "Cannot read properties of undefined")
  const getAnalyser = useCallback(
    () =>
      talkingRemote ? engine.getRemoteAnalyser() : engine.getLocalAnalyser(),
    [talkingRemote],
  )

  // حماية صريحة بعد كل الـ hooks
  if (!session) return null

  const ended = session.state === 'failed'
  const endedMessage =
    session.declineReason === 'declined'
      ? `${session.peer.name} رفض الاتصال`
      : session.declineReason === 'busy'
        ? `${session.peer.name} في محادثة أخرى الآن`
        : session.declineReason === 'timeout'
          ? 'لم يجب الطرف الآخر'
          : session.declineReason === 'peer-offline'
            ? 'الجهاز غير متاح على الشبكة'
            : 'تعذّر إنشاء الاتصال'

  return (
    <div className="min-h-dvh flex flex-col bg-gradient-to-b from-background via-background to-muted/40">
      {/* الشريط العلوي */}
      <header className="flex items-center justify-between px-4 pt-[max(env(safe-area-inset-top),0.9rem)] pb-2">
        <Button
          variant="ghost"
          size="icon"
          aria-label="إنهاء الاتصال"
          onClick={() => engine.endSession()}
          className="rounded-full w-11 h-11 bg-destructive/10 text-destructive hover:bg-destructive/20 hover:text-destructive"
        >
          <PhoneOff size={20} />
        </Button>
        <div className="flex items-center gap-2">
          {elapsed && (
            <span className="text-[13px] font-bold text-muted-foreground tabular-nums bg-muted/60 px-2.5 py-1 rounded-full" dir="ltr">
              {elapsed}
            </span>
          )}
          <QualityChip session={session} />
        </div>
      </header>

      {/* منطقة الطرف الآخر */}
      <main className="flex-1 flex flex-col items-center justify-between px-6 pb-[max(env(safe-area-inset-bottom),1.4rem)] pt-2 max-w-lg mx-auto w-full">
        <div className="flex flex-col items-center gap-4 w-full mt-2">
          <div className="relative">
            {session.state === 'calling' && (
              <span className="absolute -inset-2 rounded-[2rem] border-2 border-amber-500/40 animate-radar" />
            )}
            <AvatarBadge
              avatar={session.peer.avatar}
              size={96}
              talking={talkingRemote || talkingLocal ? talkingRemote : false}
            />
          </div>

          <div className="text-center space-y-1">
            <h2 className="text-2xl font-extrabold tracking-tight">
              {session.peer.name}
            </h2>
            <p className="text-xs text-muted-foreground">{session.peer.model}</p>
          </div>

          <StatusChip session={session} />
        </div>

        {/* الموجة الصوتية */}
        <div className="w-full h-28 my-2">
          <AudioVisualizer
            getAnalyser={getAnalyser}
            active={connected && (talkingLocal || talkingRemote)}
            color={visualizerColor}
          />
        </div>

        {/* حالة الفشل */}
        {ended && (
          <motion.div
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            className="w-full rounded-2xl border bg-card p-4 text-center space-y-3 mb-3"
          >
            <p className="text-sm font-semibold">{endedMessage}</p>
            <div className="flex gap-2 justify-center">
              <Button
                className="rounded-xl h-11 px-5"
                onClick={() => void engine.call(session.peer)}
              >
                <RotateCw size={16} className="ms-0.5" />
                إعادة المحاولة
              </Button>
              <Button
                variant="outline"
                className="rounded-xl h-11 px-5"
                onClick={() => engine.endSession()}
              >
                رجوع
              </Button>
            </div>
          </motion.div>
        )}

        {/* زر الحديث */}
        <div className="w-full flex flex-col items-center gap-4">
          <PttButton
            disabled={session.state !== 'connected'}
            talkingLocal={talkingLocal}
            talkingRemote={talkingRemote}
            connected={connected}
          />
          <p className="text-[12px] text-muted-foreground text-center leading-relaxed">
            {connected ? (
              <>
                اضغط باستمرار على الزر للتحدث وارفع إصبعك للإنصات
                <span className="hidden sm:inline"> — أو استخدم مفتاح المسافة</span>
              </>
            ) : session.state === 'reconnecting'
              ? 'نستعيد الاتصال تلقائيًا بمجرد استقرار الشبكة…'
              : session.state === 'calling'
                ? 'بانتظار موافقة الطرف الآخر…'
                : 'لحظات ويصبح الاتصال جاهزًا…'}
          </p>
        </div>
      </main>
    </div>
  )
}
