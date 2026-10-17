<script setup lang="ts">
import { ref,onMounted,onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LayoutDashboard,AppWindow,Building2,Users,ShieldCheck,KeyRound,ScrollText,Braces,ChevronRight,ChevronDown,LogOut,Menu,CheckCircle2,AlertCircle,ArrowRight,LoaderCircle,PanelLeftClose,LockKeyhole } from 'lucide-vue-next'
import { api,json } from './api'
import { useHub } from './store'
const hub=useHub(),route=useRoute(),router=useRouter()
const authenticated=ref(false),initializing=ref(true),username=ref('admin'),password=ref(''),loginError=ref(''),loggingIn=ref(false),mobileOpen=ref(false),accountOpen=ref(false)
const nav=[{path:'/',title:'权限概览',icon:LayoutDashboard},{path:'/applications',title:'软件管理',icon:AppWindow},{path:'/departments',title:'部门管理',icon:Building2},{path:'/users',title:'用户管理',icon:Users},{path:'/roles',title:'角色管理',icon:ShieldCheck},{path:'/permissions',title:'权限管理',icon:KeyRound},{path:'/audit',title:'审计日志',icon:ScrollText},{path:'/integration',title:'API 联调',icon:Braces}]
async function login() {
  loggingIn.value=true;loginError.value=''
  try {const result=await api<{token:string;username:string}>('/auth/login',json('POST',{username:username.value,password:password.value}));sessionStorage.setItem('melonhub-token',result.token);username.value=result.username;password.value='';authenticated.value=true;await hub.load()}
  catch(e) {loginError.value=(e as Error).message} finally {loggingIn.value=false}
}
async function logout() {try {await api('/auth/logout',{method:'POST'})} finally {expire();accountOpen.value=false;router.push('/')}}
function expire() {authenticated.value=false;sessionStorage.removeItem('melonhub-token');hub.clear()}
onMounted(async()=>{
  window.addEventListener('session-expired',expire)
  if(sessionStorage.getItem('melonhub-token'))try {const me=await api<{username:string}>('/auth/me');username.value=me.username;authenticated.value=true;await hub.load()}catch {expire()}
  initializing.value=false
})
onBeforeUnmount(()=>window.removeEventListener('session-expired',expire))
</script>
<template>
  <div v-if="initializing" class="boot"><LoaderCircle class="spin" :size="28" /><span>正在连接 MelonHub</span></div>
  <main v-else-if="!authenticated" class="login-page">
    <div class="login-brand"><img src="/brand.png" alt="" /><strong>MelonHub</strong><span>统一权限平台</span></div>
    <section class="login-surface"><div class="login-emblem"><ShieldCheck :size="32" /></div><h1>登录管理控制台</h1><p class="muted">企业统一权限服务</p><form @submit.prevent="login"><label>管理员账号<input v-model="username" name="username" autocomplete="username" required maxlength="100" /></label><label>密码<input v-model="password" name="password" type="password" autocomplete="current-password" required maxlength="100" /></label><p v-if="loginError" class="inline-error" role="alert">{{loginError}}</p><button class="button primary" :disabled="loggingIn"><LoaderCircle v-if="loggingIn" :size="17" class="spin" /><span>{{loggingIn?'正在登录':'登录'}}</span><ArrowRight :size="17" /></button></form></section>
    <footer><LockKeyhole :size="14" /> MelonHub 权限服务 · RBAC0</footer>
  </main>
  <div v-else class="app-layout">
    <div v-if="mobileOpen" class="sidebar-scrim" @click="mobileOpen=false"></div>
    <aside class="sidebar" :class="{open:mobileOpen}">
      <RouterLink to="/" class="brand"><img src="/brand.png" alt="" /><div><strong>MelonHub</strong><span>统一权限平台</span></div><button class="icon-button mobile-only" aria-label="关闭导航" @click.prevent="mobileOpen=false"><PanelLeftClose :size="20" /></button></RouterLink>
      <div class="workspace-label"><span class="workspace-mark">M</span><div><strong>企业工作空间</strong><span>权限管理控制台</span></div><span class="small-dot"></span></div>
      <p class="nav-label">工作空间</p>
      <nav aria-label="主导航"><RouterLink v-for="item in nav.slice(0,6)" :key="item.path" :to="item.path" :class="{active:route.path===item.path}" @click="mobileOpen=false"><component :is="item.icon" :size="19" /><span>{{item.title}}</span><span v-if="item.path==='/applications'" class="nav-count">{{hub.applications.length}}</span></RouterLink><p class="nav-label">开发与安全</p><RouterLink v-for="item in nav.slice(6)" :key="item.path" :to="item.path" :class="{active:route.path===item.path}" @click="mobileOpen=false"><component :is="item.icon" :size="19" /><span>{{item.title}}</span></RouterLink></nav>
      <div class="sidebar-bottom"><div><ShieldCheck :size="18" /><strong>RBAC0</strong><span class="badge green">已启用</span></div><span>统一身份，独立权限域</span><div class="sidebar-version">MelonHub Console <span>v1.0.0</span></div></div>
    </aside>
    <div class="main-column">
      <header class="topbar"><div class="breadcrumb"><button class="icon-button mobile-only" title="打开导航" aria-label="打开导航" @click="mobileOpen=true"><Menu :size="20" /></button><span class="breadcrumb-workspace">工作空间</span><ChevronRight :size="14" /><strong>{{route.meta.title}}</strong></div><div class="topbar-right"><span class="service-status"><span class="small-dot" :class="{offline:!hub.health}"></span>{{hub.health?'服务运行正常':'服务连接异常'}}</span><span class="environment">本地环境</span><div class="account"><button class="account-button" @click="accountOpen=!accountOpen" :aria-expanded="accountOpen"><span class="avatar">A</span><span>{{username}}</span><ChevronDown :size="13" /></button><div v-if="accountOpen" class="account-menu"><span>平台管理员</span><button @click="logout"><LogOut :size="16" />退出登录</button></div></div></div></header>
      <div class="scope-toolbar"><span class="scope-label"><Building2 :size="16" />当前隔离域</span><label class="scope-select"><span>部门</span><select v-model="hub.departmentId" aria-label="当前部门" @change="hub.loadScope"><option v-for="d in hub.departments" :key="d.id" :value="d.id">{{d.name}}</option><option v-if="!hub.departments.length" :value="0">暂无部门</option></select></label><span class="scope-separator">/</span><label class="scope-select"><span>软件</span><select v-model="hub.applicationId" aria-label="当前软件" @change="hub.loadScope"><option v-for="a in hub.applications" :key="a.id" :value="a.id">{{a.name}}</option><option v-if="!hub.applications.length" :value="0">暂无软件</option></select></label><span class="scope-lock"><LockKeyhole :size="13" />域间隔离</span><LoaderCircle v-if="hub.busy" class="spin scope-spinner" :size="16" /></div>
      <main class="page-content"><RouterView :key="route.path" /></main>
      <footer class="main-footer"><span>MelonHub 统一权限平台</span><span>RBAC0 <span class="footer-dot">·</span> Vue + Spring Boot</span></footer>
    </div>
  </div>
  <Transition name="toast"><div v-if="hub.toast" class="toast" :class="{error:hub.toastError}" role="status"><AlertCircle v-if="hub.toastError" :size="18" /><CheckCircle2 v-else :size="18" />{{hub.toast}}</div></Transition>
</template>
