'use client'

import { useState } from 'react'
import {
  Moon,
  Radio,
  ShieldCheck,
  Smartphone,
  Sparkles,
  Sun,
  Volume2,
  UserRound,
  Hand,
} from 'lucide-react'
import { useTheme } from 'next-themes'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useToast } from '@/hooks/use-toast'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Switch } from '@/components/ui/switch'
import { AvatarBadge } from './avatar-badge'
import { AVATARS, type Identity } from '@/lib/walkie/identity'
import { engine } from '@/lib/walkie/engine'
import { promptInstall } from './pwa-register'
import { setSoundsEnabled } from '@/lib/walkie/sound'
import { useWalkie } from '@/lib/walkie/store'
import { cn } from '@/lib/utils'

function Section({
  title,
  icon: Icon,
  children,
}: {
  title: string
  icon: React.ComponentType<{ size?: number; className?: string }>
  children: React.ReactNode
}) {
  return (
    <section className="space-y-3">
      <h3 className="text-[13px] font-bold text-muted-foreground flex items-center gap-1.5 px-1">
        <Icon size={14} />
        {title}
      </h3>
      <div className="rounded-2xl border bg-card divide-y overflow-hidden">
        {children}
      </div>
    </section>
  )
}

function Row({
  label,
  desc,
  children,
}: {
  label: string
  desc?: string
  children: React.ReactNode
}) {
  return (
    <div className="flex items-center justify-between gap-3 p-3.5">
      <div className="min-w-0">
        <p className="text-sm font-semibold">{label}</p>
        {desc && <p className="text-[12px] text-muted-foreground mt-0.5 leading-relaxed">{desc}</p>}
      </div>
      {children}
    </div>
  )
}

export function SettingsSheet() {
  const { settingsOpen, setSettingsOpen, identity } = useWalkie()

  return (
    <Sheet open={settingsOpen} onOpenChange={setSettingsOpen}>
      <SheetContent
        side="bottom"
        className="max-h-[88dvh] overflow-y-auto scrollbar-thin rounded-t-3xl px-5 pb-[max(env(safe-area-inset-bottom),1.25rem)] pt-2 max-w-lg mx-auto inset-x-0"
      >
        {/* يُركّب من جديد عند كل فتح — يقرأ الهوية الحالية دائمًا */}
        {settingsOpen && identity && (
          <SettingsBody identity={identity} onClose={() => setSettingsOpen(false)} />
        )}
      </SheetContent>
    </Sheet>
  )
}

