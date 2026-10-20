<script setup lang="ts">
import { computed, provide, reactive, ref, watch } from 'vue'
import { useHub } from '../store'
import { authorizationScopeKey } from '../authorizationScope'
import Roles from '../pages/Roles.vue'
import Permissions from '../pages/Permissions.vue'
import Assignments from '../pages/Assignments.vue'
import Users from '../pages/Users.vue'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ departmentId?: number; applicationId?: number; initialTab?: string }>()
const hub = useHub()
const departmentId = ref(0), applicationId = ref(0), tab = ref('assignments'), revision = ref(0)
const department = computed(() => hub.departments.find(d => d.id === departmentId.value))
const application = computed(() => hub.applications.find(a => a.id === applicationId.value))
const hasDomain = computed(() => !!application.value)
const scope = reactive({
  departmentId, applicationId, department, application, hasDomain,
  domainKey: computed(() => hasDomain.value ? `${departmentId.value}:${applicationId.value}` : ''),
  domainQuery: computed(() => ({ departmentId: departmentId.value, applicationId: applicationId.value })),
  canDomain: computed(() => hasDomain.value && hub.canWriteApp(applicationId.value)),
})
provide(authorizationScopeKey, scope)
const title = computed(() => props.applicationId ? `软件配置：${hub.applicationName(props.applicationId)}` : `部门配置：${hub.departmentName(props.departmentId)}`)

watch(() => [open.value, props.departmentId, props.applicationId], () => {
  if (!open.value) return
  departmentId.value = props.departmentId ?? hub.departmentId
  applicationId.value = props.applicationId ?? hub.applicationId
  tab.value = props.initialTab ?? (props.applicationId ? 'roles' : 'assignments')
  revision.value++
}, { immediate: true })

function navigate(value: string) { tab.value = value }
function afterOpenChange(value: boolean) { if (!value) hub.loadLists() }
</script>

<template>
  <a-drawer v-model:open="open" :title="title" width="min(1180px, 94vw)" destroy-on-close :push="false"
            root-class-name="authorization-drawer" @after-open-change="afterOpenChange">
    <div class="toolbar authorization-scope">
      <template v-if="props.applicationId">
        <span>成员筛选</span>
        <a-select v-model:value="departmentId" :options="[{ value: 0, label: '全部成员' }, ...hub.departmentOptions]" placeholder="选择部门" aria-label="配置部门" show-search option-filter-prop="label" class="toolbar-select" />
      </template>
      <template v-else>
        <span>配置软件</span>
        <a-select v-model:value="applicationId" :options="hub.applicationOptions" placeholder="选择软件" aria-label="配置软件" show-search option-filter-prop="label" class="toolbar-select" />
      </template>
      <span class="muted">{{ application?.name }} · {{ department?.name ?? '全部成员' }}</span>
    </div>
    <a-tabs v-model:active-key="tab">
      <a-tab-pane key="assignments" tab="成员授权" />
      <a-tab-pane key="roles" tab="角色" />
      <a-tab-pane key="permissions" tab="权限项" />
      <a-tab-pane key="members" :tab="departmentId ? '部门成员' : '全部用户'" />
    </a-tabs>
    <div v-if="open" class="authorization-content">
      <Users v-if="tab === 'members'" :key="`members:${departmentId}`" embedded :department-id="departmentId" @navigate="navigate" />
      <template v-else-if="scope.hasDomain">
        <Roles v-if="tab === 'roles'" :key="`roles:${scope.domainKey}:${revision}`" embedded @navigate="navigate" />
        <Permissions v-else-if="tab === 'permissions'" :key="`permissions:${scope.domainKey}:${revision}`" embedded />
        <Assignments v-else :key="`assignments:${departmentId}:${revision}`" embedded @navigate="navigate" />
      </template>
      <a-empty v-else description="请先选择软件。可在软件列表中创建。" />
    </div>
  </a-drawer>
</template>
