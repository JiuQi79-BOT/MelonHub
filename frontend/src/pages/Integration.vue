<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { message, type TableColumnsType } from 'ant-design-vue'
import { useHub } from '../store'
import PageHead from '../components/PageHead.vue'

const hub = useHub()
const tab = ref('flow')

type Endpoint = { method: string; path: string; description: string }
const endpoints: Endpoint[] = [
  { method: 'POST', path: '/api/v1/contexts', description: '按当前软件的用户角色创建权限上下文；部门不影响授权' },
  { method: 'POST', path: '/api/v1/contexts/check', description: '批量校验权限，返回每个权限编码的允许/拒绝结果和总体 allowed' },
  { method: 'GET', path: '/api/v1/contexts/effective', description: '查询上下文当前的有效角色与权限，用于前端菜单或按钮展示' },
  { method: 'DELETE', path: '/api/v1/contexts', description: '用户退出登录时注销上下文' },
  { method: 'GET', path: '/api/v1/departments', description: '查询全部部门' },
  { method: 'GET', path: '/api/v1/departments/{dept}/users', description: '查询部门成员' },
  { method: 'GET / POST', path: '/api/v1/roles', description: '查询 / 创建本软件的角色' },
  { method: 'PUT / DELETE', path: '/api/v1/roles/{id}', description: '修改 / 删除角色' },
  { method: 'PATCH', path: '/api/v1/roles/{id}/status', description: '启用 / 停用角色' },
  { method: 'PUT', path: '/api/v1/roles/{id}/permissions', description: '整体替换角色的权限' },
  { method: 'GET / POST', path: '/api/v1/permissions', description: '查询 / 创建权限' },
  { method: 'PUT / DELETE', path: '/api/v1/permissions/{id}', description: '修改 / 删除权限' },
  { method: 'GET / PUT', path: '/api/v1/users/{id}/roles', description: '查询 / 整体替换用户在本软件的角色' },
]
const endpointColumns: TableColumnsType<Endpoint> = [
  { title: '方法', dataIndex: 'method', key: 'method', width: 130 },
  { title: '路径', dataIndex: 'path', key: 'path', width: 420 },
  { title: '说明', dataIndex: 'description' },
]
type ErrorRow = { status: string; code: string; meaning: string }
const errors: ErrorRow[] = [
  { status: '401', code: 'UNAUTHORIZED', meaning: 'API Key 缺失、错误、已吊销或已过期，或软件已停用。检查密钥配置，不要重试' },
  { status: '401', code: 'CONTEXT_INVALID', meaning: '上下文已失效（角色调整、用户停用、过期等）。重新创建，仍失败则让用户重新登录' },
  { status: '403', code: 'FORBIDDEN', meaning: '用户或软件已停用，或激活角色不在已分配范围内' },
  { status: '400', code: 'INVALID_REQUEST / INVALID_PATH', meaning: '请求参数或路径格式不正确' },
  { status: '429', code: 'RATE_LIMITED', meaning: '超过该软件每秒请求上限。按 Retry-After 退避后重试' },
  { status: '503', code: 'SERVICE_BUSY', meaning: '平台繁忙或数据库暂不可用。按 Retry-After 退避重试；关键操作应按拒绝处理' },
]
const errorColumns: TableColumnsType<ErrorRow> = [
  { title: 'HTTP', dataIndex: 'status', width: 70 },
  { title: 'code', dataIndex: 'code', key: 'code', width: 260 },
  { title: '含义与处理', dataIndex: 'meaning' },
]

const curl = computed(() => `# 业务系统后端调用，API Key 不要下发到浏览器或客户端
MELONHUB=http://127.0.0.1:8080
API_KEY='<在“软件接入”中创建的 API Key>'

# 1. 用户登录时创建软件上下文（省略 activeRoleIds 表示激活全部已分配角色）
curl -s -X POST "$MELONHUB/api/v1/contexts" \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: $API_KEY" \\
  -d '{"externalId":"zhangsan"}'

# 2. 每次受保护操作前校验
curl -s -X POST "$MELONHUB/api/v1/contexts/check" \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: $API_KEY" \\
  -H "X-Context-Token: <contextToken>" \\
  -d '{"permissions":["order:read","order:approve"]}'

# 3. 用户退出登录
curl -s -X DELETE "$MELONHUB/api/v1/contexts" \\
  -H "X-API-Key: $API_KEY" \\
  -H "X-Context-Token: <contextToken>"`)

