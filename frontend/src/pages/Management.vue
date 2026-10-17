<script setup lang="ts">
import { computed,ref,watch,onMounted } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { Plus,Search,Download,MoreHorizontal,ChevronLeft,ChevronRight,ShieldCheck,KeyRound,Pencil,Trash2,Power,Copy,Check,RefreshCw,Users,SlidersHorizontal } from 'lucide-vue-next'
import { useHub } from '../store'
import { api,json } from '../api'
import Modal from '../components/Modal.vue'
import AppIcon from '../components/AppIcon.vue'
type Row=Record<string,any>
type Entity='applications'|'departments'|'users'|'roles'|'permissions'|'audit'
const hub=useHub(),route=useRoute(),router=useRouter()
const entity=computed(()=>route.meta.key as Entity)
const singular:Record<Entity,string>={applications:'软件',departments:'部门',users:'用户',roles:'角色',permissions:'权限',audit:'审计日志'}
const subtitles:Record<Entity,string>={applications:'集中维护业务软件与 API 接入凭据。',departments:'管理组织部门及用户归属。',users:'维护用户身份，在隔离域中分配角色。',roles:'管理当前隔离域的角色与权限关联。',permissions:'维护当前隔离域的资源与操作标识。',audit:'追踪平台授权与配置变更。'}
const search=ref(''),status=ref('all'),page=ref(1),menu=ref<number|null>(null),modal=ref(''),selected=ref<Row|null>(null),form=ref<Row>({}),error=ref(''),saving=ref(false),chosenIds=ref<number[]>([]),secret=ref(''),copied=ref(false),auditScope=ref('all')
const rows=computed<Row[]>(()=>{
  const source=entity.value==='audit'?hub.audits:hub[entity.value]
  return (source as Row[]).filter(row=>{
    const matches=[row.name,row.code,row.externalId,row.email,row.actor,row.action,row.target].some(v=>String(v||'').toLowerCase().includes(search.value.toLowerCase()))
    const matchesStatus=status.value==='all'||Boolean(row.enabled)===(status.value==='enabled')
    const scope=entity.value!=='audit'||auditScope.value==='all'||(row.departmentName===hub.department?.name&&row.applicationName===hub.application?.name)
    return matches && matchesStatus && scope
  })
})
const pageCount=computed(()=>Math.max(1,Math.ceil(rows.value.length/10)))
const visible=computed(()=>rows.value.slice((page.value-1)*10,page.value*10))
const fields=computed(()=>{
  const common=[{key:'name',label:`${singular[entity.value]}名称`,max:100,required:true},{key:'code',label:'唯一编码',max:entity.value==='applications'||entity.value==='departments'?64:100,required:true}]
  if(entity.value==='users')return [{key:'name',label:'姓名',max:100,required:true},{key:'externalId',label:'用户标识',max:100,required:true},{key:'email',label:'邮箱',max:150,required:false}]
  if(entity.value==='applications'||entity.value==='roles')return [...common,{key:'description',label:'描述',max:500,required:false}]
  if(entity.value==='permissions')return [...common,{key:'resource',label:'资源标识',max:100,required:true},{key:'action',label:'操作标识',max:64,required:true}]
  return common
})
const hasStatus=computed(()=>['applications','users','roles'].includes(entity.value))
const domainEntity=computed(()=>['roles','permissions'].includes(entity.value))
watch([search,status,auditScope],()=>page.value=1)
watch(()=>rows.value.length,()=>page.value=Math.min(page.value,pageCount.value))
watch(()=>[hub.departmentId,hub.applicationId],()=>{modal.value='';menu.value=null;page.value=1})
onMounted(()=>{if(route.query.create==='1'&&entity.value==='applications'){openEdit();router.replace('/applications')}})
function openEdit(row:Row|null=null) {selected.value=row;form.value=row?{...row,departmentIds:[...(row.departmentIds||[])]}:{name:'',code:'',description:'',externalId:'',email:'',resource:'',action:'',departmentIds:hub.departmentId?[hub.departmentId]:[]};error.value='';modal.value='edit';menu.value=null}
async function save() {
  if(domainEntity.value&&(!hub.departmentId||!hub.applicationId)){error.value='请先创建部门和软件';return}
  saving.value=true;error.value=''
  try {
    const path=`/admin/${entity.value}${selected.value?`/${selected.value.id}`:''}${domainEntity.value?hub.query:''}`
    const body=Object.fromEntries(fields.value.map(field=>[field.key,typeof form.value[field.key]==='string'?form.value[field.key].trim():form.value[field.key]]))
    if(entity.value==='users')body.departmentIds=form.value.departmentIds
    const result=await api<{apiKey?:string}>(path,json(selected.value?'PUT':'POST',body))
    modal.value='';await hub.load();hub.notify(`${singular[entity.value]}已保存`)
    if(result.apiKey){secret.value=result.apiKey;modal.value='secret';copied.value=false}
  }catch(e){error.value=(e as Error).message}finally{saving.value=false}
}
async function grant(row:Row) {
  selected.value=row;error.value='';chosenIds.value=[];menu.value=null
  if(entity.value==='roles') {chosenIds.value=[...row.permissionIds];modal.value='permissions'}
  else {saving.value=true;try {const result=await api<{roleIds:number[]}>(`/admin/users/${row.id}/roles${hub.query}`);chosenIds.value=result.roleIds;modal.value='roles'}catch(e){hub.notify((e as Error).message,true)}finally{saving.value=false}}
}
async function saveGrant() {
  saving.value=true;error.value=''
  try {await api(`/admin/${entity.value}/${selected.value!.id}/${entity.value==='roles'?'permissions':'roles'}${hub.query}`,json('PUT',{ids:chosenIds.value}));modal.value='';await hub.load();hub.notify('授权关系已更新')}catch(e){error.value=(e as Error).message}finally{saving.value=false}
}
function confirm(row:Row,type:string) {selected.value=row;modal.value=type;error.value='';menu.value=null}
async function confirmAction() {
  saving.value=true;error.value=''
  try {
    if(modal.value==='rotate') {const result=await api<{apiKey:string}>(`/admin/applications/${selected.value!.id}/rotate-key`,{method:'POST'});secret.value=result.apiKey;modal.value='secret';copied.value=false;await hub.load();return}
    if(modal.value==='delete')await api(`/admin/${entity.value}/${selected.value!.id}${domainEntity.value?hub.query:''}`,{method:'DELETE'})
    else await api(`/admin/${entity.value}/${selected.value!.id}/status`,json('PATCH',{enabled:!selected.value!.enabled}))
    modal.value='';await hub.load();hub.notify('操作已完成')
  }catch(e){error.value=(e as Error).message}finally{saving.value=false}
}
async function copySecret() {try{await navigator.clipboard.writeText(secret.value);copied.value=true;hub.notify('密钥已复制')}catch{hub.notify('浏览器无法访问剪贴板，请在密钥框中选择内容',true)}}
function exportRows() {
  const columns=entity.value==='audit'?['id','actor','action','target','departmentName','applicationName','createdAt']:['id','name','code','externalId','email','description','enabled'].filter(k=>rows.value.some(r=>r[k]!=null))
  const csv='\ufeff'+[columns,...rows.value.map(row=>columns.map(k=>row[k]??''))].map(values=>values.map(v=>'"'+String(v).replace(/^[=+@-]/,"'$&").replaceAll('"','""')+'"').join(',')).join('\r\n')
  const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8;'}));const a=document.createElement('a');a.href=url;a.download=`melonhub-${entity.value}-${new Date().toISOString().slice(0,10)}.csv`;a.click();URL.revokeObjectURL(url)
}
function formatTime(s:string){return new Date(s).toLocaleString('zh-CN',{hour12:false})}
function names(ids:number[]){return ids.map(id=>hub.departments.find(d=>d.id===id)?.name).filter(Boolean).join('、')}
function close(){if(!saving.value){modal.value='';secret.value='';error.value=''}}
</script>
<template>
  <div class="page-heading"><div><div class="eyebrow">{{entity==='audit'?'SECURITY AUDIT':entity.toUpperCase()}}</div><h1>{{route.meta.title}}<span class="heading-count">{{rows.length}}</span></h1><p>{{subtitles[entity]}}</p></div><div class="heading-actions"><button class="button" @click="exportRows" :disabled="!rows.length"><Download :size="15" />导出</button><button v-if="entity!=='audit'" class="button primary" :disabled="domainEntity&&(!hub.departmentId||!hub.applicationId)" @click="openEdit()"><Plus :size="16" />{{entity==='applications'?'接入软件':`新建${singular[entity]}`}}</button><button v-else class="button primary" :disabled="hub.busy" @click="hub.load()"><RefreshCw :size="16" />刷新</button></div></div>
  <section class="section-surface management-surface"><div class="table-toolbar"><label class="search-box"><Search :size="17" /><input v-model="search" :placeholder="entity==='audit'?'搜索操作人、操作或对象…':`搜索${singular[entity]}名称或标识…`" aria-label="搜索" /></label><div class="table-filters"><SlidersHorizontal :size="15" class="muted" /><select v-if="hasStatus" v-model="status" aria-label="状态筛选"><option value="all">全部状态</option><option value="enabled">已启用</option><option value="disabled">已停用</option></select><select v-if="entity==='audit'" v-model="auditScope" aria-label="日志范围"><option value="all">全部隔离域</option><option value="current">当前隔离域</option></select><span v-if="domainEntity" class="badge neutral">{{hub.department?.name}} / {{hub.application?.name}}</span><span v-else class="muted filter-count">共 {{rows.length}} 条</span></div></div>
    <div class="table-scroll"><table class="data-table"><thead><tr>
      <template v-if="entity==='applications'"><th>软件</th><th>软件标识</th><th>角色 / 权限</th><th>状态</th><th>接入时间</th></template>
      <template v-if="entity==='departments'"><th>部门名称</th><th>部门标识</th><th>部门用户</th></template>
      <template v-if="entity==='users'"><th>用户</th><th>用户标识</th><th>归属部门</th><th>状态</th></template>
      <template v-if="entity==='roles'"><th>角色名称</th><th>角色标识</th><th>权限数</th><th>授权用户</th><th>状态</th></template>
      <template v-if="entity==='permissions'"><th>权限名称</th><th>权限标识</th><th>资源</th><th>操作</th><th>关联角色</th></template>
      <template v-if="entity==='audit'"><th>时间</th><th>操作人</th><th>事件</th><th>对象</th><th>隔离域</th></template>
      <th v-if="entity!=='audit'" class="actions-th">操作</th>
    </tr></thead><tbody><tr v-for="(row,i) in visible" :key="row.id">
      <template v-if="entity==='applications'"><td><div class="app-cell"><AppIcon :index="(page-1)*10+i" /><div><strong>{{row.name}}</strong><small>{{row.description||'未设置描述'}}</small></div></div></td><td><code>{{row.code}}</code></td><td class="tabular">{{row.roleCount}}<span class="muted"> / </span>{{row.permissionCount}}</td><td><span class="status-dot" :class="{disabled:!row.enabled}">{{row.enabled?'已启用':'已停用'}}</span></td><td class="muted nowrap">{{new Date(row.createdAt).toLocaleDateString('zh-CN')}}</td></template>
      <template v-if="entity==='departments'"><td><strong>{{row.name}}</strong></td><td><code>{{row.code}}</code></td><td>{{row.memberCount}} 人</td></template>
      <template v-if="entity==='users'"><td><div class="user-cell"><span class="user-avatar" :class="`tone-${i%6}`">{{row.name.slice(-2)}}</span><div><strong>{{row.name}}</strong><small>{{row.email||'未设置邮箱'}}</small></div></div></td><td><code>{{row.externalId}}</code></td><td class="department-cell">{{names(row.departmentIds)||'暂无归属部门'}}</td><td><span class="status-dot" :class="{disabled:!row.enabled}">{{row.enabled?'已启用':'已停用'}}</span></td></template>
      <template v-if="entity==='roles'"><td><div class="name-stack"><strong>{{row.name}}</strong><small>{{row.description||'未设置描述'}}</small></div></td><td><code>{{row.code}}</code></td><td><span class="badge neutral">{{row.permissionIds.length}} 项权限</span></td><td>{{row.memberCount}} 人</td><td><span class="status-dot" :class="{disabled:!row.enabled}">{{row.enabled?'已启用':'已停用'}}</span></td></template>
      <template v-if="entity==='permissions'"><td><strong>{{row.name}}</strong></td><td><code>{{row.code}}</code></td><td>{{row.resource}}</td><td><span class="badge blue">{{row.action}}</span></td><td>{{row.roleCount}} 个</td></template>
      <template v-if="entity==='audit'"><td class="muted nowrap">{{formatTime(row.createdAt)}}</td><td><span class="actor-label">{{row.actor}}</span></td><td><code class="audit-code">{{row.action}}</code></td><td class="target-cell">{{row.target}}</td><td class="muted">{{row.departmentName||'平台'}}<span v-if="row.applicationName"> / {{row.applicationName}}</span></td></template>
      <td v-if="entity!=='audit'" class="actions-cell"><div class="row-actions"><button v-if="entity==='roles'" class="text-link" @click="grant(row)"><KeyRound :size="14" />配置权限</button><button v-if="entity==='users'" class="text-link" :disabled="!row.departmentIds.includes(hub.departmentId)||!hub.applicationId" @click="grant(row)"><ShieldCheck :size="14" />分配角色</button><button v-if="entity==='applications'" class="text-link" @click="hub.applicationId=row.id;hub.loadScope();router.push('/roles')">管理<ChevronRight :size="13" /></button><button class="icon-button" :aria-label="`${row.name}的更多操作`" title="更多操作" :aria-expanded="menu===row.id" @click="menu=menu===row.id?null:row.id"><MoreHorizontal :size="19" /></button></div><div v-if="menu===row.id" class="row-menu"><button @click="openEdit(row)"><Pencil :size="14" />编辑{{singular[entity]}}</button><button v-if="entity==='applications'" @click="confirm(row,'rotate')"><RefreshCw :size="14" />重置 API Key</button><button v-if="hasStatus" @click="confirm(row,'status')"><Power :size="14" />{{row.enabled?'停用':'启用'}}</button><button class="danger" @click="confirm(row,'delete')"><Trash2 :size="14" />删除{{singular[entity]}}</button></div></td>
    </tr><tr v-if="!visible.length"><td colspan="7"><div class="empty"><Search :size="30" /><strong>{{search?'没有找到匹配记录':`暂无${singular[entity]}`}}</strong><button v-if="search" class="text-link" @click="search=''">清空搜索</button><button v-else-if="entity!=='audit'" class="text-link" :disabled="domainEntity&&(!hub.departmentId||!hub.applicationId)" @click="openEdit()">新建{{singular[entity]}}<Plus :size="14" /></button></div></td></tr></tbody></table></div>
    <div class="pagination"><span>共 {{rows.length}} 条<span class="pagination-detail">，每页 10 条</span></span><div><button class="icon-button" aria-label="上一页" title="上一页" :disabled="page<=1" @click="page--"><ChevronLeft :size="17" /></button><span class="page-number">{{page}}</span><span class="muted">/ {{pageCount}}</span><button class="icon-button" aria-label="下一页" title="下一页" :disabled="page>=pageCount" @click="page++"><ChevronRight :size="17" /></button></div></div>
  </section>
  <Modal v-if="modal==='edit'" :title="`${selected?'编辑':'新建'}${singular[entity]}`" @close="close"><form class="modal-form" @submit.prevent="save"><div v-if="domainEntity" class="form-scope">{{hub.department?.name}} / {{hub.application?.name}}</div><label v-for="field in fields" :key="field.key">{{field.label}}<span v-if="field.required" class="required">*</span><textarea v-if="field.key==='description'" v-model="form[field.key]" :maxlength="field.max" rows="3"></textarea><input v-else v-model="form[field.key]" :required="field.required" :maxlength="field.max" :type="field.key==='email'?'email':'text'" :pattern="field.key==='code'?'[a-zA-Z0-9_.:-]+':undefined" /></label><fieldset v-if="entity==='users'"><legend>归属部门</legend><div class="checkbox-list"><label v-for="d in hub.departments" :key="d.id" class="checkbox-option"><input type="checkbox" v-model="form.departmentIds" :value="d.id" /><span>{{d.name}}</span></label></div></fieldset><p v-if="error" class="inline-error" role="alert">{{error}}</p><div class="modal-actions"><button type="button" class="button" :disabled="saving" @click="close">取消</button><button class="button primary" :disabled="saving">{{saving?'保存中…':'保存'}}</button></div></form></Modal>
  <Modal v-if="modal==='permissions'||modal==='roles'" :title="`${modal==='permissions'?'配置权限':'分配角色'} · ${selected?.name}`" wide @close="close"><form class="modal-form" @submit.prevent="saveGrant"><div class="form-scope">{{hub.department?.name}} / {{hub.application?.name}}</div><div class="selection-toolbar"><span>已选择 {{chosenIds.length}} 项</span><button type="button" class="text-link" @click="chosenIds=(modal==='permissions'?hub.permissions:hub.roles).map(item=>item.id)">全选</button><button type="button" class="text-link" @click="chosenIds=[]">清空</button></div><div class="checkbox-list grant-list"><label v-for="item in (modal==='permissions'?hub.permissions:hub.roles)" :key="item.id" class="checkbox-option grant-option"><input type="checkbox" v-model="chosenIds" :value="item.id" /><div><strong>{{item.name}}</strong><code>{{item.code}}</code></div></label><div v-if="!(modal==='permissions'?hub.permissions:hub.roles).length" class="empty">当前隔离域暂无可选项</div></div><p v-if="error" class="inline-error" role="alert">{{error}}</p><div class="modal-actions"><button type="button" class="button" :disabled="saving" @click="close">取消</button><button class="button primary" :disabled="saving">{{saving?'保存中…':'保存授权'}}</button></div></form></Modal>
  <Modal v-if="['delete','status','rotate'].includes(modal)" :title="modal==='delete'?'删除确认':modal==='rotate'?'重置接口密钥':`${selected?.enabled?'停用':'启用'}${singular[entity]}`" @close="close"><div class="modal-form"><p class="confirm-copy">{{modal==='delete'?`确定删除「${selected?.name}」？角色和权限的关联会同步解除；仍有依赖的部门或软件无法删除。`:modal==='rotate'?`重置「${selected?.name}」的 API Key 后，原密钥立即失效。`: `确定${selected?.enabled?'停用':'启用'}「${selected?.name}」？`}}</p><p v-if="error" class="inline-error" role="alert">{{error}}</p><div class="modal-actions"><button class="button" :disabled="saving" @click="close">取消</button><button class="button" :class="modal==='delete'?'destructive':'primary'" :disabled="saving" @click="confirmAction">{{saving?'处理中…':'确认'}}</button></div></div></Modal>
  <Modal v-if="modal==='secret'" title="API Key 已生成" @close="close"><div class="modal-form"><p class="confirm-copy">密钥仅在此处展示一次。请交给该软件的可信后端保存。</p><label>API Key<div class="secret-input"><input :value="secret" readonly aria-label="新 API Key" /><button class="icon-button" aria-label="复制 API Key" title="复制 API Key" @click="copySecret"><Check v-if="copied" :size="17" /><Copy v-else :size="17" /></button></div></label><div class="modal-actions"><button class="button primary" @click="close">完成</button></div></div></Modal>
</template>
