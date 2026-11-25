// Rules of the lesson video: what counts as watched and where a seek may land.

/** How far ahead of the watched point a timeupdate may land and still count as ordinary playback. */
export const PLAYBACK_STEP_SECONDS = 2
/** A jump forward within this margin is tolerated (browser rounding, a click right at the edge). */
const SEEK_TOLERANCE_SECONDS = 1

export function nextWatchedSeconds(watched: number, current: number, seeking: boolean): number {
  if (seeking) return watched
  if (current > watched && current - watched <= PLAYBACK_STEP_SECONDS) return current
  return watched
}

export function allowedSeekTarget(watched: number, target: number): number {
  return target > watched + SEEK_TOLERANCE_SECONDS ? watched : target
}

/**
 * The figure saved for a video lesson. At the threshold the server completes the lesson, so while the
 * reading or the self-check of the same lesson is unfinished the saved figure stays just under it and
 * «Урок изучен, дальше» sends the full one (26.11.2026: a module is video, then text, then self-check).
 */
export function videoProgressToSave(
  watchedPct: number,
  threshold: number,
  gatesDone: boolean,
): number {
  return gatesDone ? watchedPct : Math.min(watchedPct, Math.max(0, threshold - 1))
}

/** The browser's record of what played (`HTMLMediaElement.played`) as [start, end] pairs, seconds. */
export type PlayedRange = readonly [number, number]

/**
 * The furthest point reached by continuous playback, from the browser's own record of what played.
 * Event timing does not matter here: on a busy till a timeupdate can come seconds late, and a jump
 * that large used to be taken for a seek, so «Просмотрено» froze for the rest of the clip (26.11.2026,
 * reproduced with the page blocked 2.5 s every 4 s: the clip played to the end, the page said 2%).
 * A played range counts only when it starts within the tolerance of what was already watched, so a
 * forward seek, which the page snaps back, never adds to it.
 */
export function watchedFromPlayed(ranges: readonly PlayedRange[], watched: number): number {
  let next = watched
  for (const [start, end] of [...ranges].sort((a, b) => a[0] - b[0])) {
    if (start <= next + SEEK_TOLERANCE_SECONDS && end > next) next = end
  }
  return next
}
