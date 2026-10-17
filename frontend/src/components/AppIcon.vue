<script setup lang="ts">
import { BriefcaseBusiness,ChartNoAxesCombined,FileText,FolderKanban,MessagesSquare,Users,BookOpen,Boxes,CodeXml } from 'lucide-vue-next'
defineProps<{index?:number;code?:string}>()
const icons=[FolderKanban,Users,FileText,ChartNoAxesCombined,BriefcaseBusiness,BookOpen,MessagesSquare,Boxes,CodeXml]
</script>
<template><span class="app-icon" :class="`tone-${(index||0)%6}`"><component :is="icons[(index||0)%icons.length]" :size="20" aria-hidden="true" /></span></template>
