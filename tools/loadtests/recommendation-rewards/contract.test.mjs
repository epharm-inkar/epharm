import test from 'node:test'
import assert from 'node:assert/strict'
import { setTimeout as delay } from 'node:timers/promises'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expectedCredit, isolatedOrigin, lineChange, offerValid, percentile, recommendPayload, runConfig, salePayload,
  scenario, shownPayload, validateFixture } from './contract.mjs'
import { Gate, run } from './run.mjs'

const fixture = () => ({ kind: 'epharm-isolated-reward-load-v1', runId: 'unit', expectedDatabase: 'epharm_reward_load_unit',
  bonus: 200, packages: 2, price: 2000, unitSemantics: 'full_package', triggerSku: 'trigger', triggerBarcode: '4870000000012',
  recommendSku: 'offer', recommendBarcode: '4870000000029', ruleId: 'rule', playlistId: 'playlist', slideId: 'slide',
  actors: Array.from({ length: 8 }, (_, i) => ({ pharmacistId: `reward-unit-person-${i}`, pharmacyId: `reward-unit-ph-${Math.floor(i / 3)}`,
    deviceId: `REWARD-${i}`, deviceKey: `${'a'.repeat(40)}${i}`, sourceNamespace: String(i).padStart(64, '0'),
    rawUserId: `STD-USER-${i}`, nativeSessionId: 100 + i, sourceDocumentId: 1000 + i,
    sessionId: `session-${i}`, saleId: `sale-${i}`, scenario: scenario(i), expectedBonus: expectedCredit(scenario(i)),
    fiscalShift: 99, fiscalNumber: 100 + i, name: `Synthetic ${i}` })) })
const sourceFixture = () => {
  const f = fixture(); f.identityMode = 'source-bound'
  f.actors.forEach((a, i) => Object.assign(a, { iin: String(900000000000 + i),
    name: ['Тестов Алексей', 'Тестова Анна', 'Тестов Марк'][i % 3], proofMethod: i % 2 === 0 ? 'IIN' : 'NAME' }))
  return f
}

test('default HQ mode keeps its existing payload and unknown identity modes are refused', () => {
  const f = fixture(), a = f.actors[0]
  assert.equal(validateFixture(f), f)
  const expected = { pharmacistId: a.rawUserId, pharmacistName: a.name, pharmacyId: a.pharmacyId,
    sessionId: a.sessionId, scannedBarcode: f.triggerBarcode,
    cart: [{ sku: f.triggerSku, barcode: f.triggerBarcode, name: 'Synthetic trigger', qty: 1 }] }
  assert.deepEqual(recommendPayload(f, a), expected)
  assert.deepEqual(recommendPayload({ ...f, identityMode: 'hq' }, a), expected)
  for (const identityMode of ['unknown', 'source', null, true, {}])
    assert.throws(() => validateFixture({ ...f, identityMode }), /identity mode/)
})

test('source-bound mode alternates IIN and full-name proof and sends the exact database namespace', () => {
  const f = sourceFixture()
  assert.equal(validateFixture(f), f)
  assert.equal(f.actors.filter(a => a.proofMethod === 'IIN').length, 4)
  assert.equal(f.actors.filter(a => a.proofMethod === 'NAME').length, 4)
  for (const a of f.actors) {
    const payload = recommendPayload(f, a)
    assert.equal(payload.sourceNamespace, a.sourceNamespace)
    assert.equal(payload.pharmacistId, a.rawUserId)
    assert.equal('iin' in payload, false)
    assert.equal('password' in payload, false)
  }
})

test('source-bound fixtures refuse partial names, ambiguous colleagues, invalid source sessions and proof coverage', () => {
  for (const mutation of [
    f => { f.actors[1].name = 'Кассир' },
    f => { f.actors[1].name = 'Тестов А' },
    f => { f.actors[1].name = f.actors[0].name },
    f => { f.actors[1].proofMethod = 'IIN' },
    f => { f.actors[1].nativeSessionId = '101' },
    f => { f.actors[1].iin = '123' },
  ]) {
    const f = sourceFixture(); mutation(f)
    assert.throws(() => validateFixture(f), /source-bound identity/)
  }
})

