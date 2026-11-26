// Уведомления фармацевтам на кассах (POSM 1.0.99): HQ пишет заголовок и текст, выбирает, кому, и отправляет;
// касса показывает уведомление, пока фармацевт не закроет его крестиком. Список с отметками «увидели» и
// «закрыли» обновляем раз в минуту, пока вкладка открыта.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  CreatePosmMessageRequest,
  PosmAudienceRequest,
  PosmCourseOptionDto,
  PosmMessageDto,
  PosmMessagePreviewDto,
  PosmPharmacistSearchDto,
} from '@/lib/api-types'

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

// ─── Кому уйдёт: подсчёт до отправки, курсы и поиск людей (26.11.2026) ───

export const POSM_PREVIEW_DEBOUNCE_MS = 350

export const posmAudienceKeys = {
  preview: (body: PosmAudienceRequest | null) => [...posmMessageKeys.all, 'preview', body ?? 'none'] as const,
  courses: () => [...posmMessageKeys.all, 'courses'] as const,
  people: (q: string) => [...posmMessageKeys.all, 'people', q] as const,
}

/**
 * Сколько человек получат уведомление и сколько из них сейчас за кассой. Считает сервер (он знает, кто за кассой),
 * ничего не пишет. Запрос повторяется раз в минуту: люди за кассами меняются.
 */
export function usePosmMessagePreview(body: PosmAudienceRequest | null) {
  return useQuery<PosmMessagePreviewDto>({
    queryKey: posmAudienceKeys.preview(body),
    queryFn: ({ signal }) =>
      api.post<PosmMessagePreviewDto>('/api/admin/posm-messages/preview', body, { signal }).then((r) => r.data),
    enabled: body !== null,
    refetchInterval: POSM_MESSAGES_POLL_MS,
  })
}

/** Курсы, которые ещё не прошли: для «Напомнить о курсе». */
export function usePosmCourses(enabled = true) {
  return useQuery<PosmCourseOptionDto[]>({
    queryKey: posmAudienceKeys.courses(),
    queryFn: ({ signal }) =>
      api.get<PosmCourseOptionDto[]>('/api/admin/posm-messages/courses', { signal }).then((r) => r.data),
    enabled,
    staleTime: 60_000,
  })
}

/** Поиск адресатов-людей: фамилия, имя, отчество, телефон (цифры), ИИН, аптека. Пустой запрос даёт первых по алфавиту. */
export function usePosmPharmacistSearch(q: string, enabled = true) {
  return useQuery<PosmPharmacistSearchDto>({
    queryKey: posmAudienceKeys.people(q),
    queryFn: ({ signal }) =>
      api
        .get<PosmPharmacistSearchDto>('/api/admin/posm-messages/pharmacists', { params: { q: q || undefined, limit: 50 }, signal })
        .then((r) => r.data),
    enabled,
  })
}
