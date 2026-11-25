import { useEffect, useRef } from 'react'
import { allowedSeekTarget, nextWatchedSeconds, PLAYBACK_STEP_SECONDS } from './guardedVideoRules'

/**
 * A lesson video that has to be watched (Grigoriy, 25.11.2026: «видео нельзя промотать до конца
 * и нельзя менять скорость»). Rewinding is free; jumping past the furthest watched point snaps
 * back, and the speed stays 1x. A completed lesson plays without limits, for revision.
 */
export interface GuardedVideoProgress {
  /** Furthest point reached by ordinary playback, in percent of the clip. */
  watchedPct: number
  /** Furthest point reached by ordinary playback, in seconds. */
  watchedSeconds: number
  /** Length of the clip in seconds, as the browser measured it. */
  durationSeconds: number
}

interface GuardedVideoProps {
  src: string
  /** Seconds already watched in an earlier visit: playback may resume and jump up to here. */
  resumeAtSeconds?: number | null
  /** No limits once the lesson is done. */
  unrestricted?: boolean
  ariaLabel: string
  className?: string
  poster?: string
  onProgress: (progress: GuardedVideoProgress) => void
  onPlay?: (progress: GuardedVideoProgress) => void
  onEnded?: (progress: GuardedVideoProgress) => void
}

export function GuardedVideo({
  src,
  resumeAtSeconds,
  unrestricted = false,
  ariaLabel,
  className,
  poster,
  onProgress,
  onPlay,
  onEnded,
}: GuardedVideoProps) {
  const ref = useRef<HTMLVideoElement | null>(null)
  const watched = useRef(Math.max(0, resumeAtSeconds ?? 0))
  const seeking = useRef(false)

  useEffect(() => {
    watched.current = Math.max(0, resumeAtSeconds ?? 0)
  }, [src, resumeAtSeconds])

  const snapshot = (element: HTMLVideoElement): GuardedVideoProgress => {
    const duration = Number.isFinite(element.duration) && element.duration > 0 ? element.duration : 0
    const watchedSeconds = Math.min(watched.current, duration || watched.current)
    return {
      durationSeconds: duration,
      watchedSeconds,
      watchedPct: duration > 0 ? Math.min(100, Math.floor((watchedSeconds / duration) * 100)) : 0,
    }
  }

  return (
    <video
      ref={ref}
      controls
      playsInline
      preload="metadata"
      poster={poster}
      aria-label={ariaLabel}
      className={className}
      src={src}
      controlsList={unrestricted ? 'nodownload' : 'nodownload noplaybackrate noremoteplayback'}
      disablePictureInPicture={!unrestricted}
      onLoadedMetadata={(event) => {
        const element = event.currentTarget
        const resumeAt = Math.max(0, resumeAtSeconds ?? 0)
        if (resumeAt > 0 && resumeAt < element.duration - 1) element.currentTime = resumeAt
      }}
      onSeeking={(event) => {
        if (unrestricted) return
        const element = event.currentTarget
        seeking.current = true
        const target = allowedSeekTarget(watched.current, element.currentTime)
        if (target !== element.currentTime) element.currentTime = target
      }}
      onSeeked={() => {
        seeking.current = false
      }}
      onRateChange={(event) => {
        if (!unrestricted && event.currentTarget.playbackRate !== 1) event.currentTarget.playbackRate = 1
      }}
      onPlay={(event) => onPlay?.(snapshot(event.currentTarget))}
      onTimeUpdate={(event) => {
        const element = event.currentTarget
        watched.current = unrestricted
          ? Math.max(watched.current, element.currentTime)
          : nextWatchedSeconds(watched.current, element.currentTime, seeking.current)
        onProgress(snapshot(element))
      }}
      onPause={(event) => onProgress(snapshot(event.currentTarget))}
      onEnded={(event) => {
        const element = event.currentTarget
        // The last timeupdate can land a fraction before the end; reaching «ended» by playback is the end.
        if (!seeking.current && element.duration - watched.current <= PLAYBACK_STEP_SECONDS + 1) {
          watched.current = element.duration
        }
        const progress = snapshot(element)
        onProgress(progress)
        onEnded?.(progress)
      }}
    />
  )
}
