'use client'

import { useEffect } from 'react'

export interface DeferredInstallPrompt {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

/** يلتقط حدث التثبيت المؤجل لاستخدامه من زر "تثبيت التطبيق" في الإعدادات */
export function PwaRegister() {
  useEffect(() => {
    // تسجيل عامل الخدمة (يجعل التطبيق قابلاً للتثبيت)
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('/sw.js').catch(() => {
        /* بيئة بلا دعم — تجاهل بهدوء */
      })
    }

    // التقاط نية التثبيت من المتصفح
    const onPrompt = (e: Event) => {
      e.preventDefault()
      ;(window as unknown as { __walkieInstallPrompt?: DeferredInstallPrompt }).__walkieInstallPrompt =
        e as unknown as DeferredInstallPrompt
      window.dispatchEvent(new CustomEvent('walkie-install-available'))
    }
    window.addEventListener('beforeinstallprompt', onPrompt)

    const onInstalled = () => {
      ;(window as unknown as { __walkieInstallPrompt?: DeferredInstallPrompt }).__walkieInstallPrompt =
        undefined
      window.dispatchEvent(new CustomEvent('walkie-installed'))
    }
    window.addEventListener('appinstalled', onInstalled)

    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt)
      window.removeEventListener('appinstalled', onInstalled)
    }
  }, [])

  return null
}

/** محاولة استدعاء نافذة التثبيت؛ تعيد false إن لم يتوفر (iOS مثلًا) */
export async function promptInstall(): Promise<boolean> {
  const p = (window as unknown as { __walkieInstallPrompt?: DeferredInstallPrompt })
    .__walkieInstallPrompt
  if (!p) return false
  await p.prompt()
  await p.userChoice
  ;(window as unknown as { __walkieInstallPrompt?: DeferredInstallPrompt }).__walkieInstallPrompt =
    undefined
  return true
}
