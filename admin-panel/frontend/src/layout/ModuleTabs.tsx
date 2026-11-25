import type { SectionId } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import {
  activeNavigationItem,
  type AdminNavigationItem,
  type AdminNavigationModule,
} from '@/app/adminNavigation'
import type { TrainingTab } from '@/app/trainingNavigation'

/**
 * The sections of the open module as tabs at the top of the page; the sidebar lists modules only
 * (Grigoriy, 25.11.2026: navigation on the page, not a second level in the sidebar). «Обучение»
 * keeps its own tab bar on its page, so here it only offers the way back from «AI-экзаменация».
 */
export function ModuleTabs({
  modules,
  active,
  activeTrainingTab,
  pathname,
  onNavigate,
}: {
  modules: readonly AdminNavigationModule[]
  active: SectionId
  activeTrainingTab: TrainingTab
  pathname: string
  onNavigate: (path: string) => void
}) {
  const t = useT()
  if (pathname === '/account') return null
  const module = modules.find((candidate) =>
    candidate.items.some((item) => activeNavigationItem(item, active, activeTrainingTab, pathname)),
  )
  if (!module) return null
  let items: AdminNavigationItem[] = module.items.filter((item) => item.available && item.path)
  if (module.id === 'training') {
    if (active === 'lms') return null
    const home = items.find((item) => item.trainingTab)
    items = [
      ...(home
        ? [{ ...home, id: 'training_home', label: t('module.training'), labelKey: undefined }]
        : []),
      ...items.filter((item) => !item.trainingTab),
    ]
  }
  if (items.length < 2) return null
  return (
    <nav aria-label={t(`module.${module.id}`)} className="mb-4">
      <div className="hairline no-scrollbar flex items-center gap-5 overflow-x-auto border-b">
        {items.map((item) => {
          const current =
            item.id === 'training_home'
              ? false
              : activeNavigationItem(item, active, activeTrainingTab, pathname)
          return (
            <button
              key={item.id}
              type="button"
              aria-current={current ? 'page' : undefined}
              className={`tab flex-none whitespace-nowrap ${current ? 'active' : ''}`}
              onClick={() => {
                if (!current && item.path) onNavigate(item.path)
              }}
            >
              {item.labelKey ? t(item.labelKey) : item.label}
            </button>
          )
        })}
      </div>
    </nav>
  )
}
