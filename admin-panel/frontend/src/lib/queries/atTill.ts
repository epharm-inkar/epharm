// «Сейчас за кассой» (26.11.2026): кто из фармацевтов сидит за кассой в эту минуту. Список считает сервер:
// касса на связи и назвала этого человека (пользователя Стандарт-Н) за последние 20 минут. Обновляем раз в минуту,
// пока вкладка открыта; кнопка «Обновить» читает заново сразу.

import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { AtTillListDto } from '@/lib/api-types'

export const AT_TILL_POLL_MS = 60_000

export interface AtTillFilter {
  /** Аптека кассы (не обязательно родная аптека человека). */
  pharmacyId?: string
  /** Часть ФИО, телефон цифрами, ИИН, название или адрес аптеки. */
  q?: string
}

export const atTillKeys = {
  all: ['at-till'] as const,
  list: (filter: AtTillFilter) => [...atTillKeys.all, 'list', filter] as const,
}

export function useAtTill(filter: AtTillFilter, enabled = true) {
  return useQuery<AtTillListDto>({
    queryKey: atTillKeys.list(filter),
    queryFn: ({ signal }) =>
      api
        .get<AtTillListDto>('/api/admin/posm-at-till', {
          params: { pharmacyId: filter.pharmacyId || undefined, q: filter.q || undefined },
          signal,
        })
        .then((r) => r.data),
    enabled,
    refetchInterval: AT_TILL_POLL_MS,
  })
}
