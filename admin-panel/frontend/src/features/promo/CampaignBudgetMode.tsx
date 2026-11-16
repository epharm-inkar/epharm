import { useT } from '@/i18n'

/** The caller must supply a fresh, server-confirmed financial capability. */
export function CampaignBudgetMode({ unlimited, canConfigure, disabled, onChange }: {
  unlimited: boolean
  canConfigure: boolean
  disabled?: boolean
  onChange: (unlimited: boolean) => void
}) {
  const t = useT()
  return (
    <div className="space-y-2 text-[12px]" data-testid="campaign-budget-mode">
      {canConfigure ? (
        <label className="flex items-center gap-2 font-semibold text-ink-900">
          <input
            type="checkbox"
            checked={unlimited}
            disabled={disabled}
            onChange={(event) => {
              const next = event.target.checked
              if (!next || window.confirm(t('budget.unlimitedConfirm'))) onChange(next)
            }}
          />
          {t('budget.unlimited')}
        </label>
      ) : (
        <p className="font-semibold text-ink-700">{t(unlimited ? 'budget.unlimited' : 'budget.limited')}</p>
      )}
      <p className="text-ink-500">{t(unlimited ? 'budget.unlimitedHint' : 'budget.limitedHint')}</p>
    </div>
  )
}
