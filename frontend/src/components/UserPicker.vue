<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { get, type PageResult } from '../api'
import type { User } from '../types'

const props = withDefaults(defineProps<{ departmentId?: number; multiple?: boolean; placeholder?: string; exclude?: number[] }>(), { multiple: false, placeholder: '输入姓名、外部标识或邮箱搜索' })
const value = defineModel<number | number[] | undefined>('value')
const emit = defineEmits<{ (e: 'pick', user: User | undefined): void; (e: 'picks', users: User[]): void }>()
const results = shallowRef<User[]>([]), loading = ref(false)
const known = new Map<number, User>()
let sequence = 0, timer: ReturnType<typeof setTimeout> | undefined

const label = (u: User) => `${u.name}（${u.externalId}）${u.enabled ? '' : ' · 已停用'}`
const options = computed(() => {
  const selected = (Array.isArray(value.value) ? value.value : value.value ? [value.value] : []).filter(id => !results.value.some(u => u.id === id))
  const extra = selected.map(id => known.get(id)).filter((u): u is User => !!u)
  return [...extra, ...results.value].filter(u => !props.exclude?.includes(u.id)).map(u => ({ value: u.id, label: label(u) }))
})

async function run(keyword: string) {
  const current = ++sequence
  loading.value = true
  try {
    const page = await get<PageResult<User>>('/admin/users', { keyword, departmentId: props.departmentId, size: 20 })
    if (current !== sequence) return
    page.items.forEach(u => known.set(u.id, u))
    results.value = page.items
  } catch {
    if (current === sequence) results.value = []
  } finally {
    if (current === sequence) loading.value = false
  }
}
function search(keyword: string) { clearTimeout(timer); timer = setTimeout(() => run(keyword.trim()), 250) }
function change(next: unknown) {
  value.value = next as number | number[] | undefined
  if (!props.multiple) emit('pick', typeof next === 'number' ? known.get(next) : undefined)
  else emit('picks', ((next as number[] | undefined) ?? []).map(id => known.get(id)).filter((u): u is User => !!u))
}
onMounted(() => run(''))
watch(() => props.departmentId, () => run(''))
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <a-select :value="value" :mode="multiple ? 'multiple' : undefined" :options="options" :placeholder="placeholder" show-search allow-clear
            :filter-option="false" :loading="loading" :not-found-content="loading ? '搜索中…' : '没有匹配的用户'" @search="search" @change="change" />
</template>
