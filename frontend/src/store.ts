import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { get, session } from './api'
import type { Application, Department, Health, Principal } from './types'

const DEPARTMENT = 'melonhub-department', APPLICATION = 'melonhub-application'

export const useHub = defineStore('hub', () => {
  const me = ref<Principal | null>(null)
  const departments = ref<Department[]>([])
  const applications = ref<Application[]>([])
  const departmentId = ref(Number(localStorage.getItem(DEPARTMENT)) || 0)
  const applicationId = ref(Number(localStorage.getItem(APPLICATION)) || 0)
  const health = ref<Health | null>(null)

  const department = computed(() => departments.value.find(d => d.id === departmentId.value))
  const application = computed(() => applications.value.find(a => a.id === applicationId.value))
  const hasDomain = computed(() => !!department.value && !!application.value)
  /** Changes whenever the isolation domain changes; domain pages reload on it and drop stale responses. */
  const domainKey = computed(() => hasDomain.value ? `${departmentId.value}:${applicationId.value}` : '')
  const domainQuery = computed(() => ({ departmentId: departmentId.value, applicationId: applicationId.value }))

  const role = computed(() => me.value?.role)
  const isSuper = computed(() => role.value === 'SUPER_ADMIN')
  const canPlatform = computed(() => role.value === 'SUPER_ADMIN' || role.value === 'ADMIN')
  function canWriteApp(id: number) { return canPlatform.value || (role.value === 'APP_ADMIN' && !!me.value?.applicationIds.includes(id)) }
  const canDomain = computed(() => hasDomain.value && canWriteApp(applicationId.value))

  const departmentName = (id: number | null | undefined) => id ? departments.value.find(d => d.id === id)?.name ?? `部门 #${id}` : '—'
  const applicationName = (id: number | null | undefined) => id ? applications.value.find(a => a.id === id)?.name ?? `软件 #${id}` : '—'
  const departmentOptions = computed(() => departments.value.map(d => ({ value: d.id, label: d.name })))
  const applicationOptions = computed(() => applications.value.map(a => ({ value: a.id, label: a.enabled ? a.name : `${a.name}（已停用）` })))

  function persist() {
    localStorage.setItem(DEPARTMENT, String(departmentId.value))
    localStorage.setItem(APPLICATION, String(applicationId.value))
  }
  function setDomain(dept?: number, app?: number) {
    if (dept !== undefined) departmentId.value = dept
    if (app !== undefined) applicationId.value = app
    persist()
  }
  async function loadLists() {
    const [d, a] = await Promise.all([get<Department[]>('/admin/departments'), get<Application[]>('/admin/applications')])
    departments.value = d
    applications.value = a
    if (!d.some(x => x.id === departmentId.value)) departmentId.value = d[0]?.id ?? 0
    if (!a.some(x => x.id === applicationId.value)) applicationId.value = a[0]?.id ?? 0
    persist()
  }
  async function refreshHealth() {
    // /api/health answers 503 with a JSON body when the database is down, so read the body either way.
    try {
      const response = await fetch('/api/health', { headers: { Accept: 'application/json' } })
      health.value = await response.json() as Health
    } catch {
      health.value = { status: 'DOWN', database: 'UNKNOWN', cache: 'UNKNOWN' }
    }
  }
  function signIn(principal: Principal) { me.value = principal }
  function signOut() {
    session.clear()
    me.value = null
    departments.value = []
    applications.value = []
  }

  return {
    me, departments, applications, departmentId, applicationId, health, department, application, hasDomain, domainKey, domainQuery,
    role, isSuper, canPlatform, canWriteApp, canDomain, departmentName, applicationName, departmentOptions, applicationOptions,
    setDomain, loadLists, refreshHealth, signIn, signOut,
  }
})
