'use client'

import { useEffect, useMemo, useState } from 'react'
import { Check, Copy, Laptop, QrCode, Smartphone } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'

/** عنوان الانضمام المفضّل:
 *  1) معامل ?join= في العنوان (يحقنه المضيف عند الحاجة)
 *  2) متغير عام window.__WALKIE_JOIN_URL__ (يحقنه تطبيق ويندوز)
 *  3) عنوان الصفحة الحالي نفسه (الحالة الافتراضية) */
function currentJoinUrl(): string {
  if (typeof window === 'undefined') return ''
  const injected =
    (window as unknown as { __WALKIE_JOIN_URL__?: string }).__WALKIE_JOIN_URL__ ??
    new URLSearchParams(window.location.search).get('join')
  if (injected) return injected
  const clean = new URL(window.location.href)
  clean.searchParams.delete('as-new')
  clean.searchParams.delete('join')
  return clean.toString()
}

export function JoinDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (v: boolean) => void }) {
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const url = useMemo(() => (open ? currentJoinUrl() : ''), [open])

  useEffect(() => {
    if (!open || !url) return
    let alive = true
    import('qrcode')
      .then((QR) => QR.toDataURL(url, { width: 512, margin: 2, errorCorrectionLevel: 'M' }))
      .then((d) => alive && setQrDataUrl(d))
      .catch(() => alive && setQrDataUrl(null))
    return () => {
      alive = false
    }
  }, [open, url])

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      setTimeout(() => setCopied(false), 1600)
    } catch {
      /* المتصفح منع النسخ */
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-sm rounded-3xl" dir="rtl">
        <DialogHeader className="items-start text-start">
          <DialogTitle className="text-lg font-extrabold flex items-center gap-2">
            <QrCode size={19} className="text-amber-500" />
            انضم من جهاز آخر
          </DialogTitle>
          <DialogDescription className="text-[13px] leading-relaxed">
            افتح التطبيق على الهاتف أو الكمبيوتر المتصل بنفس شبكة Wi-Fi، ثم امسح الرمز أو أدخل العنوان.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {/* رمز QR — على خلفية بيضاء دائمًا لوضوح المسح */}
          <div className="mx-auto w-fit rounded-2xl bg-white p-3 shadow-sm border">
            {qrDataUrl ? (
              <img src={qrDataUrl} alt="رمز الانضمام إلى الشبكة" className="w-44 h-44 rounded-lg" />
            ) : (
              <div className="w-44 h-44 rounded-lg bg-muted flex items-center justify-center">
                <QrCode className="text-muted-foreground/50 animate-pulse" size={40} />
              </div>
            )}
          </div>

          {/* العنوان */}
          <div className="flex items-center gap-2">
            <code
              dir="ltr"
              className="flex-1 min-w-0 truncate text-[12px] bg-muted/70 rounded-xl px-3 py-2.5 font-medium"
            >
              {url}
            </code>
            <Button
              size="icon"
              variant="outline"
              className="rounded-xl w-11 h-11 shrink-0"
              aria-label="نسخ العنوان"
              onClick={() => void copy()}
            >
              {copied ? <Check size={17} className="text-emerald-600" /> : <Copy size={17} />}
            </Button>
          </div>

          {/* الخطوات */}
          <ol className="space-y-2 text-[13px] text-muted-foreground">
            <li className="flex gap-2.5 items-start">
              <span className="w-5 h-5 rounded-full bg-amber-500/15 text-amber-700 dark:text-amber-400 text-[11px] font-bold flex items-center justify-center shrink-0 mt-0.5">1</span>
              تأكد أن الجهازين متصلان بنفس شبكة Wi-Fi
            </li>
            <li className="flex gap-2.5 items-start">
              <span className="w-5 h-5 rounded-full bg-amber-500/15 text-amber-700 dark:text-amber-400 text-[11px] font-bold flex items-center justify-center shrink-0 mt-0.5">2</span>
              <span className="flex items-center gap-1.5 flex-wrap">
                افتح التطبيق على الجهاز الآخر
                <Smartphone size={13} className="inline" />
                <Laptop size={13} className="inline" />
                (متصفح أو تطبيق لاسِلكي)
              </span>
            </li>
            <li className="flex gap-2.5 items-start">
              <span className="w-5 h-5 rounded-full bg-amber-500/15 text-amber-700 dark:text-amber-400 text-[11px] font-bold flex items-center justify-center shrink-0 mt-0.5">3</span>
            سيظهر الجهاز في القائمة تلقائيًا — اضغط عليه وابدأ الحديث
            </li>
          </ol>
        </div>
      </DialogContent>
    </Dialog>
  )
}
