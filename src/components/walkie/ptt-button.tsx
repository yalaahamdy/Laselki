'use client'

import { useCallback, useRef, useState } from 'react'
import { Mic } from 'lucide-react'
import { cn } from '@/lib/utils'
import { engine } from '@/lib/walkie/engine'

interface PttButtonProps {
  disabled?: boolean
  talkingLocal: boolean
  talkingRemote: boolean
  connected: boolean
}

/**
 * زر الضغط والتحدث — العنصر الأهم في التطبيق
 * ضغط مستمر = إرسال صوت فوري، رفع الإصبع = إيقاف
 */
export function PttButton({
  disabled,
  talkingLocal,
  talkingRemote,
  connected,
}: PttButtonProps) {
  const [pressed, setPressed] = useState(false)
  const holdingRef = useRef(false)

  const down = useCallback(() => {
    if (disabled || holdingRef.current) return
    holdingRef.current = true
    setPressed(true)
    void engine.pttDown()
  }, [disabled])

  const up = useCallback(() => {
    if (!holdingRef.current) return
    holdingRef.current = false
    setPressed(false)
    engine.pttUp()
  }, [])

  return (
    <div className="relative flex items-center justify-center select-none">
      {/* هالات نابضة أثناء الحديث */}
      {talkingLocal && (
        <>
          <span className="absolute w-full h-full rounded-full bg-amber-500/30 animate-ptt-ring pointer-events-none" />
          <span
            className="absolute w-full h-full rounded-full bg-amber-500/20 animate-ptt-ring pointer-events-none"
            style={{ animationDelay: '0.5s' }}
          />
        </>
      )}
      {talkingRemote && !talkingLocal && (
        <span className="absolute w-full h-full rounded-full bg-emerald-500/20 animate-ptt-ring pointer-events-none" />
      )}

      <button
        type="button"
        aria-label="اضغط باستمرار للتحدث"
        aria-pressed={talkingLocal}
        disabled={disabled}
        onPointerDown={(e) => {
          e.preventDefault()
          e.currentTarget.setPointerCapture(e.pointerId)
          down()
        }}
        onPointerUp={(e) => {
          e.preventDefault()
          up()
        }}
        onPointerCancel={() => up()}
        onContextMenu={(e) => e.preventDefault()}
        className={cn(
          'relative z-10 w-[min(58vw,240px)] aspect-square rounded-full',
          'flex flex-col items-center justify-center gap-2',
          'touch-none select-none outline-none',
          'transition-all duration-150 ease-out',
          'shadow-[0_18px_50px_-12px_rgba(0,0,0,0.45)]',
          talkingLocal
            ? 'bg-gradient-to-b from-amber-400 to-amber-600 scale-95 shadow-amber-500/40'
            : talkingRemote
              ? 'bg-gradient-to-b from-emerald-500/90 to-emerald-700/90'
              : connected
                ? 'bg-gradient-to-b from-zinc-700 to-zinc-900 dark:from-zinc-700 dark:to-black border border-white/10'
                : 'bg-gradient-to-b from-zinc-400 to-zinc-600 opacity-60',
          pressed && !talkingRemote && 'scale-95',
          disabled && 'cursor-not-allowed',
        )}
      >
        <Mic
          className={cn(
            'transition-colors duration-150',
            talkingLocal
              ? 'text-amber-50'
              : talkingRemote
                ? 'text-emerald-50'
                : 'text-zinc-300 dark:text-zinc-400',
          )}
          size={64}
          strokeWidth={1.8}
        />
        <span
          className={cn(
            'text-sm font-semibold tracking-wide',
            talkingLocal
              ? 'text-amber-50'
              : talkingRemote
                ? 'text-emerald-50'
                : 'text-zinc-400 dark:text-zinc-500',
          )}
        >
          {talkingLocal
            ? 'أنت تتحدث الآن'
            : talkingRemote
              ? 'الطرف الآخر يتحدث'
              : connected
                ? 'اضغط باستمرار للتحدث'
                : 'غير متصل'}
        </span>
      </button>
    </div>
  )
}
