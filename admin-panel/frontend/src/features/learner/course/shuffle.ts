// Stable shuffles for quizzes: the same seed gives the same order on every render and reload, a new
// attempt (new seed) gives a new order. Answers that refer to the others stay last.

function hash(text: string): number {
  let value = 2166136261
  for (let index = 0; index < text.length; index++) {
    value ^= text.charCodeAt(index)
    value = Math.imul(value, 16777619)
  }
  return value >>> 0
}

function random(seed: number) {
  let state = seed || 1
  return () => {
    state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0
    state ^= state >>> 13
    return (state >>> 0) / 4294967296
  }
}

export function seededOrder<T>(items: readonly T[], seed: string): T[] {
  const next = random(hash(seed))
  const copy = [...items]
  for (let index = copy.length - 1; index > 0; index--) {
    const target = Math.floor(next() * (index + 1))
    ;[copy[index], copy[target]] = [copy[target], copy[index]]
  }
  return copy
}

const PINNED_LAST =
  /^(все|оба|ни один|ничего из|всё перечисленное|все перечисленное|барлығы|ешқайсысы)/i

/** Answer options in a seeded order, with «Все перечисленное» and the like kept at the end. */
export function seededOptions(
  options: readonly string[],
  seed: string,
): Array<{ text: string; original: number }> {
  const indexed = options.map((text, original) => ({ text, original }))
  const pinned = indexed.filter((option) => PINNED_LAST.test(option.text.trim()))
  const free = indexed.filter((option) => !PINNED_LAST.test(option.text.trim()))
  return [...seededOrder(free, seed), ...pinned]
}
