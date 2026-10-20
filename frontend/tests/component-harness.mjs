import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'

const require = createRequire(import.meta.url)
export const { ref, reactive, nextTick } = require('vue')

/** Mount the actual script setup with Vue watchers and lifecycle, without a browser or UI-library rendering. */
export async function mountComponent(name, { props = {}, mocks = {} } = {}) {
  const filename = new URL(`../src/components/${name}.vue`, import.meta.url)
  const { descriptor } = parse(await readFile(filename, 'utf8'), { filename: filename.pathname })
  const script = compileScript(descriptor, { id: name }).content
  const { outputText } = ts.transpileModule(script, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  })
  const module = { exports: {} }
  new Function('require', 'module', 'exports', outputText)(id => {
    if (Object.hasOwn(mocks, id)) return mocks[id]
    if (id.endsWith('.vue') || id === '@ant-design/icons-vue') return {}
    return require(id)
  }, module, module.exports)
  const component = module.exports.default
  component.render = () => null
  const { createRenderer, h } = require('vue')
  const renderer = createRenderer({
    createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
    parentNode: () => null, nextSibling: () => null,
  })
  const currentProps = reactive(props)
  let instance
  const app = renderer.createApp({
    render: () => h(component, { ...currentProps, ref: value => { instance = value } }),
  })
  app.mount({})
  return { state: instance.$.setupState, props: currentProps, unmount: () => app.unmount() }
}

export function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

export async function flush() {
  await Promise.resolve()
  await nextTick()
}
