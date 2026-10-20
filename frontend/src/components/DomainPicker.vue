<script setup lang="ts">
import { useHub } from '../store'
const hub = useHub()
</script>

<template>
  <div class="domain-picker">
    <a-tooltip title="角色、权限和用户授权按软件隔离">
      <span class="domain-label">软件</span>
    </a-tooltip>
    <a-select :value="hub.applicationId || undefined" :options="hub.applicationOptions" placeholder="选择软件" size="small" :bordered="false" show-search
              option-filter-prop="label" :dropdown-match-select-width="false" class="domain-select" @change="(v: unknown) => hub.setApplication(Number(v))" />
  </div>
</template>
