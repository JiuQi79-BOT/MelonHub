<script setup lang="ts">
import { ref,onMounted,onBeforeUnmount } from 'vue'
import { X } from 'lucide-vue-next'
defineProps<{title:string;wide?:boolean}>()
const emit=defineEmits<{close:[]}>()
const panel=ref<HTMLElement>()
let previous:HTMLElement|null=null
function key(e:KeyboardEvent) {
  if(e.key==='Escape')emit('close')
  if(e.key==='Tab' && panel.value) {
    const fields=Array.from(panel.value.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select,textarea,a[href]')).filter(el=>el.offsetParent!==null)
    const first=fields[0],last=fields.at(-1)
    if(e.shiftKey && document.activeElement===first) {e.preventDefault();last?.focus()}
    else if(!e.shiftKey && document.activeElement===last) {e.preventDefault();first?.focus()}
  }
}
onMounted(()=>{previous=document.activeElement as HTMLElement;document.addEventListener('keydown',key);document.body.style.overflow='hidden';panel.value?.querySelector<HTMLElement>('input,select,textarea,button')?.focus()})
onBeforeUnmount(()=>{document.removeEventListener('keydown',key);document.body.style.overflow='';previous?.focus()})
</script>
<template><Teleport to="body"><div class="modal-backdrop" @mousedown.self="emit('close')"><section ref="panel" class="modal" :class="{wide}" role="dialog" aria-modal="true" aria-labelledby="modal-title"><header><h2 id="modal-title">{{title}}</h2><button class="icon-button" aria-label="关闭" title="关闭" @click="emit('close')"><X :size="20" /></button></header><slot /></section></div></Teleport></template>
