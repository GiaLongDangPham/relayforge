import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import ts from 'typescript'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'

// Compile only the two presentation modules in memory; no browser or dependency added.
async function loadComponent(path, replacements = {}) {
  let source = await readFile(new URL(path, import.meta.url), 'utf8')
  source = source.replace(/import styles from '[^']+\.css'/g, 'const styles = {}')
  for (const [from, to] of Object.entries(replacements)) source = source.replace(from, to)
  const compiled = ts.transpileModule(source, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext } }).outputText
    .replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve('react/jsx-runtime')))
  return `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
}
const asyncUrl = await loadComponent('../src/features/ui-state/AsyncState.tsx')
const { ActionResult } = await import(asyncUrl)
const { HistoryReadState } = await import(await loadComponent('../src/features/operations/HistoryReadState.tsx', { "'../ui-state/AsyncState'": JSON.stringify(asyncUrl) }))
const render = (props) => renderToStaticMarkup(createElement(HistoryReadState, {
  resource: 'delivery details', hasData: false, pending: false, failed: false, fetching: false, onRetry() {}, ...props,
}, createElement('p', null, 'Known endpoint enabled')))

test('initial detail failure offers retry, not loading or invented domain data', () => {
  const html = render({ failed: true })
  assert.match(html, /Unable to load delivery details/)
  assert.match(html, /Try again/)
  assert.doesNotMatch(html, /Loading|Known endpoint|paused/)
})
test('background detail failure preserves known data and marks it stale', () => {
  const html = render({ failed: true, hasData: true, fetching: true })
  assert.match(html, /Known endpoint enabled/)
  assert.match(html, /may be out of date/)
  assert.match(html, /disabled/)
})
test('pending and successful reads have distinct truthful content', () => {
  assert.match(render({ pending: true }), /Loading delivery details/)
  assert.doesNotMatch(render({ pending: true }), /Known endpoint/)
  assert.match(render({ hasData: true }), /Known endpoint enabled/)
  assert.doesNotMatch(render({ hasData: true }), /Unable|Loading/)
})
test('action status exists before and after a result arrives', () => {
  for (const content of [null, 'Endpoint saved.']) {
    assert.match(renderToStaticMarkup(createElement(ActionResult, null, content)), /role="status"/)
  }
})
