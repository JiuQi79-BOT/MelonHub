<script setup lang="ts">
import { useHub } from '../store'
const hub = useHub()
</script>

<template>
  <div class="domain-picker">
    <a-tooltip title="软件隔离角色，权限归属角色；部门只筛选成员">
      <span class="domain-label">管理范围</span>
    </a-tooltip>
    <a-select :value="hub.departmentId" :options="[{ value: 0, label: '全部成员' }, ...hub.departmentOptions]" placeholder="选择部门" size="small" :bordered="false" show-search
              option-filter-prop="label" :dropdown-match-select-width="false" class="domain-select" @change="(v: unknown) => hub.setDomain(Number(v), undefined)" />
    <span class="domain-x">/</span>
    <a-select :value="hub.applicationId || undefined" :options="hub.applicationOptions" placeholder="选择软件" size="small" :bordered="false" show-search
              option-filter-prop="label" :dropdown-match-select-width="false" class="domain-select" @change="(v: unknown) => hub.setDomain(undefined, Number(v))" />
  </div>
</template>