const java = `// Spring Boot 3：RestClient 示例。contextToken 保存在业务系统自己的登录会话中。
RestClient melonhub = RestClient.builder()
        .baseUrl(System.getenv("MELONHUB_URL"))
        .defaultHeader("X-API-Key", System.getenv("MELONHUB_API_KEY"))
        .build();

Map<?, ?> context = melonhub.post().uri("/api/v1/contexts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("externalId", currentUser.getId()))
        .retrieve().body(Map.class);
session.setAttribute("melonhub.context", context.get("contextToken"));

boolean allowed;
try {
    Map<?, ?> result = melonhub.post().uri("/api/v1/contexts/check")
            .header("X-Context-Token", (String) session.getAttribute("melonhub.context"))
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("permissions", List.of("order:approve")))
            .retrieve().body(Map.class);
    allowed = Boolean.TRUE.equals(result.get("allowed"));
} catch (HttpClientErrorException.Unauthorized e) {
    allowed = false; // CONTEXT_INVALID：重新创建上下文，或要求用户重新登录
} catch (HttpServerErrorException.ServiceUnavailable e) {
    allowed = false; // 平台繁忙：对受保护操作按拒绝处理，稍后重试
}`

async function copy(text: string) {
  try { await navigator.clipboard.writeText(text); message.success('已复制') } catch { message.error('无法访问剪贴板，请手动复制') }
}

// ---- live debugging against /api/v1 ----
const debug = reactive<{ apiKey: string; externalId: string; departmentId?: number; permissions: string[]; token: string }>({
  apiKey: '', externalId: '', departmentId: hub.departmentId || undefined, permissions: [], token: '',
})
const response = ref<{ label: string; status: number; duration: number; requestId: string | null; body: unknown } | null>(null), calling = ref(false)
const formatted = computed(() => response.value ? JSON.stringify(response.value.body, null, 2) : '')
onBeforeUnmount(() => { debug.apiKey = ''; debug.token = '' })

