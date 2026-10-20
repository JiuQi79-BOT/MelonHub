<script setup lang="ts">
import EmptyState from './EmptyState.vue'
</script>

<template>
  <div class="panel">
    <EmptyState title="还没有可管理的软件"
                description="先接入软件，再在页面顶部选择软件。">
      <router-link to="/applications"><a-button type="primary">接入软件</a-button></router-link>
    </EmptyState>
  </div>
</template>
