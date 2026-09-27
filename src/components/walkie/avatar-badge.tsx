'use client'

import { Bird, Cat, Dog, Panda, Rabbit, Rocket, Squirrel, Turtle } from 'lucide-react'
import { avatarColor } from '@/lib/walkie/identity'
import { cn } from '@/lib/utils'
import type { PublicDevice } from '@/lib/walkie/types'

const ICONS: Record<
  string,
  React.ComponentType<{ size?: number; strokeWidth?: number; className?: string }>
> = {
  Panda,
  Bird,
  Cat,
  Dog,
  Rabbit,
  Squirrel,
  Turtle,
  Rocket,
}

interface AvatarBadgeProps {
  avatar: string
  size?: number
  talking?: boolean
  className?: string
}

export function AvatarBadge({
  avatar,
  size = 44,
  talking = false,
  className,
}: AvatarBadgeProps) {
  const colors = avatarColor(avatar)
  const Icon = ICONS[avatar] ?? Rocket
  return (
    <div
      className={cn(
        'relative flex items-center justify-center shadow-sm shrink-0 transition-transform duration-200 select-none',
        talking && 'scale-105',
        className,
      )}
      style={{
        width: size,
        height: size,
        backgroundColor: colors.bg,
        borderRadius: Math.max(10, size * 0.3),
      }}
    >
      <Icon
        size={Math.round(size * 0.54)}
        strokeWidth={2.2}
        className="text-white drop-shadow-sm pointer-events-none"
      />
      {talking && (
        <span
          className="absolute -inset-1 animate-ping opacity-35 pointer-events-none"
          style={{ backgroundColor: colors.bg, borderRadius: 'inherit' }}
        />
      )}
    </div>
  )
}

export function DeviceAvatar({
  device,
  size = 44,
  className,
}: {
  device: PublicDevice
  size?: number
  className?: string
}) {
  return (
    <AvatarBadge
      avatar={device.avatar}
      size={size}
      talking={device.talking}
      className={className}
    />
  )
}
