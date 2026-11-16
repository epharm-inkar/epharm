import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useAdministrativeCapabilities } from './useAdministrativeCapabilities'

const state = vi.hoisted(() => ({
  user: { id: 'hq', role: 'HQ_HEAD' },
  query: { isSuccess: true, isFetchedAfterMount: true, isError: false,
    data: { managed: false as boolean | undefined } },
}))
vi.mock('@tanstack/react-query', () => ({ useQuery: () => state.query }))
vi.mock('./store', () => ({ useUiStore: (select: (s: unknown) => unknown) =>
  select({ authedUser: state.user }) }))

describe('Standard-N mappings match the server LEGACY-only gate', () => {
  beforeEach(() => {
    state.user.role = 'HQ_HEAD'
    state.query = { isSuccess: true, isFetchedAfterMount: true, isError: false, data: { managed: false } }
  })
  it.each(['HQ_HEAD', 'SYSTEM_ADMIN'])('allows fresh LEGACY %s', (role) => {
    state.user.role = role
    expect(renderHook(() => useAdministrativeCapabilities()).result.current.canManageStandardNMappings).toBe(true)
  })
  it.each(['HQ_HEAD', 'SYSTEM_ADMIN', 'REGIONAL_MANAGER'])('denies MANAGED %s', (role) => {
    state.user.role = role
    state.query.data.managed = true
    expect(renderHook(() => useAdministrativeCapabilities()).result.current.canManageStandardNMappings).toBe(false)
  })
  it('does not infer access from an unknown legacy response', () => {
    state.query.data.managed = undefined
    expect(renderHook(() => useAdministrativeCapabilities()).result.current.canManageStandardNMappings).toBe(false)
  })
  it.each(['isError', 'notFetched', 'notSuccessful', 'wrongRole'])('fails closed for %s', (reason) => {
    if (reason === 'isError') state.query.isError = true
    if (reason === 'notFetched') state.query.isFetchedAfterMount = false
    if (reason === 'notSuccessful') state.query.isSuccess = false
    if (reason === 'wrongRole') state.user.role = 'REGIONAL_MANAGER'
    expect(renderHook(() => useAdministrativeCapabilities()).result.current.canManageStandardNMappings).toBe(false)
  })
})
