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
