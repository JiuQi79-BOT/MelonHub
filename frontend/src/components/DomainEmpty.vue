<script setup lang="ts">
import { useHub } from '../store'
import EmptyState from './EmptyState.vue'
const hub = useHub()
</script>

<template>
  <div class="panel">
    <EmptyState title="还没有可管理的软件"
                description="接入软件后即可配置角色和权限。">
      <router-link to="/departments"><a-button>管理部门</a-button></router-link>
      <router-link to="/applications"><a-button type="primary">接入软件</a-button></router-link>
    </EmptyState>
  </div>
</template>
