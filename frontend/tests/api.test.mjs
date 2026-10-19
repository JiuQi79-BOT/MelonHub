import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } })
const { api, session } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`)

test('a late 401 from the old token leaves the new session signed in', async () => {
  const storage = new Map()
  globalThis.sessionStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  let expired = 0
  globalThis.window = { dispatchEvent: () => expired++ }
  let respond
  globalThis.fetch = () => new Promise(resolve => { respond = resolve })
  session.save('old')
  const request = api('/admin/users')
  session.save('new')
  respond(new Response('{"code":"UNAUTHORIZED"}', { status: 401 }))
  await assert.rejects(request)
  assert.equal(session.token(), 'new')
  assert.equal(expired, 0)

  const currentRequest = api('/admin/users')
  respond(new Response('{"code":"UNAUTHORIZED"}', { status: 401 }))
  await assert.rejects(currentRequest)
  assert.equal(session.token(), null)
  assert.equal(expired, 1)
})
