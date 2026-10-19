<script setup lang="ts">
import { useHub } from '../store'
const hub = useHub()
</script>

<template>
  <div class="domain-picker">
    <a-tooltip title="角色、权限和用户授权都按“部门 × 软件”隔离，切换后页面只显示该授权域的数据">
      <span class="domain-label">授权域</span>
    </a-tooltip>
    <a-select :value="hub.departmentId || undefined" :options="hub.departmentOptions" placeholder="选择部门" size="small" :bordered="false" show-search
              option-filter-prop="label" :dropdown-match-select-width="false" class="domain-select" @change="(v: unknown) => hub.setDomain(Number(v), undefined)" />
    <span class="domain-x">×</span>
    <a-select :value="hub.applicationId || undefined" :options="hub.applicationOptions" placeholder="选择软件" size="small" :bordered="false" show-search
              option-filter-prop="label" :dropdown-match-select-width="false" class="domain-select" @change="(v: unknown) => hub.setDomain(undefined, Number(v))" />
  </div>
</template>
