<script setup lang="ts">
import { useHub } from '../store'
import EmptyState from './EmptyState.vue'
const hub = useHub()
</script>

<template>
  <div class="panel">
    <EmptyState :title="hub.departments.length ? '还没有可管理的软件' : '还没有部门'"
                description="先创建部门并接入软件，再在页面顶部选择部门和软件。">
      <router-link to="/departments"><a-button>管理部门</a-button></router-link>
      <router-link to="/applications"><a-button type="primary">接入软件</a-button></router-link>
    </EmptyState>
  </div>
</template>
