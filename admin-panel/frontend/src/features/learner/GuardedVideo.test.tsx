import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import { GuardedVideo } from './GuardedVideo'
import { allowedSeekTarget, nextWatchedSeconds, videoProgressToSave } from './guardedVideoRules'

describe('GuardedVideo: the clip has to be watched', () => {
  it('counts ordinary playback and ignores jumps', () => {
    expect(nextWatchedSeconds(10, 10.25, false)).toBe(10.25)
    expect(nextWatchedSeconds(10, 40, false)).toBe(10)
    expect(nextWatchedSeconds(10, 10.25, true)).toBe(10)
    expect(nextWatchedSeconds(10, 5, false)).toBe(10)
  })

  it('lets the viewer rewind but not jump past the watched point', () => {
    expect(allowedSeekTarget(30, 12)).toBe(12)
    expect(allowedSeekTarget(30, 30.5)).toBe(30.5)
    expect(allowedSeekTarget(30, 90)).toBe(30)
  })

  it('snaps a forward drag back, keeps 1x speed and reports watched progress only', () => {
    const onProgress = vi.fn()
    const { container } = render(
      <GuardedVideo src="/lesson.mp4" ariaLabel="Видео урока" onProgress={onProgress} />,
    )
    const video = container.querySelector('video') as HTMLVideoElement
    Object.defineProperty(video, 'duration', { configurable: true, value: 100 })
    expect(video.getAttribute('controlslist')).toContain('noplaybackrate')

    video.currentTime = 1
    fireEvent.timeUpdate(video)
    video.currentTime = 2
    fireEvent.timeUpdate(video)
    expect(onProgress).toHaveBeenLastCalledWith({ durationSeconds: 100, watchedSeconds: 2, watchedPct: 2 })

    video.currentTime = 95
    fireEvent.seeking(video)
    expect(video.currentTime).toBe(2)
    fireEvent.seeked(video)

    video.playbackRate = 2
    fireEvent.rateChange(video)
    expect(video.playbackRate).toBe(1)
  })

  it('a finished lesson plays without limits', () => {
    const { container } = render(
      <GuardedVideo src="/lesson.mp4" ariaLabel="Видео урока" unrestricted onProgress={() => {}} />,
    )
    const video = container.querySelector('video') as HTMLVideoElement
    Object.defineProperty(video, 'duration', { configurable: true, value: 100 })
    video.currentTime = 95
    fireEvent.seeking(video)
    expect(video.currentTime).toBe(95)
  })
})

describe('a video lesson with reading and a self-check', () => {
  it('saves the watched figure just under the threshold until both are done', () => {
    expect(videoProgressToSave(100, 95, false)).toBe(94)
    expect(videoProgressToSave(60, 95, false)).toBe(60)
    expect(videoProgressToSave(100, 95, true)).toBe(100)
    expect(videoProgressToSave(0, 1, false)).toBe(0)
  })
})
