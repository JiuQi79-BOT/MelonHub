<script setup lang="ts">
withDefaults(defineProps<{ title?: string; description?: string; compact?: boolean }>(), { title: '暂无数据', compact: false })
</script>

<template>
  <div class="empty-state" :class="{ compact }">
    <span class="empty-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 13.5 5.6 6.3A2 2 0 0 1 7.5 5h9a2 2 0 0 1 1.9 1.3L21 13.5" />
        <path d="M3 13.5V18a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-4.5h-5.2a.8.8 0 0 0-.7.5l-.4 1a.8.8 0 0 1-.7.5h-4a.8.8 0 0 1-.7-.5l-.4-1a.8.8 0 0 0-.7-.5Z" />
      </svg>
    </span>
    <div class="empty-title">{{ title }}</div>
    <p v-if="!compact && (description || $slots.description)" class="empty-desc"><slot name="description">{{ description }}</slot></p>
    <div v-if="$slots.default" class="empty-actions"><slot /></div>
  </div>
</template>
