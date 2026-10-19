import { computed, ref, shallowRef, type Ref } from 'vue'
import { message } from 'ant-design-vue'
import { errorText, type PageResult } from './api'

/** Server-side paged table state. Responses that arrive after a newer request are dropped. */
export function usePaged<T>(fetcher: (page: number, size: number) => Promise<PageResult<T>>, initialSize = 20) {
  const rows = shallowRef<T[]>([]) as Ref<T[]>
  const loading = ref(false), total = ref(0), page = ref(1), size = ref(initialSize)
  let sequence = 0
  async function load(reset = false): Promise<void> {
    if (reset) page.value = 1
    const current = ++sequence
    loading.value = true
    try {
      const result = await fetcher(page.value, size.value)
      if (current !== sequence) return
      if (!result.items.length && result.total > 0 && page.value > 1) {
        page.value = Math.max(1, Math.ceil(result.total / size.value))
        return load()
      }
      rows.value = result.items
      total.value = result.total
    } catch (error) {
      if (current !== sequence) return
      rows.value = []
      total.value = 0
      message.error(errorText(error))
    } finally {
      if (current === sequence) loading.value = false
    }
  }
  const pagination = computed(() => ({
    current: page.value, pageSize: size.value, total: total.value, showSizeChanger: true, size: 'small',
    pageSizeOptions: ['20', '50', '100', '200'], showTotal: (count: number) => `共 ${count} 条`,
  }))
  function onChange(next: { current?: number; pageSize?: number }) {
    if (next.pageSize && next.pageSize !== size.value) { size.value = next.pageSize; page.value = 1 }
    else if (next.current) page.value = next.current
    load()
  }
  function clear() { sequence++; rows.value = []; total.value = 0; loading.value = false }
  return { rows, loading, total, page, size, pagination, load, onChange, clear }
}

/** Unpaged list with the same stale-response protection. */
export function useList<T>(fetcher: () => Promise<T[]>) {
  const rows = shallowRef<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  let sequence = 0
  async function load() {
    const current = ++sequence
    loading.value = true
    try {
      const result = await fetcher()
      if (current === sequence) rows.value = result
    } catch (error) {
      if (current === sequence) { rows.value = []; message.error(errorText(error)) }
    } finally {
      if (current === sequence) loading.value = false
    }
  }
  function clear() { sequence++; rows.value = []; loading.value = false }
  return { rows, loading, load, clear }
}

/** Runs a mutation with a busy flag and a success or error message; resolves to whether it succeeded. */
export async function attempt(busy: Ref<boolean>, action: () => Promise<unknown>, success?: string) {
  busy.value = true
  try {
    await action()
    if (success) message.success(success)
    return true
  } catch (error) {
    message.error(errorText(error))
    return false
  } finally {
    busy.value = false
  }
}

/**
 * New administrator passwords: 12-100 characters and at most 72 UTF-8 bytes, because BCrypt only reads the
 * first 72 bytes and the server refuses anything longer instead of silently truncating it.
 */
export function newPasswordRules(label = '密码') {
  return [{
    required: true,
    validator: (_: unknown, value: string) => {
      if (!value || value.length < 12 || value.length > 100) return Promise.reject(`${label}长度为 12 到 100 位`)
      if (new TextEncoder().encode(value).length > 72) return Promise.reject(`${label}不能超过 72 字节（UTF-8 编码，约 24 个汉字）`)
      return Promise.resolve()
    },
  }]
}

export const tablePagination = { size: 'small', showSizeChanger: true, defaultPageSize: 20, pageSizeOptions: ['20', '50', '100'], showTotal: (count: number) => `共 ${count} 条` }
