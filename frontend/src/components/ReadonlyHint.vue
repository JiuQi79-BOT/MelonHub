<script setup lang="ts">
import { useAuthorizationScope } from '../authorizationScope'
const scope = useAuthorizationScope()
</script>

<template>
  <a-alert v-if="scope.hasDomain && !scope.canDomain" type="info" show-icon class="block-gap"
           :message="`你对软件“${scope.application?.name}”只有查看权限，修改需要平台管理员或该软件的软件管理员。`" />
</template>
