'use client'

import { useEffect, useRef } from 'react'

interface VisualizerProps {
  getAnalyser: () => AnalyserNode | null
  active: boolean
  /** لون الأعمدة */
  color?: string
  className?: string
  barsCount?: number
  mirrored?: boolean
}

/**
 * تصوّر موجي حي للصوت — أعمدة دائرية ناعمة تتحرك مع مستوى الصوت الفعلي
 */
export function AudioVisualizer({
  getAnalyser,
  active,
  color = '#F59E0B',
  className = '',
  barsCount = 24,
  mirrored = true,
}: VisualizerProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const rafRef = useRef<number>(0)
  const levelRef = useRef(0)
  const smoothRef = useRef<number[]>([])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const data = new Uint8Array(128)
    smoothRef.current = new Array(barsCount).fill(0)

    const draw = () => {
      const analyser = getAnalyser()
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      const w = canvas.clientWidth
      const h = canvas.clientHeight
      if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
        canvas.width = w * dpr
        canvas.height = h * dpr
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.clearRect(0, 0, w, h)

      let target = 0
      if (analyser && active) {
        analyser.getByteFrequencyData(data as Uint8Array<ArrayBuffer>)
        // نأخذ النطاق المنخفض المهم للكلام
        let sum = 0
        for (let i = 2; i < 40; i++) sum += data[i]
        target = Math.min(1, sum / (38 * 140))
      }
      levelRef.current += (target - levelRef.current) * 0.18

      const barW = w / barsCount
      const mid = h / 2
      const gradient = ctx.createLinearGradient(0, 0, w, 0)
      gradient.addColorStop(0, color)
      gradient.addColorStop(0.5, color)
      gradient.addColorStop(1, color)

      for (let i = 0; i < barsCount; i++) {
        // موجة متناظرة نحو المركز
        const srcIdx = mirrored
          ? Math.floor(Math.abs(i - barsCount / 2) * (128 / (barsCount / 2)))
          : Math.floor((i / barsCount) * 64)
        const raw = analyser && active ? data[Math.min(srcIdx, 127)] / 255 : 0
        const wave =
          (0.06 + raw * 0.9 * (0.35 + levelRef.current * 1.3)) *
          (0.7 + 0.3 * Math.sin(Date.now() / 260 + i * 0.9))
        const bh = Math.max(3, Math.min(h * 0.92, wave * h))

        smoothRef.current[i] += (bh - smoothRef.current[i]) * 0.35
        const bhSmooth = smoothRef.current[i]

        const x = i * barW + barW * 0.22
        const bw = barW * 0.56
        const y = mid - bhSmooth / 2

        ctx.globalAlpha = active ? 0.95 : 0.22
        ctx.fillStyle = gradient
        const r = Math.min(bw / 2, 3)
        ctx.beginPath()
        ctx.roundRect(x, y, bw, bhSmooth, r)
        ctx.fill()
      }
      ctx.globalAlpha = 1
      rafRef.current = requestAnimationFrame(draw)
    }

    rafRef.current = requestAnimationFrame(draw)
    return () => cancelAnimationFrame(rafRef.current)
  }, [getAnalyser, active, color, barsCount, mirrored])

  return (
    <canvas
      ref={canvasRef}
      className={className}
      aria-hidden="true"
      style={{ width: '100%', height: '100%', display: 'block' }}
    />
  )
}
