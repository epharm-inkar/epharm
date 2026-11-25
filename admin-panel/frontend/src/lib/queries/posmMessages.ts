// Уведомления фармацевтам на кассах (POSM 1.0.99): HQ пишет заголовок и текст, выбирает, кому, и отправляет;
// касса показывает уведомление, пока фармацевт не закроет его крестиком. Список с отметками «увидели» и
// «закрыли» обновляем раз в минуту, пока вкладка открыта.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { CreatePosmMessageRequest, PosmMessageDto } from '@/lib/api-types'

export const POSM_MESSAGES_POLL_MS = 60_000

export const posmMessageKeys = {
  all: ['posm-messages'] as const,
  list: () => [...posmMessageKeys.all, 'list'] as const,
}

export function usePosmMessages(enabled = true) {
  return useQuery<PosmMessageDto[]>({
    queryKey: posmMessageKeys.list(),
    queryFn: ({ signal }) =>
      api
        .get<PosmMessageDto[]>('/api/admin/posm-messages', { params: { limit: 100 }, signal })
        .then((r) => r.data),
    enabled,
    refetchInterval: POSM_MESSAGES_POLL_MS,
  })
}

export function useSendPosmMessage() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreatePosmMessageRequest) =>
      api.post<PosmMessageDto>('/api/admin/posm-messages', body).then((r) => r.data),
    onSuccess: (created) => {
      qc.setQueryData<PosmMessageDto[]>(posmMessageKeys.list(), (current) => [
        created,
        ...(current ?? []).filter((message) => message.id !== created.id),
      ])
    },
    onSettled: () => qc.invalidateQueries({ queryKey: posmMessageKeys.list() }),
  })
}

export function useWithdrawPosmMessage() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api
        .post<PosmMessageDto>(`/api/admin/posm-messages/${encodeURIComponent(id)}/withdraw`)
        .then((r) => r.data),
    onSuccess: (updated) => {
      qc.setQueryData<PosmMessageDto[]>(posmMessageKeys.list(), (current) =>
        (current ?? []).map((message) => (message.id === updated.id ? updated : message)),
      )
    },
    onSettled: () => qc.invalidateQueries({ queryKey: posmMessageKeys.list() }),
  })
}
