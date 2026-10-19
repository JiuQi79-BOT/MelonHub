<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { send } from '../api'
import { attempt } from '../composables'
import { useHub } from '../store'

const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ (e: 'changed'): void }>()
const hub = useHub()

const step = ref<'idle' | 'setup'>('idle'), secret = ref(''), uri = ref('')
const confirmRef = ref<FormInstance>(), confirmForm = reactive({ code: '' })
const disableRef = ref<FormInstance>(), disableForm = reactive({ password: '', code: '' })
const busy = ref(false)

watch(open, value => {
  if (value) {
    step.value = 'idle'
    secret.value = ''
    uri.value = ''
    confirmForm.code = ''
    Object.assign(disableForm, { password: '', code: '' })
  }
})

async function setup() {
  const ok = await attempt(busy, async () => {
    const result = await send<{ secret: string; uri: string }>('POST', '/auth/mfa/setup')
    secret.value = result.secret
    uri.value = result.uri
  })
  if (ok) step.value = 'setup'
}
async function confirm() {
  await confirmRef.value?.validate()
  const ok = await attempt(busy, () => send('POST', '/auth/mfa/confirm', { code: confirmForm.code.trim() }), '两步验证已启用')
  if (ok) { emit('changed'); open.value = false }
}
async function disable() {
  await disableRef.value?.validate()
  const ok = await attempt(busy, () => send('POST', '/auth/mfa/disable', { password: disableForm.password, code: disableForm.code.trim() }), '两步验证已停用')
  if (ok) { emit('changed'); open.value = false }
}
const codeRules = [{ required: true, pattern: /^\d{6}$/, message: '请输入 6 位动态口令' }]
</script>

<template>
  <a-modal v-model:open="open" title="两步验证" :footer="null" destroy-on-close :mask-closable="false" width="520px">
    <template v-if="hub.me?.mfaEnabled">
      <a-alert type="success" show-icon class="block-gap" message="两步验证已启用" description="登录时需要额外输入身份验证器中的 6 位动态口令。停用时需要验证当前密码和动态口令。" />
      <a-form ref="disableRef" layout="vertical" :model="disableForm">
        <a-form-item label="当前密码" name="password" :rules="[{ required: true, message: '请输入当前密码' }]">
          <a-input-password v-model:value="disableForm.password" autocomplete="current-password" />
        </a-form-item>
        <a-form-item label="动态口令" name="code" :rules="codeRules">
          <a-input v-model:value="disableForm.code" :maxlength="6" placeholder="身份验证器中的 6 位数字" autocomplete="one-time-code" />
        </a-form-item>
        <a-button danger block :loading="busy" @click="disable">停用两步验证</a-button>
      </a-form>
    </template>
    <template v-else>
      <a-alert type="info" show-icon class="block-gap" message="两步验证未启用"
               description="启用后，登录时除密码外还需输入身份验证器（如 Microsoft Authenticator、Google Authenticator）中的 6 位动态口令。" />
      <template v-if="step === 'idle'">
        <a-button type="primary" block :loading="busy" @click="setup">生成绑定密钥</a-button>
      </template>
      <template v-else>
        <p class="muted">1. 在身份验证器中添加账号，选择“手动输入密钥”，填入以下密钥（或粘贴 otpauth 链接）：</p>
        <div class="secret-box">
          <a-typography-paragraph :copyable="{ text: secret, tooltips: ['复制', '已复制'] }" class="secret-text"><code>{{ secret }}</code></a-typography-paragraph>
        </div>
        <div class="secret-box">
          <a-typography-paragraph :copyable="{ text: uri, tooltips: ['复制链接', '已复制'] }" class="secret-text"><code>{{ uri }}</code></a-typography-paragraph>
        </div>
        <a-form ref="confirmRef" layout="vertical" :model="confirmForm" class="mfa-confirm">
          <a-form-item label="2. 输入身份验证器显示的 6 位动态口令完成绑定" name="code" :rules="codeRules">
            <a-input v-model:value="confirmForm.code" :maxlength="6" autocomplete="one-time-code" />
          </a-form-item>
          <a-button type="primary" block :loading="busy" @click="confirm">确认启用</a-button>
        </a-form>
      </template>
    </template>
  </a-modal>
</template>

<style scoped>
.mfa-confirm { margin-top: 16px; }
</style>
