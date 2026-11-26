// Телефон: ширина до 767 px. Одни и те же данные рисуем таблицей на компьютере и карточками на телефоне.

import { useEffect, useState } from 'react'

const QUERY = '(max-width: 767px)'

export function useNarrowScreen(): boolean {
  const [matches, setMatches] = useState(
    () => typeof window !== 'undefined' && Boolean(window.matchMedia?.(QUERY).matches),
  )
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return
    const media = window.matchMedia(QUERY)
    const onChange = (event: MediaQueryListEvent) => setMatches(event.matches)
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])
  return matches
}
