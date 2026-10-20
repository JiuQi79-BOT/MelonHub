import { test } from 'node:test'
import assert from 'node:assert/strict'
import { deferred, flush, mountComponent, nextTick, ref } from './component-harness.mjs'

const user = id => ({ id, name: `用户 ${id}`, externalId: `user-${id}`, email: '', enabled: true })
const searchResult = id => ({ users: [user(id)], departments: [], applications: [], roles: [] })

async function globalSearch(t) {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const requests = [], routes = []
  globalThis.window = { addEventListener() {}, removeEventListener() {} }
  const view = await mountComponent('GlobalSearch', { mocks: {
    '../api': { get: (path, query) => { const response = deferred(); requests.push({ path, query, ...response }); return response.promise } },
    '../responsive': { isPhone: ref(true) },
    'vue-router': { useRouter: () => ({ push: route => routes.push(route) }) },
  } })
  t.after(view.unmount)
  return { ...view, requests, routes }
}

test('clearing global search discards a response already in flight', async t => {
  const { state, requests } = await globalSearch(t)
  state.search('old')
  t.mock.timers.tick(300)
  state.search('')
  requests[0].resolve(searchResult(1))
  await flush()
  assert.equal(state.keyword, '')
  assert.deepEqual(state.options, [])
  assert.equal(state.fetching, false)
})

test('typing a new global query invalidates the old response during debounce', async t => {
  const { state, requests } = await globalSearch(t)
  state.search('old')
  t.mock.timers.tick(300)
  state.search('new')
  requests[0].resolve(searchResult(1))
  await flush()
  assert.deepEqual(state.options, [])
  assert.equal(state.fetching, true)
  t.mock.timers.tick(300)
  assert.equal(requests[1].query.q, 'new')
  requests[1].resolve(searchResult(2))
  await flush()
  assert.equal(state.options[0].options[0].value, 'user:2')
})

test('cancelling phone search cancels a pending debounce', async t => {
  const { state, requests } = await globalSearch(t)
  state.search('cancelled')
  state.collapse()
  t.mock.timers.tick(300)
  assert.equal(requests.length, 0)
  assert.deepEqual(state.options, [])
})

test('choosing a result cancels the next pending global query', async t => {
  const { state, requests, routes } = await globalSearch(t)
  state.search('first')
  t.mock.timers.tick(300)
  requests[0].resolve(searchResult(1))
  await flush()
  state.search('next')
  state.choose('user:1')
  t.mock.timers.tick(300)
  assert.equal(requests.length, 1)
  assert.equal(routes[0].path, '/users')
  assert.deepEqual(state.options, [])
})

test('choosing a current user result keeps its external ID in the destination', async t => {
  const { state, requests, routes } = await globalSearch(t)
  state.search('current')
  t.mock.timers.tick(300)
  requests[0].resolve(searchResult(2))
  await flush()
  state.choose('user:2')
  assert.deepEqual(routes, [{ path: '/users', query: { keyword: 'user-2' } }])
  assert.equal(state.keyword, '')
  assert.deepEqual(state.options, [])
})

async function userPicker(t) {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const requests = []
  const view = await mountComponent('UserPicker', {
    props: { departmentId: 10, multiple: true, value: undefined },
    mocks: { '../api': { get: (path, query) => {
      const response = deferred()
      requests.push({ path, query, ...response })
      return response.promise
    } } },
  })
  t.after(view.unmount)
  requests[0].resolve({ items: [] })
  await flush()
  return { ...view, requests }
}

test('user picker discards the old query while the new query is debouncing', async t => {
  const { state, requests } = await userPicker(t)
  state.search('old')
  t.mock.timers.tick(250)
  state.search('new')
  requests[1].resolve({ items: [user(1)] })
  await flush()
  assert.deepEqual(state.results, [])
  t.mock.timers.tick(250)
  requests[2].resolve({ items: [user(2)] })
  await flush()
  assert.deepEqual(state.options.map(o => o.value), [2])
})

test('switching departments cancels the previous department search timer', async t => {
  const { state, props, requests } = await userPicker(t)
  state.search('old-department-query')
  props.departmentId = 20
  await nextTick()
  assert.equal(requests[1].query.departmentId, 20)
  assert.equal(requests[1].query.keyword, '')
  requests[1].resolve({ items: [user(2)] })
  await flush()
  t.mock.timers.tick(250)
  assert.equal(requests.length, 2)
  assert.deepEqual(state.options.map(o => o.value), [2])
})

test('debounced user search keeps the labels of already selected users', async t => {
  const { state, props, requests } = await userPicker(t)
  state.search('first')
  t.mock.timers.tick(250)
  requests[1].resolve({ items: [user(1)] })
  await flush()
  props.value = [1]
  await nextTick()
  state.search('second')
  assert.deepEqual(state.options.map(o => o.value), [1])
  t.mock.timers.tick(250)
  requests[2].resolve({ items: [user(2)] })
  await flush()
  assert.deepEqual(state.options.map(o => o.value), [1, 2])
  assert.match(state.options[0].label, /user-1/)
})
