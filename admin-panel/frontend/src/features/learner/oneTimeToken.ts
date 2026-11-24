/** Reads `#t=<token>` from a cabinet link opened from the till; pure, so it is safe in a state initializer. */
export function parseOneTimeToken(hash: string): string | null {
  const match = /(?:^#|&)t=([A-Za-z0-9_-]{43})(?:&|$)/.exec(hash)
  return match ? match[1] : null
}
