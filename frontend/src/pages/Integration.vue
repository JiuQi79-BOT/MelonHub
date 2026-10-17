<script setup lang="ts">
import { ref,computed } from 'vue'
import { Braces,Play,Send,RefreshCw,Copy,Check,Terminal,ShieldCheck,Clock,KeyRound,ArrowRight,CheckCircle2,AlertCircle } from 'lucide-vue-next'
import { useHub } from '../store'
import { api,json,ApiError } from '../api'
const hub=useHub(),tab=ref('context'),mode=ref('admin'),key=ref(''),userId=ref(hub.users[0]?.externalId||''),departmentId=ref(hub.departmentId),token=ref(''),requested=ref('project:read, project:create'),busy=ref(false),result=ref<unknown>(null),responseStatus=ref(0),duration=ref(0),copied=ref(false)
const history=ref<{token:string;label:string}[]>([])
const endpoints=[['GET','/api/v1/departments','查询组织部门'],['GET','/api/v1/departments/{dept}/users','查询部门用户'],['GET / POST','/api/v1/departments/{dept}/roles','查询 / 创建角色'],['PUT / DELETE','/api/v1/departments/{dept}/roles/{id}','更新 / 删除角色'],['PATCH','/api/v1/departments/{dept}/roles/{id}/status','启用 / 停用角色'],['GET / POST','/api/v1/departments/{dept}/permissions','查询 / 创建权限'],['PUT / DELETE','/api/v1/departments/{dept}/permissions/{id}','更新 / 删除权限'],['PUT','/api/v1/departments/{dept}/roles/{id}/permissions','替换角色权限关联'],['GET / PUT','/api/v1/departments/{dept}/users/{id}/roles','查询 / 替换用户角色'],['POST','/api/v1/contexts','创建上下文 / 切换部门'],['GET','/api/v1/contexts/effective','查询有效角色与权限'],['POST','/api/v1/contexts/check','批量权限校验'],['DELETE','/api/v1/contexts','注销权限上下文']]
const formatted=computed(()=>result.value?JSON.stringify(result.value,null,2):'')
const snippet=computed(()=>`curl -X POST http://127.0.0.1:8080/api/v1/contexts \\\n+  -H "Content-Type: application/json" \\\n+  -H "X-API-Key: <YOUR_API_KEY>" \\\n+  -d '{"externalId":"${userId.value||'chenming'}","departmentId":${departmentId.value||1}}'\n\n+curl -X POST http://127.0.0.1:8080/api/v1/contexts/check \\\n+  -H "Content-Type: application/json" \\\n+  -H "X-API-Key: <YOUR_API_KEY>" \\\n+  -H "X-Context-Token: <CONTEXT_TOKEN>" \\\n+  -d '{"permissions":["project:read"]}'`)
async function request(action:string) {
  if(mode.value==='business'&&!key.value.trim()){hub.notify('请输入当前软件的 API Key',true);return}
  if(action==='issue'&&!userId.value){hub.notify('请选择用户',true);return}
  if(action!=='issue'&&!token.value){hub.notify('请先创建权限上下文',true);return}
  const permissions=requested.value.split(',').map(p=>p.trim()).filter(Boolean)
  if(action==='check'&&!permissions.length){hub.notify('请输入至少一个权限标识',true);return}
  busy.value=true;result.value=null;responseStatus.value=0;hub.toast=''
  const start=performance.now()
  const base=mode.value==='business'?'/v1/contexts':'/admin/contexts'
  const query=mode.value==='business'?'':`?applicationId=${hub.applicationId}`
  const headers:Record<string,string>={}
  if(mode.value==='business')headers['X-API-Key']=key.value.trim()
  if(action!=='issue')headers['X-Context-Token']=token.value
  try {
    const path=base+(action==='issue'?'':action==='effective'?'/effective':'/check')+query
    const options=action==='effective'?{method:'GET',headers}:{...json('POST',action==='issue'?{externalId:userId.value,departmentId:departmentId.value}:{permissions}),headers}
    const response=await api<Record<string,unknown>>(path,options);result.value=response;responseStatus.value=200
    if(action==='issue') {
      token.value=response.contextToken as string
      history.value.unshift({token:token.value,label:`${hub.departments.find(d=>d.id===departmentId.value)?.name} · ${hub.application?.name} · v${response.contextVersion}`})
      history.value=history.value.slice(0,8);hub.notify('权限上下文已创建');await hub.load()
    }
  }catch(e){responseStatus.value=e instanceof ApiError?e.status:500;result.value={code:e instanceof ApiError?e.code:'NETWORK_ERROR',message:(e as Error).message}}finally{duration.value=Math.round(performance.now()-start);busy.value=false}
}
async function copy(){try{await navigator.clipboard.writeText(snippet.value);copied.value=true;setTimeout(()=>copied.value=false,2000)}catch{hub.notify('无法访问剪贴板',true)}}
</script>
<template>
  <div class="page-heading"><div><div class="eyebrow">DEVELOPER WORKSPACE</div><h1>API 联调</h1><p>{{hub.application?.name||'请选择软件'}}<span class="subtitle-separator">/</span><code>{{hub.application?.code||'--'}}</code></p></div><span class="badge green outlined"><Braces :size="14" />REST API · v1</span></div>
  <div class="page-tabs" role="tablist"><button :class="{active:tab==='context'}" role="tab" :aria-selected="tab==='context'" @click="tab='context'"><ShieldCheck :size="16" />权限上下文</button><button :class="{active:tab==='reference'}" role="tab" :aria-selected="tab==='reference'" @click="tab='reference'"><Braces :size="16" />接口参考</button><button :class="{active:tab==='example'}" role="tab" :aria-selected="tab==='example'" @click="tab='example'"><Terminal :size="16" />请求示例</button></div>
  <div v-if="tab==='context'" class="integration-grid"><section class="section-surface"><div class="section-heading"><h2>请求配置</h2><span class="badge neutral">{{mode==='admin'?'管理员联调':'软件 API'}}</span></div><form class="integration-form" @submit.prevent="request('issue')"><div class="segmented"><button type="button" :class="{active:mode==='admin'}" @click="mode='admin'">管理员联调</button><button type="button" :class="{active:mode==='business'}" @click="mode='business'">软件 API</button></div><label v-if="mode==='business'">X-API-Key<input v-model="key" type="password" autocomplete="off" placeholder="mh_…" /></label><label>用户<select v-model="userId" required><option value="" disabled>选择用户</option><option v-for="u in hub.users" :key="u.id" :value="u.externalId">{{u.name}} · {{u.externalId}}</option></select></label><label>目标部门<select v-model="departmentId" required><option v-for="d in hub.departments" :key="d.id" :value="d.id">{{d.name}}</option></select></label><button class="button primary full-width" :disabled="busy||!hub.applicationId||!departmentId"><Play :size="15" />{{busy?'请求中…':'创建上下文 / 切换部门'}}</button><div class="form-divider"></div><label>权限上下文<select v-if="history.length" v-model="token" aria-label="选择当前或历史上下文"><option v-for="(h,i) in history" :key="h.token" :value="h.token">{{i===0?'最新':'历史'}} · {{h.label}}</option></select><input v-else v-model="token" placeholder="Context Token" autocomplete="off" /></label><label>待校验权限<input v-model="requested" placeholder="project:read, project:create" /></label><div class="integration-actions"><button type="button" class="button" :disabled="busy||!token" @click="request('effective')"><RefreshCw :size="15" />查询权限</button><button type="button" class="button primary" :disabled="busy||!token" @click="request('check')"><Send :size="15" />执行校验</button></div></form></section><section class="section-surface response-surface"><div class="section-heading"><h2>响应结果</h2><div v-if="responseStatus" class="response-meta"><span class="badge" :class="responseStatus===200?'green':'red'">{{responseStatus}}</span><span><Clock :size="12" />{{duration}} ms</span></div></div><pre v-if="formatted" class="response-code">{{formatted}}</pre><div v-else class="response-empty"><Braces :size="36" /><strong>{{busy?'等待服务响应':'暂无响应'}}</strong><code>JSON RESPONSE</code></div><div class="surface-footer"><CheckCircle2 v-if="responseStatus===200" :size="15" /><AlertCircle v-else-if="responseStatus>=400" :size="15" /><KeyRound v-else :size="15" /><span>{{responseStatus===200?'请求已完成':responseStatus>=400?'请求被拒绝':'等待请求'}}</span></div></section></div>
  <section v-if="tab==='reference'" class="section-surface"><div class="section-heading"><h2>业务软件接口</h2><span class="badge neutral">X-API-Key</span></div><div class="table-scroll"><table class="data-table endpoint-table"><thead><tr><th>方法</th><th>路径</th><th>操作</th></tr></thead><tbody><tr v-for="endpoint in endpoints" :key="endpoint[1]+endpoint[0]"><td><span class="method-label" :class="{post:endpoint[0].includes('POST'),put:endpoint[0].includes('PUT')}">{{endpoint[0]}}</span></td><td><code>{{endpoint[1]}}</code></td><td>{{endpoint[2]}}</td></tr></tbody></table></div></section>
  <section v-if="tab==='example'" class="section-surface"><div class="section-heading"><h2>创建与校验权限上下文</h2><button class="button small" @click="copy"><Check v-if="copied" :size="15" /><Copy v-else :size="15" />{{copied?'已复制':'复制'}}</button></div><pre class="example-code">{{snippet}}</pre></section>
</template>
