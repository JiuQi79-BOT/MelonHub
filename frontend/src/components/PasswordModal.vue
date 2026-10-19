<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { send } from '../api'
import { attempt, newPasswordRules } from '../composables'

const open = defineModel<boolean>('open', { required: true })
const formRef = ref<FormInstance>()
const form = reactive({ currentPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)
watch(open, value => { if (value) Object.assign(form, { currentPassword: '', newPassword: '', confirm: '' }) })

const rules = {
  currentPassword: [{ required: true, message: '请输入当前密码' }],
  newPassword: newPasswordRules('新密码'),
  confirm: [{ required: true, validator: (_: unknown, value: string) => value === form.newPassword ? Promise.resolve() : Promise.reject('两次输入的新密码不一致') }],
}
async function submit() {
  await formRef.value?.validate()
  const ok = await attempt(saving, () => send('PUT', '/auth/password', { currentPassword: form.currentPassword, newPassword: form.newPassword }), '密码已修改，其他设备上的登录已下线')
  if (ok) open.value = false
}
</script>

<template>
  <a-modal v-model:open="open" title="修改密码" :confirm-loading="saving" ok-text="保存" :mask-closable="false" destroy-on-close @ok="submit">
    <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
      <a-form-item label="当前密码" name="currentPassword"><a-input-password v-model:value="form.currentPassword" autocomplete="current-password" /></a-form-item>
      <a-form-item label="新密码" name="newPassword" extra="12-100 位且不超过 72 字节（约 24 个汉字）；修改后其他会话会被强制下线"><a-input-password v-model:value="form.newPassword" autocomplete="new-password" /></a-form-item>
      <a-form-item label="确认新密码" name="confirm"><a-input-password v-model:value="form.confirm" autocomplete="new-password" /></a-form-item>
    </a-form>
  </a-modal>
</template>
