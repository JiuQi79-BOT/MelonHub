<script setup lang="ts">
import { ref, watch } from 'vue'
import { message, type TableColumnsType } from 'ant-design-vue'
import { get, send, type PageResult } from '../api'
import { attempt, usePaged, useUserSelection } from '../composables'
import { useHub } from '../store'
import type { User } from '../types'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ departmentId: number }>()
const emit = defineEmits<{ changed: [] }>()
const hub = useHub()
const keyword = ref(''), sourceDepartment = ref<number>(), saving = ref(false)
const query = () => ({ keyword: keyword.value.trim(), departmentId: sourceDepartment.value })
const table = usePaged<User>((page, size) => get<PageResult<User>>('/admin/users', { ...query(), page, size }))
const selection = useUserSelection(query, user => !user.departmentIds.includes(props.departmentId))
const { selected, selecting } = selection
watch(() => [open.value, props.departmentId], () => {
  keyword.value = ''
  sourceDepartment.value = undefined
  selection.clear()
  if (open.value) table.load(true)
})
watch(sourceDepartment, () => { if (open.value) table.load(true) })

const columns: TableColumnsType<User> = [
  { title: '姓名', dataIndex: 'name', width: 140 },
  { title: '外部标识', dataIndex: 'externalId', width: 180 },
  { title: '所属部门', key: 'departments' },
]
async function save() {
  const ok = await attempt(saving, async () => {
    const result = await send<{ changedUsers: number }>('POST', `/admin/departments/${props.departmentId}/members`, { ids: selected.value })
    message.success(`已将 ${result.changedUsers} 名用户加入部门`)
  })
  if (ok) { open.value = false; emit('changed') }
}
</script>

<template>
  <a-modal v-model:open="open" :title="`批量加入用户：${hub.departmentName(departmentId)}`" width="860px" ok-text="加入部门"
           :confirm-loading="saving" :ok-button-props="{ disabled: !selected.length || selecting }"
           :cancel-button-props="{ disabled: saving }" :closable="!saving" :keyboard="!saving" :mask-closable="false" destroy-on-close @ok="save">
    <p class="hint">选择已有用户加入当前部门，保留其他部门归属和软件授权。已在本部门的用户无需重复加入。</p>
    <div class="toolbar">
      <a-input-search v-model:value="keyword" placeholder="姓名、外部标识或邮箱" allow-clear class="toolbar-search" :disabled="saving" @search="table.load(true)" />
      <a-select v-model:value="sourceDepartment" :options="hub.departmentOptions" placeholder="按原部门筛选" allow-clear show-search
                option-filter-prop="label" class="toolbar-select" :disabled="saving" />
    </div>
    <div class="toolbar">
      <a-button :loading="selecting" :disabled="saving || table.loading.value" @click="selection.selectAll">全选可加入用户</a-button>
      <a-button :disabled="saving || (!selected.length && !selecting)" @click="selection.clear">取消全选</a-button>
      <span class="muted">已选 {{ selected.length }} 名用户，可跨页勾选</span>
    </div>
    <a-table :columns="columns" :data-source="table.rows.value" :row-selection="selection.rowSelection.value" row-key="id" size="small"
             :loading="table.loading.value || saving" :pagination="table.pagination.value" :scroll="{ x: 650 }" @change="table.onChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'departments'">
          <a-tag v-if="record.departmentIds.includes(departmentId)" color="green">已在本部门</a-tag>
          <span>{{ record.departmentIds.map(hub.departmentName).join('、') || '未加入部门' }}</span>
        </template>
      </template>
    </a-table>
  </a-modal>
</template>
