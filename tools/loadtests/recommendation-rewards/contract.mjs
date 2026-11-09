import { localOrigin, failureKind } from '../../../load-tests/k6/rps-contract.mjs'
export { failureKind }

export function isolatedOrigin(raw) {
  localOrigin(raw)
  if (!/^http:\/\/(127\.0\.0\.1|\[::1\]):[0-9]+$/.test(raw)) throw new Error('Use an explicit HTTP loopback IP and port; no DNS, public host, proxy or override')
  return raw
}

export function scenario(index) {
  return ['duplicate_race', 'offline_reorder', 'foreign_device', 'removed', 'preexisting', 'late121'][index % 100] || 'positive'
}

export function expectedCredit(kind) {
  return ['positive', 'duplicate_race', 'offline_reorder'].includes(kind) ? 400 : 0
}

export function validateFixture(f) {
  if (f?.kind !== 'epharm-isolated-reward-load-v1' || !/^[a-z0-9_]{1,16}$/.test(f.runId || '') ||
      f.expectedDatabase !== `epharm_reward_load_${f.runId}` || !Array.isArray(f.actors) || f.actors.length < 8 || f.actors.length > 1500 ||
      f.bonus !== 200 || f.packages !== 2 || f.price !== 2000 || f.unitSemantics !== 'full_package') throw new Error('Invalid synthetic monetary fixture')
  for (const key of ['deviceKey', 'pharmacistId', 'sessionId', 'saleId', 'sourceDocumentId']) {
    if (new Set(f.actors.map(a => a[key])).size !== f.actors.length) throw new Error(`Duplicate ${key}`)
  }
  f.actors.forEach((a, i) => {
    if (!a.pharmacyId?.startsWith(`reward-${f.runId}-`) || !a.pharmacistId?.startsWith(`reward-${f.runId}-`) ||
        !/^[A-Za-z0-9_-]{32,128}$/.test(a.deviceKey || '') || !/^[0-9a-f]{64}$/.test(a.sourceNamespace || '') ||
        !/^[A-Za-z0-9_-]{1,64}$/.test(a.deviceId || '') || !a.rawUserId || !a.nativeSessionId ||
        a.scenario !== scenario(i) || a.expectedBonus !== expectedCredit(a.scenario)) throw new Error('Invalid actor / expectation')
  })
  return f
}

export function sourceIdentity(a) {
  return { sourceNamespace: a.sourceNamespace, rawUserId: a.rawUserId, nativeSessionId: a.nativeSessionId,
    workstationId: a.deviceId, identityStatus: 'unknown' }
}

export function cartLine(f, a, recommended, qty) {
  return { sourceDocumentId: a.sourceDocumentId, sourceLineId: a.sourceDocumentId * 10 + (recommended ? 2 : 1),
    sku: recommended ? f.recommendSku : f.triggerSku, barcode: recommended ? f.recommendBarcode : f.triggerBarcode,
    qty, unitSemantics: f.unitSemantics }
}

export function shownPayload(f, a, shownAt) {
  return { shownAt, rewardEvidence: { version: 1, checkoutSessionId: a.sessionId,
    observationId: `${a.sessionId}-show`, identity: sourceIdentity(a), cartComplete: true,
    lines: [cartLine(f, a, false, 1), ...(a.scenario === 'preexisting' ? [cartLine(f, a, true, 2)] : [])] } }
}

export function lineChange(f, a, eventId, sequence, observedAt, beforeQty, afterQty) {
  const { qty, ...line } = cartLine(f, a, true, afterQty)
  return { ...line, observationId: `${a.sessionId}-change-${sequence}`, eventId, generationId: `${a.sessionId}-generation-1`,
    sequence, observedAt, identity: sourceIdentity(a), beforeQty, afterQty }
}

export function salePayload(f, a, changes, completedAt) {
  const units = a.scenario === 'removed' ? 0 : f.packages
  const lines = [cartLine(f, a, false, 1), ...(units ? [cartLine(f, a, true, units)] : [])]
  const items = lines.map(l => ({ sku: l.sku, barcode: l.barcode, name: l.sku, qty: l.qty,
    price: l.sku === f.recommendSku ? f.price : 1500, total: l.qty * (l.sku === f.recommendSku ? f.price : 1500) }))
  const total = items.reduce((sum, l) => sum + l.total, 0)
  return { saleId: a.saleId, pharmacistId: a.rawUserId, pharmacistName: a.name, pharmacyId: a.pharmacyId,
    sessionId: a.sessionId, sourceDocumentId: a.sourceDocumentId, captureSource: 'standardn-print-log',
    fiscalId: `${a.deviceId}-${a.fiscalShift}-${a.fiscalNumber}`, cashier: a.name, shift: String(a.fiscalShift),
    cashRegisterRegistrationNumber: a.deviceId, printedAt: completedAt, totalAmount: total, items,
    rewardEvidence: { version: 1, checkoutSessionId: a.sessionId, journalComplete: true, changes,
      completion: { status: 'paid', source: 'standardn-posted-print', completedAt, identity: sourceIdentity(a),
        sourceDocumentId: a.sourceDocumentId, documentStatus: 1, finalDetailsRead: true, signedTotal: -total,
        deviceNumber: a.deviceId, fiscalNumber: a.fiscalNumber, fiscalShift: a.fiscalShift,
        lines: lines.map((l, i) => ({ ...l, signedQuantity: -l.qty, signedPrice: items[i].price, signedAmount: -items[i].total })) } } }
}

export function offerValid(body, f, sessionId) {
  const offer = body?.recommendations?.[0]
  return body?.sessionId === sessionId && body.rewardEvidenceProtocol === 1 && body.conflicts?.length === 0 &&
    body.recommendations?.length === 1 && typeof offer?.eventId === 'string' && offer.eventId.startsWith('rec_') &&
    offer.ruleId === f.ruleId && offer.recommendSku === f.recommendSku && offer.recommendBarcode === f.recommendBarcode &&
    offer.bonus === f.bonus && offer.recommendPrice === f.price && offer.source === 'epharm'
}

export function percentile(values, fraction) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)]
}

export function runConfig(env = process.env) {
  const number = (key, fallback, min, max) => {
    const raw = env[key] ?? String(fallback)
    if (!/^[0-9]+$/.test(raw) || Number(raw) < min || Number(raw) > max) throw new Error(`Invalid ${key}`)
    return Number(raw)
  }
  return { origin: isolatedOrigin(env.BASE_URL), concurrency: number('MAX_INFLIGHT', 50, 2, 150),
    spreadSeconds: number('SPREAD_SECONDS', 60, 1, 300), paymentDelaySeconds: number('PAYMENT_DELAY_SECONDS', 125, 125, 300),
    timeoutMs: number('HTTP_TIMEOUT_MS', 10000, 1000, 30000), requestP95Ms: number('REQUEST_P95_MS', 2000, 50, 10000),
    requestP99Ms: number('REQUEST_P99_MS', 5000, 50, 30000), queueP99Ms: number('QUEUE_P99_MS', 1000, 10, 10000) }
}