test('only explicit loopback IPs can receive load; no production escape or redirect origin', () => {
  for (const raw of ['http://127.0.0.1:19081', 'http://[::1]:8080']) assert.equal(isolatedOrigin(raw), raw)
  for (const raw of ['https://epharm.inkar.kz', 'http://10.10.1.76:8080', 'http://localhost:8080',
    'http://127.0.0.1.evil:80', 'http://127.0.0.1:8080/api', 'http://host.docker.internal:8080',
    'http://user:pass@127.0.0.1:8080', 'http://127.0.0.1:0', 'http://127.0.0.1:8080?override=1']) assert.throws(() => isolatedOrigin(raw))
})
test('real late payment cannot be shortened by an environment setting', () => {
  const env = { BASE_URL: 'http://127.0.0.1:19081' }
  assert.equal(runConfig(env).paymentDelaySeconds, 125)
  for (const PAYMENT_DELAY_SECONDS of ['0', '120', '124', '-1', '125.5']) assert.throws(() => runConfig({ ...env, PAYMENT_DELAY_SECONDS }))
  assert.throws(() => runConfig({ ...env, MAX_INFLIGHT: '1500' }))
})
test('fixture enforces distinct authorizations and explicit 200x2 expectations', () => {
  assert.equal(validateFixture(fixture()).actors.length, 8)
  for (const key of ['deviceKey', 'pharmacistId', 'sessionId', 'saleId', 'sourceDocumentId']) {
    const f = fixture(); f.actors[1][key] = f.actors[0][key]; assert.throws(() => validateFixture(f))
  }
  const f = fixture(); f.actors[2].expectedBonus = 400; assert.throws(() => validateFixture(f))
})
test('default 1500 actor distribution exercises all six controls and exactly1440 payable cases', () => {
  const cases = Array.from({ length: 1500 }, (_, i) => scenario(i))
  assert.equal(cases.filter(c => expectedCredit(c) > 0).length, 1440)
  for (const c of ['duplicate_race', 'offline_reorder', 'foreign_device', 'removed', 'preexisting', 'late121']) assert.equal(cases.filter(x => x === c).length, 15)
})
test('offer must identify the exact real rule, price, bonus and server evidence protocol', () => {
  const f = fixture(), body = { sessionId: 'session', rewardEvidenceProtocol: 1, conflicts: [], recommendations: [{ eventId: 'rec_unit',
    ruleId: f.ruleId, recommendSku: f.recommendSku, recommendBarcode: f.recommendBarcode, bonus: 200, recommendPrice: 2000, source: 'epharm' }] }
  assert.equal(offerValid(body, f, 'session'), true)
  for (const field of ['rewardEvidenceProtocol', 'sessionId']) assert.equal(offerValid({ ...body, [field]: null }, f, 'session'), false)
  assert.equal(offerValid({ ...body, recommendations: [] }, f, 'session'), false)
  assert.equal(offerValid({ ...body, recommendations: [{ ...body.recommendations[0], bonus: 0 }] }, f, 'session'), false)
})
test('shown snapshot explicitly excludes a new offer; preexisting control includes it', () => {
  const f = fixture()
  assert.equal(shownPayload(f, f.actors[0], '2026-01-01T00:00:00Z').rewardEvidence.lines.length, 1)
  const shown = shownPayload(f, f.actors[4], '2026-01-01T00:00:00Z')
  assert.equal(shown.rewardEvidence.lines[1].qty, 2)
  assert.equal(shown.rewardEvidence.identity.rawUserId, f.actors[4].rawUserId)
})
test('immutable journal and signed paid line use identical physical identity', () => {
  const f = fixture(), a = f.actors[0], change = lineChange(f, a, 'rec_unit', 1, '2026-01-01T00:00:01Z', 0, 2)
  const paid = salePayload(f, a, [change], '2026-01-01T00:02:05Z')
  assert.equal(paid.rewardEvidence.completion.signedTotal, -5500)
  const line = paid.rewardEvidence.completion.lines[1]
  assert.equal(line.signedQuantity, -2); assert.equal(line.signedPrice, 2000); assert.equal(line.signedAmount, -4000)
  assert.equal(line.sourceLineId, change.sourceLineId); assert.equal(line.sourceDocumentId, change.sourceDocumentId)
  assert.equal(paid.rewardEvidence.completion.identity.rawUserId, a.rawUserId)
  assert.equal(change.beforeQty, 0); assert.equal(change.afterQty, 2)
  assert.equal(paid.rewardEvidence.completion.status, 'paid'); assert.equal(paid.rewardEvidence.completion.documentStatus, 1)
  assert.equal(paid.rewardEvidence.completion.finalDetailsRead, true)
  assert.equal(paid.captureSource, 'standardn-print-log'); assert.equal(paid.rewardEvidence.completion.source, 'standardn-posted-print')
})
test('removed line cannot reappear in final paid products', () => {
  const f = fixture(), paid = salePayload(f, f.actors[3], [], '2026-01-01T00:02:05Z')
  assert.equal(paid.items.length, 1); assert.equal(paid.totalAmount, 1500)
  assert.equal(paid.rewardEvidence.completion.lines.length, 1)
})
test('bounded async gate never exceeds the socket budget and releases every waiter', async () => {
  const gate = new Gate(3), values = []
  await Promise.all(Array.from({ length: 50 }, (_, i) => (async () => {
    await gate.acquire(); try { values.push(i); await delay(1) } finally { gate.release() }
  })()))
  assert.equal(gate.peak, 3); assert.equal(gate.active, 0); assert.equal(values.length, 50)
  assert.equal(gate.queue.length, 0)
})
test('percentiles use real samples, not a configured target', () => {
  assert.equal(percentile([], .99), null); assert.equal(percentile([5, 1, 3, 4, 2], .95), 5)
})
test('wrong isolated DB acknowledgment aborts before HTTP', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'reward-contract-')), path = join(dir, 'fixture.json')
  await writeFile(path, JSON.stringify(fixture()), { mode: 0o600 })
  try {
    await assert.rejects(run(path, join(dir, 'results'), { BASE_URL: 'http://127.0.0.1:19081', ISOLATED_DATABASE: 'epharm' }), /ISOLATED_DATABASE/)
  } finally { await rm(dir, { recursive: true }) }
})
