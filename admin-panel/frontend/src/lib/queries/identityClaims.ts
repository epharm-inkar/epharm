// «ИИН указали сами» (26.11.2026): TanStack Query хуки. Только HQ_HEAD и SYSTEM_ADMIN.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { ConfirmIdentityClaimRequest, IdentityClaimDto } from '@/lib/api-types'
import { pharmacistKeys } from './pharmacists'

export const identityClaimKeys = {
  all: ['posm', 'identity-claims'] as const,
}

/** Every claim, open ones first (the server orders them). Off for roles that cannot decide. */
export function useIdentityClaims(enabled = true) {
  return useQuery<IdentityClaimDto[]>({
    queryKey: identityClaimKeys.all,
    queryFn: () =>
      api.get<IdentityClaimDto[]>('/api/admin/posm/identity-claims').then((r) => r.data),
    enabled,
    staleTime: 30_000,
  })
}

/** A decision changes claims, the roster (a new card, a new name) and the seller mappings. */
function useRefreshAfterDecision() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: identityClaimKeys.all })
    qc.invalidateQueries({ queryKey: pharmacistKeys.all })
    qc.invalidateQueries({ queryKey: pharmacistKeys.standardNMappings })
    qc.invalidateQueries({ queryKey: pharmacistKeys.unmappedStandardNSellers })
  }
}

export function useConfirmIdentityClaim() {
  const refresh = useRefreshAfterDecision()
  return useMutation({
    mutationFn: ({ id, request }: { id: string; request: ConfirmIdentityClaimRequest }) =>
      api
        .post<IdentityClaimDto>(`/api/admin/posm/identity-claims/${id}/confirm`, request)
        .then((r) => r.data),
    onSuccess: refresh,
  })
}

export function useRejectIdentityClaim() {
  const refresh = useRefreshAfterDecision()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      api
        .post<IdentityClaimDto>(`/api/admin/posm/identity-claims/${id}/reject`, { reason })
        .then((r) => r.data),
    onSuccess: refresh,
  })
}
