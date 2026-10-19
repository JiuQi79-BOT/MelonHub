<script setup lang="ts">
defineProps<{ title?: string; description?: string }>()
</script>

<template>
  <div class="page-head">
    <div class="page-head-main">
      <h2 v-if="title">{{ title }}</h2>
      <p v-if="description || $slots.description" class="page-desc"><slot name="description">{{ description }}</slot></p>
    </div>
    <div v-if="$slots.default" class="page-head-extra"><a-space wrap><slot /></a-space></div>
  </div>
</template>
