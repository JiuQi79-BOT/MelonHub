import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import App from './App.vue'
import { useHub } from './store'
import './theme'
import './style.css'

dayjs.locale('zh-cn')

declare module 'vue-router' {
  interface RouteMeta { title: string; group?: string; domain?: boolean; superOnly?: boolean }
}

const routes: RouteRecordRaw[] = [
  { path: '/', component: () => import('./pages/Overview.vue'), meta: { title: '概览', domain: true } },
  { path: '/departments', component: () => import('./pages/Departments.vue'), meta: { title: '部门', group: '组织与软件' } },
  { path: '/users', component: () => import('./pages/Users.vue'), meta: { title: '用户', group: '组织与软件' } },
  { path: '/applications', component: () => import('./pages/Applications.vue'), meta: { title: '软件接入', group: '组织与软件' } },
  { path: '/roles', component: () => import('./pages/Roles.vue'), meta: { title: '角色', group: '授权管理', domain: true } },
  { path: '/permissions', component: () => import('./pages/Permissions.vue'), meta: { title: '权限', group: '授权管理', domain: true } },
  { path: '/assignments', component: () => import('./pages/Assignments.vue'), meta: { title: '用户授权', group: '授权管理', domain: true } },
  { path: '/approvals', component: () => import('./pages/Approvals.vue'), meta: { title: '审批中心', group: '授权治理' } },
  { path: '/reviews', component: () => import('./pages/Reviews.vue'), meta: { title: '权限复核', group: '授权治理' } },
  { path: '/simulate', component: () => import('./pages/Simulate.vue'), meta: { title: '权限诊断', group: '授权治理' } },
  { path: '/audit', component: () => import('./pages/Audit.vue'), meta: { title: '审计日志', group: '安全与审计' } },
  { path: '/status', component: () => import('./pages/Status.vue'), meta: { title: '系统状态', group: '安全与审计' } },
  { path: '/accounts', component: () => import('./pages/Accounts.vue'), meta: { title: '管理员账号', group: '安全与审计', superOnly: true } },
  { path: '/integration', component: () => import('./pages/Integration.vue'), meta: { title: '接入指南', group: '开发接入' } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({ history: createWebHashHistory(), routes })
const pinia = createPinia()
router.beforeEach(to => {
  const hub = useHub(pinia)
  if (to.meta.superOnly && hub.me && !hub.isSuper) return '/'
})
router.afterEach(to => { document.title = `${to.meta.title} · MelonHub 统一权限平台` })

createApp(App).use(pinia).use(router).mount('#app')
