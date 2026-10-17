import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import Dashboard from './pages/Dashboard.vue'
import Management from './pages/Management.vue'
import Integration from './pages/Integration.vue'
import './style.css'

const router = createRouter({ history: createWebHashHistory(), routes: [
  {path:'/',component:Dashboard,meta:{title:'权限概览'}},
  ...[['applications','软件管理'],['departments','部门管理'],['users','用户管理'],['roles','角色管理'],['permissions','权限管理'],['audit','审计日志']].map(([key,title])=>({path:`/${key}`,component:Management,meta:{title,key}})),
  {path:'/integration',component:Integration,meta:{title:'API 联调'}},
  {path:'/:pathMatch(.*)*',redirect:'/'},
] })
createApp(App).use(createPinia()).use(router).mount('#app')
