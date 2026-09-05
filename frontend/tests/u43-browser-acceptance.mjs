// Isolated UI acceptance against the built localhost artifact. All API traffic is mocked;
// no owner credentials, real keys, database writes or browser storage files are used.
// Run with PLAYWRIGHT_MODULE pointing to an existing playwright/index.mjs installation.
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href)
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
const context = await browser.newContext({ hasTouch: true, viewport: { width: 1366, height: 768 }, reducedMotion: 'reduce' })
const page = await context.newPage()
const base = 'http://localhost:5173'
const now = '2026-09-05T09:00:00Z'
const projects = Array.from({ length: 25 }, (_, i) => ({ id: `project-${i}`, name: i ? `Acceptance project ${i}` : 'U4.3 isolated fixture — long project name for keyboard and responsive acceptance', version: 0, createdAt: now, updatedAt: now }))
const key = { id: 'key-1', displayName: 'Disposable test publisher', keyHint: 'test-only', createdAt: now, revokedAt: null }
const event = { id: 'event-1', eventType: 'invoice.paid', acceptedAt: now, deliveryCount: 3 }
const delivery = { id: 'delivery-1', eventId: event.id, endpointId: 'endpoint-1', displayStatus: 'SUCCEEDED', state: 'SUCCEEDED', attemptCount: 1, createdAt: now, terminalAt: now, nextAttemptAt: null, replayOfDeliveryId: null }
const deliveries = [
  delivery,
  { id: 'delivery-2', eventId: event.id, endpointId: 'endpoint-2', displayStatus: 'SUCCEEDED', state: 'SUCCEEDED', attemptCount: 1, createdAt: now, terminalAt: now, nextAttemptAt: null, replayOfDeliveryId: null },
  { id: 'delivery-3', eventId: event.id, endpointId: 'endpoint-3', displayStatus: 'EXHAUSTED', state: 'EXHAUSTED', attemptCount: 5, createdAt: now, terminalAt: now, nextAttemptAt: null, replayOfDeliveryId: null },
]
const attempt = { id: 'attempt-1', attemptNumber: 1, status: 'SUCCEEDED', startedAt: now, finishedAt: now, httpStatus: 204, failureCode: null, latencyMilliseconds: 10 }
let revokeFails = true
let detailFails = true
let revokeCount = 0
let keyReads = 0
const unexpected = []
const pageErrors = []
page.on('pageerror', e => pageErrors.push(e.message))
await context.route('**/api/**', async route => {
  const url = new URL(route.request().url())
  const path = url.pathname
  const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  if (path.endsWith('/auth/me')) return json({ ownerId: 'test-owner', loginName: 'fixture owner' })
  if (path.endsWith('/auth/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'fixture-only' })
  if (path === '/api/v1/projects') return json({ items: url.searchParams.has('cursor') ? projects.slice(20) : projects.slice(0, 20), nextCursor: url.searchParams.has('cursor') ? null : 'page-2' })
  if (path.endsWith('/revoke')) {
    revokeCount++
    if (revokeFails) return json({ title: 'Controlled failure' }, 503)
    key.revokedAt = now
    return json(key)
  }
  if (path.endsWith('/api-keys')) { keyReads++; return json({ items: [key], nextCursor: null }) }
  if (path.endsWith('/endpoints')) return json({ items: [], nextCursor: null })
  if (path.endsWith('/delivery-updates')) return route.fulfill({ status: 204 })
  if (path.endsWith('/delivery-health')) return json({ observedAt: now, dueEnabledCount: 0, oldestDueEnabledAt: null, retryScheduledCount: 0, inFlightCount: 0, pausedCount: 0, exhaustedCount: 1 })
  if (path.endsWith('/events')) return json({ items: [event], nextCursor: null })
  if (path.endsWith('/deliveries')) return json({ items: deliveries, nextCursor: null })
  if (path.endsWith('/attempts')) return json([attempt])
  if (detailFails) return json({ title: 'Controlled detail failure' }, 503)
  if (path.endsWith('/events/event-1')) return json({ event, payload: { invoiceId: 'fixture' }, deliverySummary: { totalCount: 3, activeCount: 0, succeededCount: 2, failedPermanentCount: 0, exhaustedCount: 1 } })
  if (path.endsWith('/deliveries/delivery-1')) return json({ delivery, eventType: event.eventType, endpoint: { endpointId: 'endpoint-1', name: 'Fixture receiver', enabled: true }, latestAttempt: attempt, replayDeliveryIds: [] })
  if (path.endsWith('/attempts/attempt-1')) return json({ attempt, destinationFingerprintVersion: 1, destinationFingerprint: 'fixture-only', responsePreview: 'Fixture response', responseTruncated: false, lateDiagnostic: null })
  unexpected.push(path)
  return json({}, 500)
})
const waitText = text => page.getByText(text, { exact: true }).waitFor({ timeout: 15000 })
const focusText = () => page.evaluate(() => document.activeElement?.textContent?.trim())
const report = message => console.log(`PASS ${message}`)
try {
  await page.goto(`${base}/app`)
  for (const resource of ['event details', 'delivery details', 'attempt diagnostic']) await waitText(`Unable to load ${resource}.`)
  assert.equal(await page.getByText('Endpoint: undefined · paused', { exact: true }).count(), 0)
  detailFails = false
  for (const resource of ['event details', 'delivery details', 'attempt diagnostic']) {
    const alert = page.getByRole('alert').filter({ hasText: `Unable to load ${resource}.` })
    if (await alert.count()) await alert.getByRole('button').click()
  }
  await waitText('Fixture response')
  report('event/delivery/attempt HTTP 503 -> explicit errors -> retry recovery')

  await page.getByRole('button', { name: 'View event history', exact: true }).click()
  assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('aria-label')), 'Events')
  report('health CTA focuses the event history region')
  const deliveryList = page.locator('section[aria-label="Deliveries"]')
  await deliveryList.getByRole('heading', { name: '3 deliveries', exact: true }).waitFor()
  assert.equal(await deliveryList.getByRole('button', { name: /Succeeded/ }).count(), 2)
  report('all delivery outcomes remain visible for the selected event')
  const replayCandidate = deliveryList.getByRole('button', { name: 'Exhausted delivery, needs replay', exact: true })
  assert.equal(await replayCandidate.count(), 1)
  assert.equal(await replayCandidate.getAttribute('aria-pressed'), 'false')
  report('health CTA focuses event history; exactly one clearly named exhausted delivery is the replay candidate')

  await page.getByRole('button', { name: 'API keys', exact: true }).click()
  await page.getByRole('button', { name: `Revoke ${key.displayName}`, exact: true }).click()
  await waitText('API-key revocation could not be confirmed.')
  assert.equal(revokeCount, 1)
  const readsBefore = keyReads
  await page.getByRole('alert').filter({ hasText: 'revocation could not' }).getByRole('button').click()
  await page.waitForFunction(() => !document.querySelector('button:disabled')?.textContent?.includes('Retrying'))
  assert.ok(keyReads > readsBefore)
  assert.equal(revokeCount, 1, 'status retry must never reissue revoke')
  revokeFails = false
  await page.getByRole('button', { name: `Revoke ${key.displayName}`, exact: true }).click()
  await waitText('Revoked')
  await waitText('API key revoked. Publishers using it can no longer authenticate.')
  assert.equal(revokeCount, 2)
  report('revoke HTTP failure, read-only status retry, explicit successful revoke')

  await page.goto(`${base}/app`)
  await page.getByRole('button', { name: /^Project:/ }).waitFor()
  await page.keyboard.press('Tab')
  assert.equal(await focusText(), 'Skip to workspace')
  await page.keyboard.press('Enter')
  await page.keyboard.press('Tab')
  assert.match(await focusText(), /^Project:/)
  await page.keyboard.press('Enter')
  const dialog = page.getByRole('dialog', { name: 'Choose a project' })
  await dialog.waitFor()
  assert.equal(await focusText(), 'Close')
  await page.keyboard.press('Shift+Tab')
  assert.equal(await focusText(), 'Load more projects')
  await page.keyboard.press('Tab')
  assert.equal(await focusText(), 'Close')
  await dialog.getByRole('button', { name: 'Load more projects' }).click()
  await dialog.getByRole('button', { name: 'Acceptance project 24', exact: true }).waitFor()
  for (let i = 0; i < 28; i++) {
    await page.keyboard.press('Tab')
    assert.ok(await page.evaluate(() => !!document.activeElement?.closest('dialog')), 'modal focus must stay inside')
  }
  await page.keyboard.press('Escape')
  assert.match(await focusText(), /^Project:/)
  report('skip link, modal Enter, Tab/Shift+Tab trap, 25 projects, Escape and focus restoration')

  await page.getByRole('button', { name: /^Project:/ }).click()
  await dialog.getByRole('button', { name: 'Acceptance project 24', exact: true }).click()
  await page.getByRole('heading', { name: 'Acceptance project 24', exact: true }).waitFor()
  assert.equal(new URL(page.url()).searchParams.get('project'), 'project-24')
  await page.reload()
  await page.getByRole('heading', { name: 'Acceptance project 24', exact: true }).waitFor()
  assert.equal(new URL(page.url()).searchParams.get('project'), 'project-24')
  report('selected later-page project persists across reload without showing the first workspace')

  await page.goto(`${base}/app?project=missing-project`)
  await page.getByRole('heading', { name: projects[0].name, exact: true }).waitFor()
  await page.waitForFunction(() => new URL(location.href).searchParams.get('project') === 'project-0')
  report('unknown project URL safely falls back to the first owned project')

  for (const [width, height] of [[320, 740], [683, 384], [1366, 768], [1440, 900]]) {
    await page.setViewportSize({ width, height })
    for (const label of ['Deliveries', 'Endpoints', 'API keys', 'Test events']) {
      await page.getByRole('button', { name: label, exact: true }).click()
      if (label === 'Endpoints') await page.getByRole('button', { name: 'New endpoint', exact: true }).click()
      const geometry = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth }))
      assert.equal(geometry.width, width)
      assert.ok(geometry.scroll <= width, `${label} overflow at ${width}: ${geometry.scroll}`)
    }
    await page.getByRole('button', { name: /^Project:/ }).click()
    assert.ok(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth), `dialog overflow at ${width}`)
    await page.keyboard.press('Escape')
    report(`all four panels + open endpoint form + project dialog reflow at ${width}x${height}`)
  }
  await page.getByRole('button', { name: 'Endpoints', exact: true }).click()
  await page.getByRole('button', { name: 'New endpoint', exact: true }).click()
  const advanced = page.locator('details').filter({ has: page.getByText('Advanced delivery settings', { exact: true }) })
  assert.equal(await advanced.evaluate(node => node.open), false)
  await advanced.getByText('Advanced delivery settings', { exact: true }).click()
  assert.equal(await advanced.evaluate(node => node.open), true)
  const destinationInfo = page.getByRole('button', { name: 'More information about Destination URL', exact: true })
  await destinationInfo.focus()
  const tooltip = page.getByRole('tooltip').filter({ hasText: 'RelayForge sends POST requests to this URL.' })
  await tooltip.waitFor()
  await page.keyboard.press('Escape')
  await page.waitForFunction(() => getComputedStyle(document.querySelector('[role="tooltip"]')).visibility === 'hidden')
  await page.setViewportSize({ width: 320, height: 740 })
  await destinationInfo.tap()
  await tooltip.waitFor()
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'touch tooltip must not create horizontal overflow')
  await page.keyboard.press('Escape')
  report('optional retry setting stays collapsed; destination help opens by keyboard and touch, then closes with Escape')

  await page.getByRole('button', { name: 'Test events', exact: true }).click()
  const ax = await page.locator('main').ariaSnapshot()
  assert.match(ax, /navigation "Project views"/)
  assert.match(ax, /textbox "Project API key"/)
  assert.match(ax, /combobox "Event type"/)
  assert.match(ax, /textbox "Event data"/)
  await page.getByRole('button', { name: 'Send new event', exact: true }).click()
  const validation = await page.locator('input[name="project-api-key"]').evaluate(el => ({ focused: document.activeElement === el, invalid: el.getAttribute('aria-invalid'), description: el.getAttribute('aria-describedby').split(' ').map(id => document.getElementById(id)?.textContent).join(' ') }))
  assert.ok(validation.focused)
  assert.equal(validation.invalid, 'true')
  assert.match(validation.description, /Paste a project API key/)
  assert.equal(await page.locator('[tabindex]').evaluateAll(elements => elements.some(el => Number(el.getAttribute('tabindex')) > 0)), false)
  report('AX landmarks/control names/validation description and focus; no positive tabindex')
  assert.deepEqual(pageErrors, [])
  assert.deepEqual(unexpected, [])
  report('no uncaught browser errors or unexpected fixture routes')
  console.log('LIMIT: 683px is 1366px/2 reflow evidence, NOT native 200% browser zoom. AX checks are NOT screen-reader speech evidence.')
} finally {
  await browser.close()
}
