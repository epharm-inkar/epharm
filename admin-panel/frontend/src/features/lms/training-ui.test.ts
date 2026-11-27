import { describe, expect, it } from 'vitest'
import type { TrainingAssignmentDto, TrainingAssignmentStatus } from '@/lib/api-types'
import { STAGE_STATUS_LABEL, programProgress } from './training-ui'

const assigned = (programId: string, programName: string, status: TrainingAssignmentStatus) =>
  ({ programId, programName, status }) as TrainingAssignmentDto

describe('program progress', () => {
  it('splits each program into completed, in progress, not started and overdue, without cancelled', () => {
    const rows = programProgress([
      assigned('p1', 'Петровакс', 'completed'),
      assigned('p1', 'Петровакс', 'waiting_test'),
      assigned('p1', 'Петровакс', 'not_started'),
      assigned('p1', 'Петровакс', 'overdue'),
      assigned('p1', 'Петровакс', 'cancelled'),
      assigned('p2', 'Касса ePharm', 'completed'),
    ])
    expect(rows).toEqual([
      { programId: 'p1', name: 'Петровакс', total: 4, completed: 1, inProgress: 1, notStarted: 1, overdue: 1 },
      { programId: 'p2', name: 'Касса ePharm', total: 1, completed: 1, inProgress: 0, notStarted: 0, overdue: 0 },
    ])
  })

  it('returns nothing when there are no assignments', () => {
    expect(programProgress([])).toEqual([])
  })
})

describe('training UI labels', () => {
  it('localizes every stage status shown in the results table', () => {
    expect(STAGE_STATUS_LABEL).toEqual({
      locked: 'Заблокирован',
      available: 'Доступен',
      in_progress: 'В процессе',
      waiting_review: 'На проверке',
      completed: 'Завершён',
      failed: 'Не пройден',
      skipped: 'Пропущен',
    })
  })
})
