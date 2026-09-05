// Visual-accessibility regression for the built local artifact. It reads only CSS
// tokens and the public landing page: no owner session or domain API request exists.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'

const source = await readFile(new URL('../src/index.css', import.meta.url), 'utf8')
const tokens = Object.fromEntries([...source.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})/g)].map(([, key, value]) => [key, value]))
function luminance(hex) {
  const values = hex.slice(1).match(/../g).map(value => parseInt(value, 16) / 255)
    .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
  return 0.2126 * values[0] + 0.7152 * values[1] + 0.0722 * values[2]
}
function contrast(foreground, background) {
  const [lighter, darker] = [luminance(tokens[foreground]), luminance(tokens[background])].sort((a, b) => b - a)
  return (lighter + 0.05) / (darker + 0.05)
}
function meets(label, foreground, background, minimum) {
  const ratio = contrast(foreground, background)
  assert.ok(ratio >= minimum, `${label}: ${ratio.toFixed(2)}:1 is below ${minimum}:1`)
  console.log(`PASS ${label}: ${ratio.toFixed(2)}:1`)
}

for (const [label, foreground, background, minimum] of [
  ['body text on page', 'text', 'page', 4.5],
  ['muted text on raised surface', 'muted', 'surface-raised', 4.5],
  ['button text on accent', 'button-text', 'accent', 4.5],
  ['input border on surface', 'border-strong', 'surface', 3],
  ['input border on raised surface', 'border-strong', 'surface-raised', 3],
  ['focus ring on page', 'focus', 'page', 3],
  ['focus ring on raised surface', 'focus', 'surface-raised', 3],
  ['error text on raised surface', 'danger', 'surface-raised', 4.5],
  ['success text on raised surface', 'success', 'surface-raised', 4.5],
]) meets(label, foreground, background, minimum)

assert.match(source, /textarea:focus-visible/)
assert.match(source, /@media \(prefers-reduced-motion: no-preference\)[\s\S]*button:not\(:disabled\):active/)
assert.match(source, /@media \(forced-colors: active\)[\s\S]*outline-color: Highlight/)
console.log('PASS textarea focus, motion opt-in, and forced-colors focus fallback are declared')

const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href)
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
try {
  const normal = await browser.newContext({ viewport: { width: 1366, height: 768 }, reducedMotion: 'no-preference' })
  const normalPage = await normal.newPage()
  await normalPage.goto('http://localhost:5173/')
  const signIn = normalPage.getByRole('link', { name: 'Sign in', exact: true })
  await signIn.focus()
  const normalStyle = await signIn.evaluate(node => {
    const button = document.body.appendChild(document.createElement('button'))
    const transition = getComputedStyle(button).transitionDuration
    button.remove()
    return { outline: getComputedStyle(node).outlineWidth, transition }
  })
  assert.equal(normalStyle.outline, '3px')
  assert.ok(normalStyle.transition.includes('0.15'))
  await normal.close()

  const reduced = await browser.newContext({ viewport: { width: 1366, height: 768 }, reducedMotion: 'reduce' })
  const reducedPage = await reduced.newPage()
  await reducedPage.goto('http://localhost:5173/')
  const reducedStyle = await reducedPage.evaluate(() => {
    const button = document.body.appendChild(document.createElement('button'))
    const transition = getComputedStyle(button).transitionDuration
    button.remove()
    return { motion: matchMedia('(prefers-reduced-motion: reduce)').matches, transition }
  })
  assert.equal(reducedStyle.motion, true)
  assert.equal(Number.parseFloat(reducedStyle.transition), 0.00001)
  await reduced.close()
  console.log('PASS built landing keeps a 3px focus outline and reduces button motion')
} finally {
  await browser.close()
}
