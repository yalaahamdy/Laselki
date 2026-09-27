'use client'

import { useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { ArrowLeft, Mic, Radio, ShieldCheck, Wifi } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { AvatarBadge } from './avatar-badge'
import {
  AVATARS,
  saveIdentity,
  suggestName,
  type Identity,
} from '@/lib/walkie/identity'
import { engine } from '@/lib/walkie/engine'
import { useWalkie } from '@/lib/walkie/store'
import { cn } from '@/lib/utils'

export function OnboardingView() {
  const store = useWalkie()
  const asNew = useMemo(() => new URLSearchParams(window.location.search).has('as-new'), [])
  const [identity, setIdentity] = useState<Identity>(() => ({
    deviceId: store.identity?.deviceId ?? crypto.randomUUID(),
    name: store.identity?.name ?? suggestName(),
    avatar: store.identity?.avatar ?? AVATARS[Math.floor(Math.random() * AVATARS.length)],
    model: store.identity?.model ?? '',
    createdAt: Date.now(),
  }))
  const [micStep, setMicStep] = useState<'idle' | 'granted' | 'denied'>(
    store.micGranted === true ? 'granted' : 'idle',
  )
  const nameValid = identity.name.trim().length >= 2

  const requestMic = async () => {
    const ok = await engine.ensureMic()
    setMicStep(ok ? 'granted' : 'denied')
  }

  const finish = () => {
    if (!nameValid) return
    const finalIdentity = { ...identity, name: identity.name.trim() }
    saveIdentity(finalIdentity, asNew)
    engine.init(finalIdentity)
    store.setPhase('ready')
  }

  return (
    <div className="min-h-dvh flex flex-col items-center justify-center px-5 py-8 overflow-y-auto">
      <motion.div
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: 'easeOut' }}
        className="w-full max-w-md flex flex-col items-center gap-7"
      >
        {/* الشعار */}
        <div className="flex flex-col items-center gap-3 text-center">
          <div className="relative">
            <div className="absolute -inset-3 bg-amber-500/15 blur-2xl rounded-full" />
            <div className="relative w-20 h-20 rounded-[1.6rem] bg-gradient-to-br from-amber-400 to-amber-600 flex items-center justify-center shadow-lg shadow-amber-500/25">
              <Radio className="text-white" size={40} strokeWidth={1.9} />
            </div>
          </div>
          <div>
            <h1 className="text-3xl font-extrabold tracking-tight">لاسِلكي</h1>
            <p className="text-muted-foreground mt-1 text-[15px]">
              اتصال صوتي فوري بين الهواتف عبر شبكة Wi-Fi المحلية
            </p>
          </div>
        </div>

        {/* بطاقة الهوية */}
        <div className="w-full rounded-3xl border bg-card p-5 shadow-sm space-y-5">
          <div className="space-y-2">
            <label className="text-sm font-semibold" htmlFor="device-name">
              اسم جهازك
            </label>
            <Input
              id="device-name"
              value={identity.name}
              onChange={(e) => setIdentity((s) => ({ ...s, name: e.target.value }))}
              placeholder="مثال: هاتف أحمد"
              maxLength={32}
              className="h-12 text-base rounded-xl"
              autoComplete="off"
            />
            {!nameValid && identity.name.length > 0 && (
              <p className="text-xs text-destructive">الاسم قصير جدًا (حرفان على الأقل)</p>
            )}
          </div>

          <div className="space-y-2">
            <label className="text-sm font-semibold">اختر رمزك</label>
            <div className="grid grid-cols-4 gap-2.5">
              {AVATARS.map((a) => (
                <button
                  key={a}
                  type="button"
                  onClick={() => setIdentity((s) => ({ ...s, avatar: a }))}
                  aria-label={a}
                  aria-pressed={identity.avatar === a}
                  className={cn(
                    'flex items-center justify-center rounded-2xl border-2 transition-all p-1.5',
                    identity.avatar === a
                      ? 'border-amber-500 bg-amber-500/10 scale-105 shadow-sm'
                      : 'border-transparent hover:border-border',
                  )}
                >
                  <AvatarBadge avatar={a} size={44} />
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* إذن الميكروفون */}
        <div className="w-full rounded-3xl border bg-card p-5 shadow-sm space-y-3">
          <div className="flex items-start gap-3">
            <div className="w-10 h-10 rounded-xl bg-emerald-500/12 flex items-center justify-center shrink-0">
              <Mic className="text-emerald-600 dark:text-emerald-400" size={20} />
            </div>
            <div className="space-y-1 flex-1">
              <h3 className="font-semibold text-[15px]">إذن الميكروفون</h3>
              <p className="text-[13px] leading-relaxed text-muted-foreground">
                نحتاج الميكروفون لتمرير صوتك مباشرة إلى الجهاز الآخر. الصوت ينتقل بين
                الهاتفين فقط ولا يُسجَّل ولا يُرسل إلى أي خادم.
              </p>
            </div>
          </div>
          {micStep !== 'granted' ? (
            <Button
              variant={micStep === 'denied' ? 'outline' : 'secondary'}
              className="w-full h-11 rounded-xl"
              onClick={requestMic}
            >
              {micStep === 'denied' ? 'المحاولة مرة أخرى' : 'تفعيل الميكروفون'}
            </Button>
          ) : (
            <div className="flex items-center gap-2 text-emerald-600 dark:text-emerald-400 text-sm font-medium">
              <ShieldCheck size={16} />
              الميكروفون جاهز
            </div>
          )}
          {micStep === 'denied' && (
            <p className="text-xs text-muted-foreground leading-relaxed">
              إذا رفضت الإذن يمكنك متابعة الخطوات وتفعيله لاحقًا من إعدادات المتصفح —
              لن تستطيع التحدث دونه.
            </p>
          )}
        </div>

        {/* نقاط الثقة */}
        <div className="flex items-center justify-center gap-4 text-[12px] text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <Wifi size={13} className="text-emerald-500" /> بدون إنترنت
          </span>
          <span className="flex items-center gap-1.5">
            <ShieldCheck size={13} className="text-emerald-500" /> بدون حساب
          </span>
          <span className="flex items-center gap-1.5">
            <Radio size={13} className="text-emerald-500" /> اتصال مباشر
          </span>
        </div>

        <Button
          size="lg"
          className="w-full h-14 text-lg rounded-2xl font-bold shadow-lg shadow-amber-500/20"
          disabled={!nameValid}
          onClick={finish}
        >
          ابدأ الآن
          <ArrowLeft className="ms-1" size={20} />
        </Button>
      </motion.div>
    </div>
  )
}