function SettingsBody({
  identity,
  onClose,
}: {
  identity: Identity
  onClose: () => void
}) {
  const { settings, updateSettings, isAsNew } = useWalkie()
  const { theme, setTheme } = useTheme()
  const { toast } = useToast()
  const [name, setName] = useState(identity.name)
  const [avatar, setAvatar] = useState<string>(identity.avatar)

  const tryInstall = async () => {
    const started = await promptInstall()
    if (!started) {
      toast({
        title: 'تثبيت التطبيق',
        description:
          'من قائمة المتصفح اختر «إضافة إلى الشاشة الرئيسية» (أندرويد) أو «تثبيت هذا الموقع كتطبيق» (ويندوز/Edge).',
      })
    }
  }

  const applyIdentity = () => {
    if (name.trim().length < 2) return
    const next: Identity = {
      ...identity,
      name: name.trim().slice(0, 32),
      avatar,
    }
    engine.saveIdentity(next)
  }

  const dirty = name.trim() !== identity.name || avatar !== identity.avatar

  return (
    <>
        <div className="mx-auto w-10 h-1.5 rounded-full bg-muted-foreground/25 mb-3" />
        <SheetHeader className="items-start text-start">
          <SheetTitle className="text-lg font-extrabold">الإعدادات</SheetTitle>
          <SheetDescription className="text-[13px]">
            كل شيء يعمل تلقائيًا — هذه الخيارات للتخصيص فقط
          </SheetDescription>
        </SheetHeader>

        <div className="mt-4 space-y-5 pb-4">
          {/* الهوية */}
          <Section title="هويتك" icon={UserRound}>
            <div className="p-3.5 space-y-3">
              <div className="flex items-center gap-3">
                <AvatarBadge avatar={avatar} size={46} />
                <Input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  maxLength={32}
                  className="h-11 rounded-xl"
                  aria-label="اسم الجهاز"
                />
              </div>
              <div className="grid grid-cols-8 gap-1.5">
                {AVATARS.map((a) => (
                  <button
                    key={a}
                    type="button"
                    onClick={() => setAvatar(a)}
                    aria-label={a}
                    className={cn(
                      'flex items-center justify-center rounded-xl border-2 p-1 transition-all',
                      avatar === a
                        ? 'border-amber-500 bg-amber-500/10 scale-105'
                        : 'border-transparent',
                    )}
                  >
                    <AvatarBadge avatar={a} size={30} />
                  </button>
                ))}
              </div>
              {dirty && (
                <Button size="sm" className="h-9 rounded-lg w-full" onClick={applyIdentity}>
                  حفظ الهوية
                </Button>
              )}
            </div>
          </Section>

          {/* المظهر */}
          <Section title="المظهر" icon={Sparkles}>
            <Row label="نمط العرض" desc="الوضع الداكن مريح في الإضاءة المنخفضة">
              <div className="flex rounded-xl border p-1 gap-1 bg-muted/50">
                {(['light', 'dark', 'system'] as const).map((t) => (
                  <button
                    key={t}
                    type="button"
                    onClick={() => setTheme(t)}
                    aria-label={t === 'light' ? 'فاتح' : t === 'dark' ? 'داكن' : 'النظام'}
                    className={cn(
                      'w-8 h-8 rounded-lg flex items-center justify-center transition-colors',
                      theme === t
                        ? 'bg-background shadow-sm text-amber-600'
                        : 'text-muted-foreground',
                    )}
                  >
                    {t === 'light' ? <Sun size={15} /> : t === 'dark' ? <Moon size={15} /> : <Radio size={15} />}
                  </button>
                ))}
              </div>
            </Row>
            <div className="flex items-center justify-between gap-3 p-3.5">
              <div>
                <p className="text-sm font-semibold flex items-center gap-1.5">
                  <Volume2 size={14} className="text-muted-foreground" />
                  النغمات التفاعلية
                </p>
                <p className="text-[12px] text-muted-foreground mt-0.5">
                  نقرات اللاسلكي عند بدء وإنهاء الحديث
                </p>
              </div>
              <Switch
                checked={settings.sounds}
                onCheckedChange={(v) => {
                  updateSettings({ sounds: v })
                  setSoundsEnabled(v)
                }}
                aria-label="النغمات التفاعلية"
              />
            </div>
          </Section>

          {/* الخصوصية */}
          <Section title="الخصوصية والأذونات" icon={ShieldCheck}>
            <Row
              label="طلب الإذن قبل الاتصال"
              desc="عند التشغيل: سيصلك طلب قبول قبل فتح القناة. عند الإيقاف: يُقبل تلقائيًا فورًا (أسرع)."
            >
              <Switch
                checked={settings.askPermission}
                onCheckedChange={(v) => updateSettings({ askPermission: v })}
                aria-label="طلب الإذن قبل الاتصال"
              />
            </Row>
            <div className="p-3.5">
              <p className="text-[12px] leading-relaxed text-muted-foreground">
                الصوت ينتقل مباشرة بين الأجهزة عبر الشبكة المحلية فقط. لا حسابات، لا
                خوادم سحابية، ولا يتم تسجيل أي محادثة أو جمع أي بيانات.
              </p>
            </div>
          </Section>

          {/* التطبيق */}
          <Section title="التطبيق" icon={Smartphone}>
            <Row
              label="تثبيت لاسِلكي كتطبيق"
              desc="أيقونة مستقلة على الشاشة الرئيسية أو سطح المكتب — يعمل بملء الشاشة بدون شريط متصفح"
            >
              <Button
                size="sm"
                variant="outline"
                className="rounded-lg shrink-0"
                onClick={() => void tryInstall()}
              >
                تثبيت
              </Button>
            </Row>
          </Section>

          {/* أدوات التجربة */}
          <Section title="أدوات التجربة" icon={Hand}>
            <Row
              label="جهاز ثانٍ على نفس المتصفح"
              desc={
                isAsNew
                  ? 'هذه النافذة جهاز تجريبي مستقل (وضع ?as-new)'
                  : 'افتح نافذة جديدة تعمل كجهاز مختلف لاختبار الاتصال'
              }
            >
              <Button
                size="sm"
                variant="outline"
                className="rounded-lg shrink-0"
                onClick={() =>
                  window.open(`${location.pathname}?as-new=1`, '_blank')
                }
              >
                فتح
              </Button>
            </Row>
          </Section>

          <p className="text-center text-[11px] text-muted-foreground pb-2">
            لاسِلكي · الإصدار 1.0 — يعمل عبر Wi-Fi المحلي
          </p>
        </div>
    </>
  )
}
