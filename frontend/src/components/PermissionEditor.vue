<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { send } from '../api'
import { attempt } from '../composables'
import { useAuthorizationScope } from '../authorizationScope'
import type { Permission } from '../types'

const open = defineModel<boolean>('open', { required: true })
const props = defineProps<{ permission?: Permission | null }>()
const emit = defineEmits<{ saved: [id: number] }>()
const scope = useAuthorizationScope()
const saving = ref(false), formRef = ref<FormInstance>()
const form = reactive({ name: '', code: '', resource: '', action: '' })
const rules = {
  name: [{ required: true, whitespace: true, max: 100, message: '请输入名称（最多 100 字）' }],
  code: [{ required: true, pattern: /^[a-zA-Z0-9_.:-]{1,100}$/, message: '1-100 位字母、数字或 _ . : -' }],
  resource: [{ required: true, whitespace: true, max: 100, message: '请输入资源标识（最多 100 字）' }],
  action: [{ required: true, whitespace: true, max: 64, message: '请输入操作标识（最多 64 字）' }],
}
watch(() => [open.value, props.permission], () => {
  if (!open.value) return
  const row = props.permission
  Object.assign(form, { name: row?.name ?? '', code: row?.code ?? '', resource: row?.resource ?? '', action: row?.action ?? '' })
}, { immediate: true })
function suggest() {
  const parts = form.code.trim().split(':')
  if (parts.length < 2) return
  if (!form.resource) form.resource = parts.slice(0, -1).join(':')
  if (!form.action) form.action = parts[parts.length - 1]
}
async function save() {
  await formRef.value?.validate()
  const body = { name: form.name.trim(), code: form.code.trim(), resource: form.resource.trim(), action: form.action.trim() }
  let id = 0
  const ok = await attempt(saving, async () => {
    const result = props.permission
      ? await send<{ id: number }>('PUT', `/admin/permissions/${props.permission.id}`, body, scope.domainQuery)
      : await send<{ id: number }>('POST', '/admin/permissions', body, scope.domainQuery)
    id = result.id
  }, props.permission ? '权限已更新' : '权限已创建')
  if (ok) { open.value = false; emit('saved', id) }
}
</script>

<template>
  <a-modal v-model:open="open" :title="permission ? '编辑权限' : '新建权限'" :confirm-loading="saving" ok-text="保存" destroy-on-close :mask-closable="false" @ok="save">
    <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
      <a-form-item label="权限编码" name="code" extra="业务系统校验时使用的值，本软件内唯一，区分大小写。建议“资源:操作”格式">
        <a-input v-model:value="form.code" :maxlength="100" placeholder="例如：order:approve" @blur="suggest" />
      </a-form-item>
      <a-form-item label="名称" name="name"><a-input v-model:value="form.name" :maxlength="100" placeholder="例如：审批订单" /></a-form-item>
      <a-row :gutter="16">
        <a-col :xs="24" :sm="12"><a-form-item label="资源" name="resource"><a-input v-model:value="form.resource" :maxlength="100" placeholder="order" /></a-form-item></a-col>
        <a-col :xs="24" :sm="12"><a-form-item label="操作" name="action"><a-input v-model:value="form.action" :maxlength="64" placeholder="approve" /></a-form-item></a-col>
      </a-row>
    </a-form>
  </a-modal>
</template>
