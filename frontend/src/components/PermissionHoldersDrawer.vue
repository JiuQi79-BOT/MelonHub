<script setup lang="ts">
import { ref, watch } from 'vue'
import { message, type TableColumnsType } from 'ant-design-vue'
import { errorText, get } from '../api'
import { expiringSoon, fmt } from '../labels'
import type { Permission, PermissionHolder, PermissionHolders } from '../types'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ permission?: Permission; applicationId?: number; departmentId?: number }>()
const data = ref<PermissionHolders | null>(null), loading = ref(false)
let sequence = 0

async function load() {
  if (!props.permission || !props.applicationId) return
  const current = ++sequence
  loading.value = true
  data.value = null
  try {
    const result = await get<PermissionHolders>('/admin/permissions/who', { applicationId: props.applicationId, code: props.permission.code, departmentId: props.departmentId })
    if (current === sequence) data.value = result
  } catch (e) {
    if (current === sequence) message.error(errorText(e))
  } finally {
    if (current === sequence) loading.value = false
  }
}
watch(() => [open.value, props.permission?.id], () => { if (open.value) load() }, { immediate: true })

const columns: TableColumnsType = [
  { title: '用户', key: 'user', width: 200, ellipsis: true },
  { title: '来源角色', key: 'roles' },
]
</script>

<template>
  <a-drawer v-model:open="open" :title="`权限使用者：${permission?.code ?? ''}`" width="820" destroy-on-close>
    <template #extra><a-button @click="load">刷新</a-button></template>
    <a-spin :spinning="loading">
      <template v-if="data">
        <p class="hint">
          共 {{ data.total }} 名用户通过角色获得 <code>{{ permission?.code }}</code>，包括有期限和永久的授权。
          <template v-if="data.truncated">这里只列出前 {{ data.holders.length }} 名，可按部门筛选后查看。</template>
        </p>
        <a-table :columns="columns" :data-source="data.holders" :row-key="(h: PermissionHolder) => `${h.userId}:${h.departmentId}`" size="small"
                 :pagination="{ size: 'small', defaultPageSize: 20, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'user'">
              <div class="cell-main">{{ record.userName }}</div>
              <div class="cell-sub"><code>{{ record.externalId }}</code></div>
            </template>
            <template v-else-if="column.key === 'roles'">
              <div v-for="r in record.roles" :key="r.id" class="holder-role">
                <a-tag :color="expiringSoon(r.expiresAt) ? 'orange' : 'blue'">{{ r.name }}（{{ r.code }}）</a-tag>
                <span class="cell-sub">{{ r.expiresAt ? `至 ${fmt(r.expiresAt)}` : '永久' }}</span>
              </div>
            </template>
          </template>
          <template #emptyText><a-empty description="当前没有用户持有该权限" :image-style="{ height: '40px' }" /></template>
        </a-table>
      </template>
    </a-spin>
  </a-drawer>
</template>
