<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Grid } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { errorText, get } from '../api'
import { cacheStatus, fmt } from '../labels'
import type { SystemStatus } from '../types'
import PageHead from '../components/PageHead.vue'

const data = ref<SystemStatus | null>(null), loading = ref(false), failure = ref('')
const screens = Grid.useBreakpoint()
// The hash fills a whole row; a span wider than the current column count makes antd clamp it and warn.
const auditColumns = computed(() => screens.value.lg ? 3 : screens.value.sm ? 2 : 1)

function uptimeText(seconds: number) {
  const d = Math.floor(seconds / 86400), h = Math.floor((seconds % 86400) / 3600), m = Math.floor((seconds % 3600) / 60)
  return d ? `${d} 天 ${h} 小时` : h ? `${h} 小时 ${m} 分钟` : `${m} 分钟`
}
async function load() {
  loading.value = true
  failure.value = ''
  try {
    data.value = await get<SystemStatus>('/admin/system/status')
  } catch (e) {
    failure.value = errorText(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <PageHead title="系统状态">
      <a-button :loading="loading" @click="load"><ReloadOutlined />刷新</a-button>
    </PageHead>
    <a-alert v-if="failure" type="error" show-icon :message="failure" class="block-gap" />
    <template v-if="data">
      <a-alert v-if="data.database !== 'UP'" type="error" show-icon class="block-gap" message="数据库不可用"
               description="数据规模、待处理事项和审计链需要读取数据库，恢复后点击“刷新”。" />
      <a-card :bordered="false" title="运行时" class="block-gap">
        <a-descriptions size="small" bordered :column="{ xs: 1, sm: 2, lg: 3 }">
          <a-descriptions-item label="版本">{{ data.version }}</a-descriptions-item>
          <a-descriptions-item label="已运行">{{ uptimeText(data.uptimeSeconds) }}</a-descriptions-item>
          <a-descriptions-item label="Java">{{ data.javaVersion }}</a-descriptions-item>
          <a-descriptions-item label="数据库">
            <a-badge :status="data.database === 'UP' ? 'success' : 'error'" :text="`${data.database === 'UP' ? '正常' : '不可用'}（${data.databaseLatencyMs} ms）`" />
          </a-descriptions-item>
          <a-descriptions-item label="权限缓存">
            <a-badge :status="cacheStatus[data.cache]?.badge ?? 'default'" :text="cacheStatus[data.cache]?.text ?? data.cache" />
          </a-descriptions-item>
          <a-descriptions-item label="有效权限上下文">{{ data.counts?.activeContexts ?? '—' }}</a-descriptions-item>
        </a-descriptions>
      </a-card>

      <a-card :bordered="false" title="数据规模" class="block-gap">
        <div v-if="data.counts" class="status-grid">
          <a-statistic title="用户" :value="data.counts.users" :suffix="`/ 启用 ${data.counts.enabledUsers}`" />
          <a-statistic title="部门" :value="data.counts.departments" />
          <a-statistic title="软件" :value="data.counts.applications" :suffix="`/ 启用 ${data.counts.enabledApplications}`" />
          <a-statistic title="角色 / 权限" :value="`${data.counts.roles} / ${data.counts.permissions}`" />
          <a-statistic title="角色分配" :value="data.counts.assignments" />
        </div>
        <p v-else class="muted">暂时无法读取</p>
      </a-card>

      <a-card :bordered="false" title="待处理" class="block-gap">
        <div class="status-grid">
          <a-statistic title="待审批申请" :value="data.pendingRequests ?? '—'" />
          <a-statistic title="进行中的复核" :value="data.openReviews ?? '—'" />
          <a-statistic title="7 天内到期的授权" :value="data.expiringIn7Days ?? '—'" />
          <a-statistic title="Webhook 待投递" :value="data.webhookBacklog ?? '—'" />
        </div>
      </a-card>

      <a-card :bordered="false" title="审计链" class="block-gap">
        <a-descriptions v-if="data.audit" size="small" bordered :column="auditColumns">
          <a-descriptions-item label="记录总数">{{ data.audit.total }}</a-descriptions-item>
          <a-descriptions-item label="未封存">{{ data.audit.unsealed }}</a-descriptions-item>
          <a-descriptions-item label="更新时间">{{ fmt(data.audit.updatedAt) }}</a-descriptions-item>
          <a-descriptions-item label="链尾序号">{{ data.audit.lastSeq }}</a-descriptions-item>
          <a-descriptions-item label="链尾记录 ID">{{ data.audit.lastId || '—' }}</a-descriptions-item>
          <a-descriptions-item label="保留期清理">{{ data.audit.anchorSeq ? `已清理序号 ${data.audit.anchorSeq} 及之前` : '未清理' }}</a-descriptions-item>
          <a-descriptions-item label="最新哈希" :span="auditColumns"><code>{{ data.audit.lastHash || '—' }}</code></a-descriptions-item>
        </a-descriptions>
        <p v-else class="muted">暂时无法读取</p>
      </a-card>
    </template>
  </div>
</template>

<style scoped>
.status-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 16px 24px; }
.status-grid :deep(.ant-statistic-content-suffix) { color: var(--mh-text-3); font-size: 13px; }
@media (max-width: 768px) { .status-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
