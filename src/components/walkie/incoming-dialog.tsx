'use client'

import { motion } from 'framer-motion'
import { PhoneIncoming } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { AvatarBadge } from './avatar-badge'
import { engine } from '@/lib/walkie/engine'
import { useWalkie } from '@/lib/walkie/store'

export function IncomingDialog() {
  const incomingFrom = useWalkie((s) => s.incomingFrom)
  const open = !!incomingFrom

  return (
    <Dialog open={open}>
      <DialogContent className="max-w-[340px] rounded-3xl gap-4" showCloseButton={false} onEscapeKeyDown={(e) => e.preventDefault()} onPointerDownOutside={(e) => e.preventDefault()}>
        {incomingFrom && (
          <div className="flex flex-col items-center text-center gap-4 pt-2">
            <div className="relative">
              <span className="absolute -inset-3 rounded-[2.4rem] border-2 border-emerald-500/40 animate-radar" />
              <AvatarBadge avatar={incomingFrom.avatar} size={76} talking />
            </div>
            <DialogHeader className="items-center space-y-1.5">
              <DialogTitle className="flex items-center gap-2 text-xl">
                <PhoneIncoming size={18} className="text-emerald-500" />
                {incomingFrom.name}
              </DialogTitle>
              <DialogDescription className="text-[13px]">
                يريد فتح قناة لاسلكي معك الآن
              </DialogDescription>
            </DialogHeader>
            <div className="grid grid-cols-2 gap-2.5 w-full">
              <Button
                variant="outline"
                className="h-12 rounded-xl font-bold"
                onClick={() => engine.declineIncoming()}
              >
                رفض
              </Button>
              <Button
                className="h-12 rounded-xl font-bold bg-emerald-600 hover:bg-emerald-700"
                onClick={() => void engine.acceptIncoming()}
              >
                قبول
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
