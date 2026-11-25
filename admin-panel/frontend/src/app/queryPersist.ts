import { hashKey, type QueryClient } from '@tanstack/react-query'

export interface PersistableStorage {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
  removeItem: (key: string) => void
}

const STORAGE_KEY = 'epharm.query.cache'
const DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000
/** One cached response larger than this (about 0.5 MB) is not stored; the page fetches it again. */
export const MAX_ENTRY_CHARS = 250_000
/** The whole stored cache stays well under the browser's localStorage quota. */
export const MAX_TOTAL_CHARS = 1_500_000

interface PersistedEnvelope {
  version: 2
  principal: string
  timestamp: number
  entries: Array<{ key: string; queryKey: readonly unknown[]; data: unknown }>
}

interface ConfigureOptions {
  principal?: string | null
  maxAgeMs?: number
  debounceMs?: number
}

export function mayPersistQuery(key: readonly unknown[]): boolean {
  const first = key[0]
  return (
    typeof first === 'string' &&
    first !== 'promo-changes' &&
    first !== 'recommendation-rewards' &&
    !/^(access|admin-access|unified-access|unified-admin-access)(?:$|[-/:.])/.test(first)
  )
}

export function configureQueryPersistence(
  client: QueryClient,
  storage: PersistableStorage,
  { principal = null, maxAgeMs = DEFAULT_MAX_AGE_MS, debounceMs = 50 }: ConfigureOptions = {},
): () => void {
  if (!principal) {
    clearPersistedCache(storage)
    return () => {}
  }
  try {
    const raw = storage.getItem(STORAGE_KEY)
    if (raw) {
      const envelope = JSON.parse(raw) as PersistedEnvelope
      const age = Date.now() - envelope.timestamp
      if (
        envelope.version === 2 &&
        envelope.principal === principal &&
        age >= 0 &&
        age < maxAgeMs &&
        Array.isArray(envelope.entries)
      ) {
        for (const entry of envelope.entries) {
          if (
            Array.isArray(entry.queryKey) &&
            mayPersistQuery(entry.queryKey) &&
            entry.data !== undefined
          ) {
            client.setQueryData(entry.queryKey, entry.data, { updatedAt: 0 })
          }
        }
        if (
          envelope.entries.some(
            (entry) => Array.isArray(entry.queryKey) && !mayPersistQuery(entry.queryKey),
          )
        )
          clearPersistedCache(storage)
      } else clearPersistedCache(storage)
    }
  } catch {
    clearPersistedCache(storage)
  }

  let disposed = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let lastWritten: string | null = null
  // Serialising every cached table on each cache event froze big admin pages (25.11.2026):
  // a 15-second poll re-encoded megabytes of pharmacists and assignments each time. Data
  // objects are immutable in TanStack Query, so each one is encoded once and reused.
  const encoded = new WeakMap<object, string | null>()
  const encodeData = (data: unknown): string | null => {
    if (data === null || typeof data !== 'object') {
      try {
        return JSON.stringify(data) ?? null
      } catch {
        return null
      }
    }
    if (encoded.has(data)) return encoded.get(data) ?? null
    let json: string | null
    try {
      json = JSON.stringify(data) ?? null
    } catch {
      json = null
    }
    // Large tables are fetched again after a reload instead of being kept in localStorage.
    if (json !== null && json.length > MAX_ENTRY_CHARS) json = null
    encoded.set(data, json)
    return json
  }
  const persist = () => {
    clearTimeout(timer)
    timer = setTimeout(() => {
      if (disposed) return
      const queries = client
        .getQueryCache()
        .getAll()
        .filter(
          (query) =>
            query.state.status === 'success' &&
            query.state.data !== undefined &&
            mayPersistQuery(query.queryKey),
        )
        .sort((a, b) => b.state.dataUpdatedAt - a.state.dataUpdatedAt)
      const parts: string[] = []
      let total = 0
      for (const query of queries) {
        const data = encodeData(query.state.data)
        if (data === null) continue
        let part: string
        try {
          part = `{"key":${JSON.stringify(hashKey(query.queryKey))},"queryKey":${JSON.stringify(query.queryKey)},"data":${data}}`
        } catch {
          continue
        }
        if (total + part.length > MAX_TOTAL_CHARS) continue
        total += part.length
        parts.push(part)
      }
      const body = parts.join(',')
      if (body === lastWritten) return
      const envelope = `{"version":2,"principal":${JSON.stringify(principal)},"timestamp":${Date.now()},"entries":[${body}]}`
      try {
        storage.setItem(STORAGE_KEY, envelope)
        lastWritten = body
      } catch {
        /* quota/private mode */
      }
    }, debounceMs)
  }
  // Only data changes matter; fetch-state and observer events fire constantly and change nothing stored.
  const unsubscribe = client.getQueryCache().subscribe((event) => {
    if (event.type === 'removed') return persist()
    if (event.type === 'updated' && (event.action.type === 'success' || event.action.type === 'setState'))
      persist()
  })
  persist()
  return () => {
    disposed = true
    clearTimeout(timer)
    unsubscribe()
  }
}

export function clearPersistedCache(storage: PersistableStorage): void {
  try {
    storage.removeItem(STORAGE_KEY)
  } catch {
    /* private mode */
  }
}
