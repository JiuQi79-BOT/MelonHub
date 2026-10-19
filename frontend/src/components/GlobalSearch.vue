<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { SearchOutlined } from '@ant-design/icons-vue'
import { get } from '../api'
import { isPhone } from '../responsive'
import type { SearchResults } from '../types'

interface Option { value: string; label: string }
const router = useRouter()
const keyword = ref(''), options = ref<{ label: string; options: Option[] }[]>([]), fetching = ref(false), expanded = ref(false)
const selectRef = ref<{ focus: () => void; blur: () => void }>()
const shortcut = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘ K' : 'Ctrl K'
let last: SearchResults | null = null
let timer: ReturnType<typeof setTimeout> | undefined, sequence = 0

function search(value: string) {
  keyword.value = value
  clearTimeout(timer)
  const q = value.trim()
  if (!q) { options.value = []; last = null; return }
  timer = setTimeout(async () => {
    const current = ++sequence
    fetching.value = true
    try {
      const r = await get<SearchResults>('/admin/search', { q })
      if (current !== sequence) return
      last = r
      const groups: { label: string; options: Option[] }[] = []
      if (r.users.length) groups.push({ label: '用户', options: r.users.map(u => ({ value: `user:${u.id}`, label: `${u.name}（${u.externalId}）${u.enabled ? '' : ' · 已停用'}` })) })
      if (r.departments.length) groups.push({ label: '部门', options: r.departments.map(d => ({ value: `department:${d.id}`, label: `${d.name}（${d.code}）` })) })
      if (r.applications.length) groups.push({ label: '软件', options: r.applications.map(a => ({ value: `application:${a.id}`, label: `${a.name}（${a.code}）${a.enabled ? '' : ' · 已停用'}` })) })
      if (r.roles.length) groups.push({ label: '角色', options: r.roles.map(x => ({ value: `role:${x.id}:${x.departmentId}:${x.applicationId}`, label: `${x.name} · ${x.departmentName} × ${x.applicationName}` })) })
      options.value = groups
    } catch {
      if (current === sequence) options.value = []
    } finally {
      if (current === sequence) fetching.value = false
    }
  }, 300)
}
/** On phones the search box only exists while expanded over the header. */
async function expand() {
  expanded.value = true
  await nextTick()
  selectRef.value?.focus()
}
function collapse() {
  expanded.value = false
  keyword.value = ''
  options.value = []
}
function choose(value: unknown) {
  const parts = String(value).split(':')
  const id = Number(parts[1])
  const r = last
  keyword.value = ''
  options.value = []
  selectRef.value?.blur()
  expanded.value = false
  if (parts[0] === 'user') router.push({ path: '/users', query: { keyword: r?.users.find(u => u.id === id)?.externalId ?? '' } })
  else if (parts[0] === 'department') router.push({ path: '/departments', query: { configure: id } })
  else if (parts[0] === 'application') router.push({ path: '/applications', query: { configure: id } })
  else if (parts[0] === 'role') router.push({ path: '/applications', query: { configure: Number(parts[3]), departmentId: Number(parts[2]) } })
}
function onKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (isPhone.value) expand()
    else selectRef.value?.focus()
  }
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => { window.removeEventListener('keydown', onKey); clearTimeout(timer) })
</script>

<template>
  <button v-if="isPhone && !expanded" type="button" class="icon-btn" aria-label="搜索" @click="expand"><SearchOutlined /></button>
  <div v-else class="global-search" :class="{ expanded: isPhone }">
    <SearchOutlined class="global-search-icon" />
    <a-select ref="selectRef" :value="undefined" show-search :filter-option="false" :options="options" :default-active-first-option="true"
              :not-found-content="fetching ? '搜索中…' : (keyword ? '没有匹配结果' : null)" :dropdown-match-select-width="isPhone ? true : 320"
              :show-arrow="!isPhone" placeholder="搜索用户、部门、软件…" @search="search" @select="choose" @blur="isPhone && !keyword && collapse()">
      <template v-if="!isPhone" #suffixIcon><kbd class="kbd">{{ shortcut }}</kbd></template>
    </a-select>
    <button v-if="isPhone" type="button" class="search-cancel" @click="collapse">取消</button>
  </div>
</template>
