import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import { GuardedVideo } from './GuardedVideo'
import {
  allowedSeekTarget,
  nextWatchedSeconds,
  videoProgressToSave,
  watchedFromPlayed,
} from './guardedVideoRules'

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

describe('a busy page: timeupdate comes seconds late', () => {
  it('takes what the browser recorded as played, never a forward seek', () => {
    // The clip played on while the page was busy: one range from the start.
    expect(watchedFromPlayed([[0, 37.5]], 2)).toBe(37.5)
    // A forward drag leaves a blip far ahead: not connected to what was watched.
    expect(watchedFromPlayed([[0, 10], [90, 90.2]], 10)).toBe(10)
    // A resumed visit plays on from the saved point.
    expect(watchedFromPlayed([[30, 45]], 30)).toBe(45)
    // Ranges come in any order and chain within the tolerance.
    expect(watchedFromPlayed([[12, 20], [0, 12.4]], 0)).toBe(20)
    // A gap means a jump: what lies beyond it does not count.
    expect(watchedFromPlayed([[0, 10], [11.5, 20]], 10)).toBe(10)
    expect(watchedFromPlayed([], 7)).toBe(7)
  })

  it('a late timeupdate after seconds of playback still counts', () => {
    const onProgress = vi.fn()
    const { container } = render(
      <GuardedVideo src="/lesson.mp4" ariaLabel="Видео урока" onProgress={onProgress} />,
    )
    const video = container.querySelector('video') as HTMLVideoElement
    Object.defineProperty(video, 'duration', { configurable: true, value: 100 })
    Object.defineProperty(video, 'played', {
      configurable: true,
      value: { length: 1, start: () => 0, end: () => 40 },
    })
    video.currentTime = 40
    fireEvent.timeUpdate(video)
    expect(onProgress).toHaveBeenLastCalledWith({ durationSeconds: 100, watchedSeconds: 40, watchedPct: 40 })
    // The page still refuses a jump past it.
    video.currentTime = 95
    fireEvent.seeking(video)
    expect(video.currentTime).toBe(40)
  })
})
