<script setup lang="ts">
import { computed, reactive, ref, shallowRef } from 'vue'
import dayjs, { type Dayjs } from 'dayjs'
import { message } from 'ant-design-vue'
import { send } from '../api'
import { attempt } from '../composables'
import { useHub } from '../store'
import type { Role } from '../types'

const props = defineProps<{
  userId: number
  applicationId: number
  roles: Role[]
  assigned: number[]
  /** Expiry of each time-limited role, keyed by role id; permanent roles are absent. */
  expiries: Record<string, string>
  disabled?: boolean
}>()
const hub = useHub()

const chosen = ref<number[]>([...props.assigned])
// Submit every selected timed expiry, so a grant removed by the expiry task cannot be recreated as permanent.
const held = shallowRef(new Map<number, Dayjs | null>(props.assigned.map(id => {
  const at = props.expiries?.[id]
  return [id, at ? dayjs(at) : null]
})))
const expiries = reactive<Record<number, Dayjs | null>>({})
for (const [id, at] of held.value) expiries[id] = at
const fillDays = ref<number | null>(null)
const saving = ref(false)

const roleMap = computed(() => new Map(props.roles.map(r => [r.id, r])))
const roleOptions = computed(() => props.roles.map(r => ({ value: r.id, label: `${r.name}（${r.code}）${r.enabled ? '' : ' · 已停用'}` })))
const scope = computed(() => hub.applicationName(props.applicationId))

function fill(days: number | null) {
  const at = days ? dayjs().add(days, 'day').second(0).millisecond(0) : null
  for (const id of chosen.value) expiries[id] = at
}
function outOfRange(day: Dayjs) { return day.isBefore(dayjs(), 'day') || day.isAfter(dayjs().add(3650, 'day'), 'day') }
function changedExpiries() {
  const changes: { roleId: number; expiresAt: string | null }[] = []
  for (const id of chosen.value) {
    const value = expiries[id] ?? null
    if (held.value.has(id)) {
      const before = held.value.get(id) ?? null
      if (!value && !before) continue
    } else if (!value) continue
    changes.push({ roleId: id, expiresAt: value ? value.format() : null })
  }
  return changes
}
async function submit() {
  if (props.disabled) return false
  const changes = changedExpiries()
  const lapsed = changes.find(c => c.expiresAt && !dayjs(c.expiresAt).isAfter(dayjs()))
  if (lapsed) { message.warning(`「${roleMap.value.get(lapsed.roleId)?.name ?? lapsed.roleId}」的到期时间必须晚于当前时间`); return false }
  return await attempt(saving, () => send('PUT', `/admin/users/${props.userId}/roles`, { ids: chosen.value, expiries: changes },
    { applicationId: props.applicationId }), '用户角色已更新')
}
defineExpose({ submit, saving })
</script>

<template>
  <p class="muted">保存后替换该用户在“{{ scope }}”中的全部角色，其在此范围内的现有权限上下文立即失效。其他软件的授权不受影响。</p>
  <a-empty v-if="!roleOptions.length" description="此软件还没有角色，请在配置抽屉的「角色」中创建" :image-style="{ height: '40px' }" />
  <template v-else>
    <a-space wrap class="block-gap">
      <span class="muted">已选 {{ chosen.length }} 个角色</span>
      <a-button size="small" :disabled="disabled" @click="chosen = roles.map(r => r.id)">全选</a-button>
      <a-button size="small" :disabled="disabled" @click="chosen = []">取消全选</a-button>
    </a-space>
    <a-checkbox-group v-model:value="chosen" class="check-list" :options="roleOptions" :disabled="disabled" />
    <div v-if="chosen.length" class="expires-form">
      <div class="expiry-head">
        <span class="cell-main">到期时间</span>
        <span class="expiry-fill">
          全部设为
          <a-input-number v-model:value="fillDays" :min="1" :max="3650" :precision="0" size="small" placeholder="天数" :disabled="disabled" class="expiry-days" />
          天后
          <a-button size="small" :disabled="disabled || !fillDays" @click="fill(fillDays)">应用</a-button>
          <a-button size="small" :disabled="disabled" @click="fill(null)">全部永久</a-button>
        </span>
      </div>
      <div v-for="id in chosen" :key="id" class="expiry-row">
        <span class="expiry-role">
          {{ roleMap.get(id)?.name ?? `角色 #${id}` }}
          <a-tag v-if="!held.has(id)" color="green">新增</a-tag>
        </span>
        <a-date-picker v-model:value="expiries[id]" show-time format="YYYY-MM-DD HH:mm" placeholder="永久" allow-clear
                       :disabled-date="outOfRange" :disabled="disabled" class="expiry-picker" />
      </div>
      <p class="hint">留空表示永久；到期后自动回收该角色。未改动的角色保留原有到期时间。</p>
    </div>
  </template>
</template>
