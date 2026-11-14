#!/usr/bin/env node
import { readFile, writeFile, mkdir, stat } from 'node:fs/promises'
import { monitorEventLoopDelay, performance } from 'node:perf_hooks'
import { setTimeout as delay } from 'node:timers/promises'
import { pathToFileURL } from 'node:url'
import { failureKind, lineChange, offerValid, percentile, recommendPayload, runConfig, salePayload, shownPayload, validateFixture } from './contract.mjs'

// 1500 waiting actors use timers, not 1500 simultaneous sockets or operating-system threads.
export class Gate {
  constructor(limit) { this.limit = limit; this.active = 0; this.peak = 0; this.queue = []; this.peakQueue = 0 }
  async acquire() {
    if (this.active >= this.limit) {
      await new Promise(resolve => { this.queue.push(resolve); this.peakQueue = Math.max(this.peakQueue, this.queue.length) })
    } else { this.active++; this.peak = Math.max(this.peak, this.active) }
  }
  release() {
    if (this.queue.length) this.queue.shift()()
    else this.active--
  }
}

export async function run(fixturePath, output, env = process.env) {
  const config = runConfig(env)
  const metadata = await stat(fixturePath)
  if ((metadata.mode & 0o077) !== 0 || !metadata.isFile()) throw new Error('Fixture must be a private regular file (0600)')
  const f = validateFixture(JSON.parse(await readFile(fixturePath, 'utf8')))
  if (env.ISOLATED_DATABASE !== f.expectedDatabase) throw new Error('ISOLATED_DATABASE must match the generated fixture; never use a production database')
  await mkdir(output, { recursive: false, mode: 0o700 })
  const gate = new Gate(config.concurrency)
  const metrics = { requests: {}, errors: {}, errorSamples: [], completed: 0, active: 0, peakActiveActors: 0,
    authenticatedActive: 0, peakAuthenticatedActors: 0,
    queueMs: [], schedulingLagMs: [], oldestAddToPayMs: 0, minAddToPayMs: null, backgroundCycles: 0 }
  const evidence = []
  const resourceSamples = []
  async function resources() {
    try {
      const response = await fetch(config.origin + '/actuator/prometheus', { redirect: 'manual', signal: AbortSignal.timeout(config.timeoutMs) })
      if (response.status !== 200) throw new Error('Metrics unavailable')
      const lines = (await response.text()).split('\n').filter(line => /^(process_cpu_usage|process_cpu_time_ns_total|process_resident_memory_bytes|jvm_memory_used_bytes|hikaricp_connections_(active|pending|max|timeout_total)|hikaricp_connections_acquire_seconds_(count|sum|max))([{ ]|$)/.test(line))
      if (!lines.some(line => /^process_cpu_usage[{ ]/.test(line)) || !lines.some(line => line.startsWith('hikaricp_connections_active'))) throw new Error('CPU / Hikari metrics absent')
      resourceSamples.push({ at: new Date().toISOString(), prometheus: lines })
    } catch { fail('resource_metrics_unavailable', 'metrics') }
  }
  const loop = monitorEventLoopDelay({ resolution: 20 }); loop.enable()
  const started = performance.now()
  let aborted = false
  const abort = () => { aborted = true }
  process.once('SIGINT', abort); process.once('SIGTERM', abort)
  const end = started + (config.spreadSeconds + config.paymentDelaySeconds + 180) * 1000
  function fail(kind, step, status = 0) {
    metrics.errors[kind] = (metrics.errors[kind] || 0) + 1
    if (metrics.errorSamples.length < 20) metrics.errorSamples.push({ kind, step, status })
  }
  async function sleepUntil(at) {
    if (aborted || performance.now() > end) throw new Error('Run interrupted or exceeded bounded deadline')
    if (at > performance.now()) await delay(at - performance.now())
    if (aborted) throw new Error('Run interrupted')
  }
  async function http(a, step, method, path, payload, accept = [200]) {
    if (aborted) throw new Error('Run interrupted')
    const queued = performance.now(); await gate.acquire()
    metrics.queueMs.push(performance.now() - queued)
    const at = performance.now()
    let response, body
    try {
      response = await fetch(config.origin + path, { method, redirect: 'manual', signal: AbortSignal.timeout(config.timeoutMs),
        headers: { 'X-Posm-Key': a.deviceKey, 'X-Device-Id': a.deviceId, ...(payload ? { 'Content-Type': 'application/json' } : {}) },
        body: payload ? JSON.stringify(payload) : undefined })
      const raw = await response.text()
      try { body = JSON.parse(raw) } catch { body = null }
      if (!accept.includes(response.status)) {
        fail(failureKind(response.status) || 'unexpected_status', step, response.status)
        throw new Error(`Unexpected ${step} HTTP ${response.status}`)
      }
    } catch (error) {
      if (!response) fail('transport', step)
      throw error
    } finally {
      const sample = metrics.requests[step] ||= { statuses: {}, ms: [] }
      const status = response?.status || 0
      sample.statuses[status] = (sample.statuses[status] || 0) + 1
      sample.ms.push(performance.now() - at); gate.release()
    }
    return body
  }
  const semantic = (pass, step) => {
    if (!pass) { fail('semantic', step); throw new Error(`Semantic failure: ${step}`) }
  }
  const show = async (a, eventId, payload, foreign = false) => {
    const sender = foreign ? f.actors.find(other => other.pharmacyId !== a.pharmacyId) : a
    if (!sender) throw new Error('Fixture needs a second pharmacy for the foreign-device control')
    // Existing legacy ACK may intentionally return 200 for a foreign event. SQL MUST prove it remained ineligible.
    const body = await http(sender, foreign ? 'shown_foreign_control' : 'shown', 'POST',
      `/api/posm/recommendations/${encodeURIComponent(eventId)}/shown`, payload, foreign ? [200, 403, 404] : [200])
    if (!foreign) semantic(body?.eventId === eventId && body.ok === true, 'shown_ack')
  }
  const sell = async (a, payload, step = 'sale') => {
    const result = await http(a, step, 'POST', '/api/posm/sales', payload)
    semantic(result?.saleId === payload.saleId && typeof result.accepted === 'boolean', 'sale_response')
    return result
  }
  const background = async a => {
    const query = new URLSearchParams({ pharmacyId: a.pharmacyId, deviceId: a.deviceId, monitorCount: '2', appVersion: 'reward-load' })
    const hb = await http(a, 'heartbeat', 'POST', `/api/posm/heartbeat?${query}`)
    semantic(hb?.ok === true && hb.deviceId === a.deviceId, 'heartbeat')
    const playlist = await http(a, 'playlist', 'GET', `/api/posm/playlists/active?pharmacyId=${encodeURIComponent(a.pharmacyId)}`)
    semantic(playlist?.playlistId === f.playlistId && playlist.slides?.length === 1 && playlist.slides[0].id === f.slideId, 'positive_playlist')
    metrics.backgroundCycles++
  }
  const actor = async (a, index) => {
    const scheduled = started + index * config.spreadSeconds * 1000 / f.actors.length
    await sleepUntil(scheduled)
    metrics.schedulingLagMs.push(Math.max(0, performance.now() - scheduled))
    metrics.active++; metrics.peakActiveActors = Math.max(metrics.peakActiveActors, metrics.active)
    let backgroundFailure = null, done = false, authenticated = false
    const backgroundStop = new AbortController()
    const backgroundTask = (async () => {
      for (let tick = 0; !done; tick++) {
        try { await delay(Math.max(0, scheduled + tick * 60000 - performance.now()), undefined, { signal: backgroundStop.signal }) }
        catch (error) { if (error.name === 'AbortError') return; throw error }
        if (done) break
        try { await background(a) } catch (error) { backgroundFailure = error; break }
      }
    })()
    try {
      const body = await http(a, 'recommend', 'POST', '/api/posm/recommend', recommendPayload(f, a))
      semantic(offerValid(body, f, a.sessionId), 'positive_offer_and_evidence_protocol')
      authenticated = true; metrics.authenticatedActive++
      metrics.peakAuthenticatedActors = Math.max(metrics.peakAuthenticatedActors, metrics.authenticatedActive)
      const eventId = body.recommendations[0].eventId
      const shownAt = new Date().toISOString(), shownMono = performance.now()
      const shown = shownPayload(f, a, shownAt)
      if (a.scenario !== 'offline_reorder') await show(a, eventId, shown, a.scenario === 'foreign_device')
      const changes = []
      if (a.scenario !== 'preexisting') {
        await sleepUntil(shownMono + (a.scenario === 'late121' ? 121000 : 20))
        changes.push(lineChange(f, a, eventId, 1, new Date().toISOString(), 0, f.packages))
        if (a.scenario === 'removed') {
          await sleepUntil(performance.now() + 20)
          changes.push(lineChange(f, a, eventId, 2, new Date().toISOString(), f.packages, 0))
        }
      }
      await sleepUntil(shownMono + config.paymentDelaySeconds * 1000)
      const completedAt = new Date().toISOString()
      semantic(Date.parse(completedAt) - Date.parse(shownAt) > 120000, 'real_late_payment_clock')
      const payload = salePayload(f, a, changes, completedAt)
      if (a.scenario === 'duplicate_race') {
        const results = await Promise.all([sell(a, payload), sell(a, payload, 'sale_race')])
        semantic(results.some(r => r.accepted), 'first_sale_accepted')
        await sell(a, { ...payload, saleId: `${a.saleId}-alias` }, 'sale_physical_alias')
      } else {
        const first = await sell(a, payload)
        semantic(first.accepted === true, 'first_sale_accepted')
      }
      if (a.scenario === 'offline_reorder') {
        await show(a, eventId, shown)
        await sell(a, payload, 'sale_offline_replay')
      }
      // Every actor replays its completed outbox item; HTTP success alone is not proof of exactly-once credit.
      await sell(a, payload, 'sale_replay')
      if (changes.length) {
        const ms = Date.parse(completedAt) - Date.parse(changes[0].observedAt)
        metrics.oldestAddToPayMs = Math.max(metrics.oldestAddToPayMs, ms)
        metrics.minAddToPayMs = Math.min(metrics.minAddToPayMs ?? ms, ms)
      }
      evidence.push({ actor: index, pharmacistId: a.pharmacistId, scenario: a.scenario, eventId,
        identityMode: f.identityMode ?? 'hq', ...(f.identityMode === 'source-bound' ? { proofMethod: a.proofMethod } : {}),
        shownAt, addedAt: changes[0]?.observedAt ?? null, completedAt, expectedBonus: a.expectedBonus })
      if (backgroundFailure) throw backgroundFailure
      metrics.completed++
    } catch (error) {
      // No request bodies, device keys, phone numbers or profiles are printed in diagnostics.
      fail('actor_incomplete', a.scenario)
    } finally {
      done = true; backgroundStop.abort(); metrics.active--
      if (authenticated) metrics.authenticatedActive--
      await backgroundTask
    }
  }
  let resourcePending = null
  const sampleResources = () => resourcePending ||= resources().finally(() => { resourcePending = null })
  await sampleResources()
  const resourceTimer = setInterval(() => { void sampleResources() }, 5000)
  await Promise.all(f.actors.map(actor))
  clearInterval(resourceTimer)
  await sampleResources()
  loop.disable(); process.removeListener('SIGINT', abort); process.removeListener('SIGTERM', abort)
  const requests = Object.fromEntries(Object.entries(metrics.requests).map(([key, value]) => [key,
    { count: value.ms.length, statuses: value.statuses, p50Ms: percentile(value.ms, .5), p95Ms: percentile(value.ms, .95),
      p99Ms: percentile(value.ms, .99), maxMs: Math.max(...value.ms) }]))
  const violations = []
  if (metrics.completed !== f.actors.length) violations.push('incomplete_actors')
  if (metrics.peakAuthenticatedActors !== f.actors.length) violations.push('not_all_authenticated_actors_simultaneously_active')
  if (Object.keys(metrics.errors).length) violations.push('request_or_semantic_errors')
  for (const [step, sample] of Object.entries(requests)) {
    if (sample.p95Ms > config.requestP95Ms || sample.p99Ms > config.requestP99Ms) violations.push(`latency_${step}`)
  }
  if (percentile(metrics.queueMs, .99) > config.queueP99Ms) violations.push('generator_queue_pressure')
  const report = { state: violations.length ? 'failed' : 'http_complete_requires_database_oracle', runId: f.runId,
    identityMode: f.identityMode ?? 'hq', offeredIdentityProofs: f.identityMode === 'source-bound'
      ? { IIN: f.actors.filter(a => a.proofMethod === 'IIN').length, NAME: f.actors.filter(a => a.proofMethod === 'NAME').length }
      : { HQ: f.actors.length },
    fixtureDatabase: f.expectedDatabase, config, elapsedSeconds: (performance.now() - started) / 1000,
    offeredActors: f.actors.length, completedActors: metrics.completed, peakActiveActors: metrics.peakActiveActors,
    peakAuthenticatedActors: metrics.peakAuthenticatedActors,
    peakHttpInflight: gate.peak, peakHttpQueue: gate.peakQueue, expectedTotalBonus: f.actors.reduce((sum, a) => sum + a.expectedBonus, 0),
    errors: metrics.errors, errorSamples: metrics.errorSamples, requests, backgroundCycles: metrics.backgroundCycles,
    realPaymentDelaySeconds: config.paymentDelaySeconds, minAddToPayMs: metrics.minAddToPayMs, maxAddToPayMs: metrics.oldestAddToPayMs,
    resourceSamples: resourceSamples.length,
    generator: { queueP95Ms: percentile(metrics.queueMs, .95), queueP99Ms: percentile(metrics.queueMs, .99),
      startLagP99Ms: percentile(metrics.schedulingLagMs, .99), eventLoopP99Ms: loop.percentile(99) / 1e6,
      maxRssBytes: process.resourceUsage().maxRSS * 1024 }, violations,
    limitations: ['Synthetic full_package adapter fixture; real Standard-N unit semantics not validated',
      'Logical authenticated active actors and concurrent HTTP requests are different metrics',
      'HTTP acceptance does not prove credit: guarded verify.sql must also pass',
      'Local laptop throughput is not production capacity proof; no mobile tests executed'] }
  await writeFile(`${output}/summary.json`, JSON.stringify(report, null, 2) + '\n', { mode: 0o600, flag: 'wx' })
  await writeFile(`${output}/actor-evidence.private.json`, JSON.stringify(evidence, null, 2) + '\n', { mode: 0o600, flag: 'wx' })
  await writeFile(`${output}/resources.private.json`, JSON.stringify(resourceSamples, null, 2) + '\n', { mode: 0o600, flag: 'wx' })
  console.log(JSON.stringify(report, null, 2))
  return violations.length ? 1 : 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [fixture, output] = process.argv.slice(2)
  if (!fixture || !output) { console.error('Usage: BASE_URL=http://127.0.0.1:19081 ISOLATED_DATABASE=... node run.mjs fixture.private.json NEW_OUTPUT_DIR'); process.exitCode = 2 }
  else try { process.exitCode = await run(fixture, output) } catch (error) { console.error(error.message); process.exitCode = 2 }
}
