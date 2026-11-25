// Задания кассам без AnyDesk: «Диагностика» и «Перезапуск» во вкладке «Все кассы» (только HQ).
// Один список последних заданий всей сети: пока хоть одно ждёт кассу или выполняется,
// опрашиваем каждые 10 с, иначе раз в минуту, как и таблицу касс.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { CreateTillCommandRequest, TillCommandDto } from '@/lib/api-types'

export const TILL_COMMANDS_LIMIT = 500
export const TILL_COMMANDS_ACTIVE_POLL_MS = 10_000
export const TILL_COMMANDS_IDLE_POLL_MS = 60_000

export const tillCommandKeys = {
  all: ['till-commands'] as const,
  latest: () => [...tillCommandKeys.all, 'latest'] as const,
}

export function isActiveTillCommand(command: Pick<TillCommandDto, 'status'>): boolean {
  return command.status === 'pending' || command.status === 'delivered'
}

/** Как часто обновлять список: 10 с, пока есть задание в работе. */
export function tillCommandsPollInterval(commands: TillCommandDto[] | undefined): number {
  return commands?.some(isActiveTillCommand) ? TILL_COMMANDS_ACTIVE_POLL_MS : TILL_COMMANDS_IDLE_POLL_MS
}

export function useTillCommands(enabled = true) {
  return useQuery<TillCommandDto[]>({
    queryKey: tillCommandKeys.latest(),
    queryFn: ({ signal }) =>
      api
        .get<TillCommandDto[]>('/api/admin/tills/commands', { params: { limit: TILL_COMMANDS_LIMIT }, signal })
        .then((r) => r.data),
    enabled,
    refetchInterval: (query) => tillCommandsPollInterval(query.state.data),
  })
}

export function useCreateTillCommand() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateTillCommandRequest) =>
      api.post<TillCommandDto>('/api/admin/tills/commands', body).then((r) => r.data),
    onSuccess: (created) => {
      // Строка сразу показывает «ждёт кассу», и опрос переходит на 10 с.
      qc.setQueryData<TillCommandDto[]>(tillCommandKeys.latest(), (current) => [
        created,
        ...(current ?? []).filter((command) => command.id !== created.id),
      ])
    },
    // 409: задание уже дал кто-то другой; обновляем список, чтобы его увидеть.
    onSettled: () => qc.invalidateQueries({ queryKey: tillCommandKeys.latest() }),
  })
}

/** Архив диагностики (zip) с именем из Content-Disposition, если браузер его видит. */
export async function fetchTillArtifact(id: string): Promise<{ blob: Blob; fileName: string | null }> {
  const response = await api.get<Blob>(`/api/admin/tills/commands/${encodeURIComponent(id)}/artifact`, {
    responseType: 'blob',
    timeout: 60_000,
  })
  const disposition = String(response.headers?.['content-disposition'] ?? '')
  const match = /filename="?([^";]+)"?/i.exec(disposition)
  return { blob: response.data, fileName: match?.[1] ?? null }
}