async function call(label: string, method: string, path: string, body?: unknown) {
  if (!debug.apiKey.trim()) { message.warning('请填写 API Key'); return null }
  const headers: Record<string, string> = { Accept: 'application/json', 'X-API-Key': debug.apiKey.trim() }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (path !== '/contexts' || method === 'DELETE') headers['X-Context-Token'] = debug.token.trim()
  calling.value = true
  const start = performance.now()
  try {
    // Plain fetch: the console session token must not be sent with business API calls.
    const r = await fetch(`/api/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    const data = await r.json().catch(() => null)
    response.value = { label, status: r.status, duration: Math.round(performance.now() - start), requestId: r.headers.get('X-Request-Id'), body: data }
    return r.ok ? data as Record<string, unknown> : null
  } catch {
    response.value = { label, status: 0, duration: Math.round(performance.now() - start), requestId: null, body: { message: '无法连接服务' } }
    return null
  } finally {
    calling.value = false
  }
}
async function issue() {
  if (!debug.externalId.trim()) { message.warning('请填写外部标识'); return }
  const data = await call('创建上下文', 'POST', '/contexts', { externalId: debug.externalId.trim(), departmentId: debug.departmentId ?? 0 })
  if (data?.contextToken) debug.token = String(data.contextToken)
}
function needToken() { if (!debug.token.trim()) { message.warning('请先创建上下文或填写 Context Token'); return false } return true }
function effective() { if (needToken()) call('查询有效权限', 'GET', '/contexts/effective') }
function check() {
  if (!needToken()) return
  if (!debug.permissions.length) { message.warning('请输入至少一个权限编码'); return }
  call('校验权限', 'POST', '/contexts/check', { permissions: debug.permissions })
}
async function revoke() { if (needToken() && await call('注销上下文', 'DELETE', '/contexts')) debug.token = '' }
</script>

<template>
  <div class="page">
    <PageHead title="接入指南" />
    <a-card :bordered="false">
      <a-tabs v-model:active-key="tab">
        <a-tab-pane key="flow" tab="接入流程">
          <a-row :gutter="24">
            <a-col :xs="24" :lg="13">
              <a-timeline class="flow">
                <a-timeline-item><b>接入软件，保存 API Key</b><p>在“软件接入”创建软件，把一次性显示的 API Key 保存到业务后端的密钥配置中。</p></a-timeline-item>
                <a-timeline-item><b>同步用户与部门</b><p>用户的“外部标识”应与业务系统的用户 ID 一致，可按管理需要加入部门。</p></a-timeline-item>
                <a-timeline-item><b>定义权限和角色</b><p>在软件中创建共享权限，为角色勾选权限，再为用户分配软件角色。部门用于成员筛选和批量授权。</p></a-timeline-item>
                <a-timeline-item><b>登录时创建上下文</b><p>业务后端完成自身登录认证后，调用 <code>POST /api/v1/contexts</code>，把返回的 <code>contextToken</code> 存入业务会话。</p></a-timeline-item>
                <a-timeline-item><b>操作前校验</b><p>每次受保护操作前调用 <code>POST /api/v1/contexts/check</code>，<code>allowed</code> 为 true 才执行。</p></a-timeline-item>
                <a-timeline-item><b>退出时注销</b><p>用户退出时调用 <code>DELETE /api/v1/contexts</code>。</p></a-timeline-item>
              </a-timeline>
            </a-col>
            <a-col :xs="24" :lg="11">
              <div class="section-title flush">注意事项</div>
              <ul class="notes">
                <li><b>软件隔离角色和权限。</b>同一软件内的角色可以共享权限，部门仅用于成员管理与批量授权。创建上下文可以省略部门，部门变化不影响已有授权。</li>
                <li><b>每次受保护操作前实时校验。</b>角色停用、权限移除、重新分配或用户停用后，下一次校验即返回新结果，不要在业务侧长期缓存结果。</li>
                <li><b>只按权限编码匹配。</b>编码区分大小写；平台不解析资源、操作或业务规则，也不会撤回已执行的业务操作。</li>
              </ul>
            </a-col>
          </a-row>
        </a-tab-pane>

        <a-tab-pane key="api" tab="接口清单">
          <p class="muted">所有业务接口都需要请求头 <code>X-API-Key</code>。创建上下文无需 <code>X-Context-Token</code>；查询、校验和注销已有上下文时才需要携带。API Key 决定所属软件，只能访问本软件的数据。</p>
          <a-table :columns="endpointColumns" :data-source="endpoints" :row-key="(r: Endpoint) => r.method + r.path" size="small" :pagination="false">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'method'"><a-tag>{{ record.method }}</a-tag></template>
              <template v-else-if="column.key === 'path'"><code>{{ record.path }}</code></template>
            </template>
          </a-table>
          <h4 class="section-title">错误码</h4>
          <p class="muted">错误响应格式为 <code>{"code": "...", "message": "...", "requestId": "..."}</code>，响应头 <code>X-Request-Id</code> 可用于排查。</p>
          <a-table :columns="errorColumns" :data-source="errors" :row-key="(r: ErrorRow) => r.status + r.code" size="small" :pagination="false">
            <template #bodyCell="{ column, record }"><template v-if="column.key === 'code'"><code>{{ record.code }}</code></template></template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="code" tab="代码示例">
          <div class="code-head"><b>curl</b><a-button size="small" @click="copy(curl)">复制</a-button></div>
          <pre class="code-block">{{ curl }}</pre>
          <div class="code-head"><b>Java（Spring Boot 3）</b><a-button size="small" @click="copy(java)">复制</a-button></div>
          <pre class="code-block">{{ java }}</pre>
        </a-tab-pane>

        <a-tab-pane key="debug" tab="在线调试">
          <a-alert type="warning" show-icon class="block-gap" message="这里发出的是真实请求"
                   description="创建当前软件的上下文，来源部门可选。API Key 只保存在本页面内存中，请使用测试密钥。" />
          <a-row :gutter="16">
            <a-col :xs="24" :lg="10">
              <a-form layout="vertical">
                <a-form-item label="X-API-Key" required><a-input-password v-model:value="debug.apiKey" autocomplete="off" placeholder="mh_…" /></a-form-item>
                <a-row :gutter="12">
                  <a-col :span="12"><a-form-item label="外部标识" required><a-input v-model:value="debug.externalId" placeholder="用户的 externalId" /></a-form-item></a-col>
                  <a-col :span="12"><a-form-item label="来源部门"><a-select v-model:value="debug.departmentId" :options="hub.departmentOptions" allow-clear show-search option-filter-prop="label" /></a-form-item></a-col>
                </a-row>
                <a-button type="primary" block :loading="calling" class="block-gap" @click="issue">创建上下文</a-button>
                <a-form-item label="X-Context-Token"><a-input v-model:value="debug.token" placeholder="创建后自动填入" autocomplete="off" /></a-form-item>
                <a-form-item label="待校验权限"><a-select v-model:value="debug.permissions" mode="tags" placeholder="输入编码后回车" :token-separators="[',', ' ']" /></a-form-item>
                <a-space wrap>
                  <a-button :loading="calling" @click="check">校验权限</a-button>
                  <a-button :loading="calling" @click="effective">查询有效权限</a-button>
                  <a-button danger :loading="calling" @click="revoke">注销上下文</a-button>
                </a-space>
              </a-form>
            </a-col>
            <a-col :xs="24" :lg="14">
              <div class="code-head">
                <b>响应</b>
                <a-space v-if="response">
                  <span>{{ response.label }}</span>
                  <a-tag :color="response.status >= 200 && response.status < 300 ? 'green' : 'red'">HTTP {{ response.status || '—' }}</a-tag>
                  <span class="muted">{{ response.duration }} ms</span>
                </a-space>
              </div>
              <pre v-if="response" class="code-block response">{{ formatted }}</pre>
              <a-empty v-else description="尚未发送请求" />
              <p v-if="response?.requestId" class="muted">X-Request-Id：{{ response.requestId }}</p>
            </a-col>
          </a-row>
        </a-tab-pane>
      </a-tabs>
    </a-card>
  </div>
</template>
