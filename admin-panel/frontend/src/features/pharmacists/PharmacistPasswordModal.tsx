// Задать или сменить пароль фармацевта (только HQ; перенесено из PharmacistsPage без изменений поведения).

import { useState } from 'react'
import { Button, Field, Input, Modal, useToast } from '@/ui'
import type { PharmacistDto } from '@/lib/api-types'
import { useUpdatePharmacist } from '@/lib/queries/pharmacists'
import { isValidNewPharmacistPassword } from '@/lib/pharmacist-password'
import { describeError } from '@/lib/describeError'
import { maskedIin } from './personal-data'

export function PharmacistPasswordModal({
  pharmacist,
  onClose,
}: {
  pharmacist: PharmacistDto
  onClose: () => void
}) {
  const toast = useToast()
  const updatePharmacist = useUpdatePharmacist()
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const valid = isValidNewPharmacistPassword(value, 6)

  const save = () => {
    if (!valid) return
    setError(null)
    updatePharmacist.mutate(
      { id: pharmacist.id, patch: { password: value } },
      {
        onSuccess: () => {
          toast.push(`Пароль для ${pharmacist.name} сохранён`)
          setValue('')
          onClose()
        },
        onError: (requestError) => setError(describeError(requestError)),
      },
    )
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={pharmacist.hasPassword ? 'Сменить пароль' : 'Задать пароль'}
      subtitle={`${pharmacist.name} · ИИН ${maskedIin(pharmacist.iin)}`}
      width={460}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button variant="primary" disabled={updatePharmacist.isPending || !valid} onClick={save}>
            {updatePharmacist.isPending ? 'Сохраняем…' : 'Сохранить пароль'}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {error && (
          <div className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
            {error}
          </div>
        )}
        <Field label="Новый пароль">
          <Input
            type="password"
            value={value}
            minLength={6}
            maxLength={128}
            autoComplete="new-password"
            onChange={(event) => setValue(event.target.value)}
            placeholder="Минимум 6 символов"
            autoFocus
          />
        </Field>
        <p className="text-[11px] leading-5 text-ink-500">
          От 6 символов, не более 72 байт UTF-8; не только пробелы.
        </p>
        <p className="text-[11px] leading-5 text-ink-500">
          Передайте пароль фармацевту безопасным способом. В интерфейсе он больше не отображается.
        </p>
      </div>
    </Modal>
  )
}
