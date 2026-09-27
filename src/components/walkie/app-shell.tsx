'use client'

import { useEffect } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { engine } from '@/lib/walkie/engine'
import { loadIdentity } from '@/lib/walkie/identity'
import { useWalkie } from '@/lib/walkie/store'
import { HomeView } from './home-view'
import { IncomingDialog } from './incoming-dialog'
import { OnboardingView } from './onboarding'
import { PwaRegister } from './pwa-register'
import { SessionView } from './session-view'
import { SettingsSheet } from './settings-sheet'

export function AppShell() {
  const { phase, session, incomingFrom, identity } = useWalkie()

  // الإقلاع: تحميل الهوية المحفوظة أو الانتقال لشاشة الترحيب
  useEffect(() => {
    if (phase !== 'boot') return
    const saved = loadIdentity()
    if (saved) {
      useWalkie.getState().setIdentity(saved)
    } else {
      useWalkie.getState().setPhase('onboarding')
    }
  }, [phase])

  // تهيئة المحرك مرة واحدة عند توفر الهوية
  useEffect(() => {
    if (!identity || phase !== 'boot') return
    engine.init(identity)
    useWalkie.getState().setPhase('ready')

    // تحقق صامت من حالة إذن الميكروفون
    if (navigator.permissions) {
      navigator.permissions
        .query({ name: 'microphone' as PermissionName })
        .then((res) => {
          if (res.state === 'granted') {
            useWalkie.getState().setMicGranted(true)
          } else if (res.state === 'denied') {
            useWalkie.getState().setMicGranted(false)
            useWalkie.getState().setMicDenied(true)
          }
        })
        .catch(() => {})
    }
  }, [identity, phase])

  const inSession = !!session

  return (
    <div className="min-h-dvh bg-background text-foreground antialiased overflow-x-hidden">
      <PwaRegister />
      <AnimatePresence mode="wait">
        {phase === 'boot' ? (
          <motion.div
            key="boot"
            className="min-h-dvh flex items-center justify-center"
            initial={{ opacity: 1 }}
            exit={{ opacity: 0 }}
          >
            <div className="w-16 h-16 rounded-[1.4rem] bg-gradient-to-br from-amber-400 to-amber-600 flex items-center justify-center animate-breathe shadow-lg shadow-amber-500/20">
              <svg
                width="30"
                height="30"
                viewBox="0 0 24 24"
                fill="none"
                stroke="white"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <circle cx="12" cy="12" r="2" />
                <path d="M4.93 19.07a10 10 0 0 1 0-14.14" />
                <path d="M7.76 16.24a6 6 0 0 1 0-8.49" />
                <path d="M16.24 7.76a6 6 0 0 1 0 8.49" />
                <path d="M19.07 4.93a10 10 0 0 1 0 14.14" />
              </svg>
            </div>
          </motion.div>
        ) : phase === 'onboarding' ? (
          <motion.div
            key="onboarding"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0, y: -20 }}
          >
            <OnboardingView />
          </motion.div>
        ) : inSession ? (
          <motion.div
            key="session"
            initial={{ opacity: 0, y: 30, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 30 }}
            transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
          >
            <SessionView />
          </motion.div>
        ) : (
          <motion.div
            key="home"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
          >
            <HomeView />
          </motion.div>
        )}
      </AnimatePresence>

      {/* الطلبات الواردة والإعدادات تظهر فوق كل شيء */}
      <IncomingDialog />
      <SettingsSheet />
    </div>
  )
}
