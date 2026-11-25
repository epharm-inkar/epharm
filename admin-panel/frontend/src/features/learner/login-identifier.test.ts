import { describe, expect, it } from 'vitest'
import { parseLoginIdentifier } from './login-identifier'

describe('parseLoginIdentifier', () => {
  it.each(['061007500024', ' 061007 500024 ', '061007-500024'])('reads %j as an IIN', (typed) => {
    expect(parseLoginIdentifier(typed)).toEqual({ iin: '061007500024' })
  })

  it.each([
    '+77015551234',
    '+7 701 555 12 34',
    '+7 (701) 555-12-34',
    '87015551234',
    '8 701 555 12 34',
    '8 (701) 555-12-34',
    '77015551234',
    '7015551234',
    '701 555 12 34',
  ])('reads %j as the phone +77015551234', (typed) => {
    expect(parseLoginIdentifier(typed)).toEqual({ phone: '+77015551234' })
  })

  it.each([
    '',
    '   ',
    '06100750002',
    '0610075000241',
    '+7 701 555 12 3',
    '+8 701 555 12 34',
    '+061007500024',
    '+1 202 555 0100',
    'ИИН 061007500024',
    'user@example.com',
  ])('does not accept %j yet', (typed) => {
    expect(parseLoginIdentifier(typed)).toBeNull()
  })
})
