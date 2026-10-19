<script setup lang="ts">
const open = defineModel<boolean>('open', { required: true })
defineProps<{ title: string; secret: string }>()
</script>

<template>
  <a-modal v-model:open="open" :title="title" :closable="false" :mask-closable="false" :keyboard="false" width="560px">
    <a-alert type="warning" show-icon message="密钥只显示这一次" description="关闭后无法再次查看。请立即保存到业务系统后端的密钥配置中，不要写进浏览器、移动端或前端代码。" />
    <div class="secret-box"><a-typography-paragraph :copyable="{ text: secret, tooltips: ['复制', '已复制'] }" class="secret-text"><code>{{ secret }}</code></a-typography-paragraph></div>
    <template #footer><a-button type="primary" @click="open = false">我已妥善保存</a-button></template>
  </a-modal>
</template>
