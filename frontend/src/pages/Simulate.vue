<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { message, type TableColumnsType } from 'ant-design-vue'
import { ExperimentOutlined } from '@ant-design/icons-vue'
import { errorText, get, send } from '../api'
import { useList } from '../composables'
import { useHub } from '../store'
import type { Permission, Role, Simulation } from '../types'
import PageHead from '../components/PageHead.vue'
import UserPicker from '../components/UserPicker.vue'

const hub = useHub()
const form = reactive<{ userId?: number; departmentId?: number; applicationId?: number; mode: 'all' | 'custom'; activeRoleIds: number[]; permissions: string[] }>({
  departmentId: hub.departmentId || undefined, applicationId: hub.applicationId || undefined, mode: 'all', activeRoleIds: [], permissions: [],
})
const domain = computed(() => form.applicationId ? { departmentId: form.departmentId ?? 0, applicationId: form.applicationId } : null)
const roles = useList<Role>(() => get<Role[]>('/admin/roles', domain.value!))
const permissions = useList<Permission>(() => get<Permission[]>('/admin/permissions', domain.value!))
const result = ref<Simulation | null>(null), running = ref(false)
let sequence = 0
/** Drops the shown result and any diagnosis still in flight: both describe conditions that no longer hold. */
function invalidate() {
  sequence++
  result.value = null
  running.value = false
}
watch(domain, value => {
  form.activeRoleIds = []
  if (value) { roles.load(); permissions.load() } else { roles.clear(); permissions.clear() }
}, { immediate: true })
watch(() => [form.userId, form.departmentId, form.applicationId, form.mode, form.activeRoleIds.join(','), form.permissions.join('\n')], invalidate)

const roleOptions = computed(() => roles.rows.value.map(r => ({ value: r.id, label: `${r.name}（${r.code}）${r.enabled ? '' : ' · 已停用'}` })))
const permissionOptions = computed(() => [...new Map(permissions.rows.value.map(p => [p.code, { value: p.code, label: `${p.code}  ${p.name}` }])).values()])
const roleName = computed(() => new Map((result.value?.roles ?? []).map(r => [r.id, r.name])))

async function run() {
  if (!form.userId || !domain.value) return
  const current = ++sequence
  const body = {
    userId: form.userId, ...domain.value,
    activeRoleIds: form.mode === 'all' ? null : [...form.activeRoleIds],
    permissions: form.permissions.length ? [...form.permissions] : null,
  }
  running.value = true
  try {
    const answer = await send<Simulation>('POST', '/admin/simulate', body)
    if (current === sequence) result.value = answer
  } catch (e) {
    if (current === sequence) message.error(errorText(e))
  } finally {
    if (current === sequence) running.value = false
  }
}

const decisionRows = computed(() => Object.entries(result.value?.decisions ?? {}).map(([code, allowed]) => ({ code, allowed })))
type Decision = { code: string; allowed: boolean }
const decisionColumns: TableColumnsType<Decision> = [
  { title: '权限编码', dataIndex: 'code', key: 'code' },
  { title: '结果', key: 'allowed', width: 100 },
]
const roleColumns: TableColumnsType<Simulation['roles'][number]> = [
  { title: '角色', dataIndex: 'name', ellipsis: true },
  { title: '编码', dataIndex: 'code', key: 'code', ellipsis: true },
  { title: '状态', key: 'enabled', width: 80 },
  { title: '本次激活', key: 'active', width: 90 },
]
const permissionColumns: TableColumnsType<Simulation['permissions'][number]> = [
  { title: '权限编码', dataIndex: 'code', key: 'code', width: 200, ellipsis: true },
  { title: '名称', dataIndex: 'name', ellipsis: true },
  { title: '资源 / 操作', key: 'meta', width: 160, ellipsis: true },
  { title: '来源角色', key: 'grantedBy' },
]
</script>

