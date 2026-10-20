<script setup lang="ts">
import { computed, provide, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useHub } from '../store'
import { authorizationScopeKey } from '../authorizationScope'
import Roles from '../pages/Roles.vue'
import Permissions from '../pages/Permissions.vue'
import Assignments from '../pages/Assignments.vue'
import Users from '../pages/Users.vue'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ departmentId?: number; applicationId?: number; initialTab?: string }>()
const hub = useHub(), router = useRouter()
const applicationId = ref(0), tab = ref('assignments'), revision = ref(0)
const application = computed(() => hub.applications.find(a => a.id === applicationId.value))
const hasDomain = computed(() => !!application.value)
const scope = reactive({
  applicationId, application, hasDomain,
  domainKey: computed(() => hasDomain.value ? String(applicationId.value) : ''),
  domainQuery: computed(() => ({ applicationId: applicationId.value })),
  canDomain: computed(() => hasDomain.value && hub.canWriteApp(applicationId.value)),
})
provide(authorizationScopeKey, scope)
const title = computed(() => props.applicationId ? `软件配置：${hub.applicationName(props.applicationId)}` : `部门成员：${hub.departmentName(props.departmentId)}`)

watch(() => [open.value, props.departmentId, props.applicationId], () => {
  if (!open.value) return
  applicationId.value = props.applicationId ?? hub.applicationId
  tab.value = props.initialTab ?? 'assignments'
  revision.value++
}, { immediate: true })

function navigate(value: string) {
  if (value === 'members' && !props.departmentId) {
    open.value = false
    router.push('/users')
  } else if (props.departmentId && (value === 'roles' || value === 'permissions')) {
    hub.setApplication(applicationId.value)
    open.value = false
    router.push('/' + value)
  } else tab.value = value
}
function afterOpenChange(value: boolean) { if (!value) hub.loadLists() }
</script>

<template>
  <a-drawer v-model:open="open" :title="title" width="min(1180px, 94vw)" destroy-on-close :push="false"
            root-class-name="authorization-drawer" @after-open-change="afterOpenChange">
    <div v-if="!props.applicationId && tab !== 'members'" class="toolbar authorization-scope">
      <span>授权软件</span>
      <a-select v-model:value="applicationId" :options="hub.applicationOptions" placeholder="选择软件" aria-label="授权软件" show-search option-filter-prop="label" class="toolbar-select" />
      <span class="muted">按部门选人，角色分配直接保存在用户与软件之间。</span>
    </div>
    <a-tabs v-model:active-key="tab">
      <a-tab-pane key="assignments" tab="成员授权" />
      <a-tab-pane v-if="!props.departmentId" key="roles" tab="角色" />
      <a-tab-pane v-if="!props.departmentId" key="permissions" tab="权限项" />
      <a-tab-pane v-if="props.departmentId" key="members" tab="部门成员" />
    </a-tabs>
    <div v-if="open" class="authorization-content">
      <Users v-if="tab === 'members'" :key="`members:${props.departmentId}`" embedded :department-id="props.departmentId" @navigate="navigate" />
      <template v-else-if="scope.hasDomain">
        <Roles v-if="tab === 'roles'" :key="`roles:${scope.domainKey}:${revision}`" embedded @navigate="navigate" />
        <Permissions v-else-if="tab === 'permissions'" :key="`permissions:${scope.domainKey}:${revision}`" embedded />
        <Assignments v-else :key="`assignments:${props.departmentId}:${revision}`" embedded :department-id="props.departmentId" @navigate="navigate" />
      </template>
      <a-empty v-else description="请先接入并选择软件。" />
    </div>
  </a-drawer>
</template>