<template>
  <div class="page">
    <PageHead title="权限诊断" />
    <a-row :gutter="16">
      <a-col :xs="24" :xl="8">
        <a-card :bordered="false" title="诊断条件">
          <a-form layout="vertical">
            <a-form-item label="用户" required><UserPicker v-model:value="form.userId" /></a-form-item>
            <a-row :gutter="12">
              <a-col :span="12"><a-form-item label="部门（筛选）"><a-select v-model:value="form.departmentId" :options="[{ value: 0, label: '全部成员' }, ...hub.departmentOptions]" show-search option-filter-prop="label" placeholder="选择部门" /></a-form-item></a-col>
              <a-col :span="12"><a-form-item label="软件" required><a-select v-model:value="form.applicationId" :options="hub.applicationOptions" show-search option-filter-prop="label" placeholder="选择软件" /></a-form-item></a-col>
            </a-row>
            <a-form-item label="激活角色">
              <a-radio-group v-model:value="form.mode" class="block-gap">
                <a-radio value="all">全部已分配角色</a-radio>
                <a-radio value="custom">指定角色</a-radio>
              </a-radio-group>
              <a-select v-if="form.mode === 'custom'" v-model:value="form.activeRoleIds" mode="multiple" :options="roleOptions" placeholder="不选表示不激活任何角色" option-filter-prop="label" />
            </a-form-item>
            <a-form-item label="待校验权限" extra="可选。可从列表选择，也可直接输入编码后回车">
              <a-select v-model:value="form.permissions" mode="tags" :options="permissionOptions" placeholder="例如：order:approve" :token-separators="[',', ' ']" />
            </a-form-item>
            <a-button type="primary" block :loading="running" :disabled="!form.userId || !domain" @click="run"><ExperimentOutlined />开始诊断</a-button>
          </a-form>
        </a-card>
      </a-col>
      <a-col :xs="24" :xl="16">
        <a-card :bordered="false" title="诊断结果">
          <a-empty v-if="!result" description="选择用户和授权域后开始诊断" />
          <template v-else>
            <a-alert v-if="result.eligible" type="success" show-icon class="block-gap" message="该用户可以在此授权域创建权限上下文"
                     :description="`激活 ${result.roles.filter(r => r.active).length} 个角色，获得 ${result.permissions.length} 项权限。`" />
            <a-alert v-else type="error" show-icon class="block-gap" message="业务系统为该用户创建权限上下文会失败">
              <template #description><ul class="issue-list"><li v-for="issue in result.issues" :key="issue">{{ issue }}</li></ul></template>
            </a-alert>
            <a-alert v-if="result.notices?.length" type="info" show-icon class="block-gap" message="以下角色不计入权限">
              <template #description><ul class="issue-list"><li v-for="notice in result.notices" :key="notice">{{ notice }}</li></ul></template>
            </a-alert>
            <template v-if="decisionRows.length">
              <h4 class="section-title">权限校验
                <a-tag :color="result.allowed ? 'green' : 'red'" class="title-tag">{{ result.allowed ? '全部通过' : '未全部通过' }}</a-tag>
              </h4>
              <a-table :columns="decisionColumns" :data-source="decisionRows" row-key="code" size="small" :pagination="false">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'code'"><code>{{ record.code }}</code></template>
                  <template v-else-if="column.key === 'allowed'"><a-tag :color="record.allowed ? 'green' : 'red'">{{ record.allowed ? '允许' : '拒绝' }}</a-tag></template>
                </template>
              </a-table>
            </template>
            <h4 class="section-title">已分配角色</h4>
            <a-table :columns="roleColumns" :data-source="result.roles" row-key="id" size="small" :pagination="false">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'code'"><code>{{ record.code }}</code></template>
                <template v-else-if="column.key === 'enabled'"><a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '启用' : '停用'" /></template>
                <template v-else-if="column.key === 'active'"><a-tag :color="record.active ? 'blue' : 'default'">{{ record.active ? '是' : '否' }}</a-tag></template>
              </template>
              <template #emptyText><a-empty description="该用户在此授权域没有角色" :image-style="{ height: '40px' }" /></template>
            </a-table>
            <h4 class="section-title">有效权限</h4>
            <a-table :columns="permissionColumns" :data-source="result.permissions" row-key="code" size="small" :pagination="{ size: 'small', pageSize: 20, hideOnSinglePage: true }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'code'"><code>{{ record.code }}</code></template>
                <template v-else-if="column.key === 'meta'">{{ record.resource }} / {{ record.action }}</template>
                <template v-else-if="column.key === 'grantedBy'"><a-tag v-for="id in record.grantedBy" :key="id">{{ roleName.get(id) ?? `#${id}` }}</a-tag></template>
              </template>
              <template #emptyText><a-empty description="没有有效权限" :image-style="{ height: '40px' }" /></template>
            </a-table>
          </template>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>
