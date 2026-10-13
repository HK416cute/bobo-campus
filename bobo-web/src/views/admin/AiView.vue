<template>
  <div class="ai-page">
    <!-- 状态：先让管理员知道 AI 到底在不在工作，而不是看一堆 0 -->
    <el-alert
      v-if="status && !status.available"
      type="warning"
      :closable="false"
      show-icon
      :title="`AI 当前处于规则模式：${status.reason || '未配置模型'}`"
      description="规则模式下发布助手与检索解析仍可用（结果会标注「规则推算」），只是语义泛化能力弱一些。配置 BOBO_AI_API_KEY 后无需改代码即可启用模型。"
    />

    <div class="cards">
      <el-card shadow="never" class="card">
        <div class="card-label">累计调用</div>
        <div class="card-value">{{ metrics.totalCalls }}</div>
        <div class="card-sub">
          成功 {{ metrics.successCount }} · 降级 {{ metrics.degradedCount }} · 失败 {{ metrics.failedCount }}
        </div>
      </el-card>

      <el-card shadow="never" class="card">
        <div class="card-label">成功率</div>
        <div class="card-value">{{ percent(metrics.successRate) }}</div>
        <div class="card-sub">降级率 {{ percent(metrics.degradeRate) }}</div>
      </el-card>

      <el-card shadow="never" class="card">
        <div class="card-label">发布建议采纳率</div>
        <div class="card-value">{{ percent(metrics.publishDraftAcceptRate) }}</div>
        <div class="card-sub">
          {{ metrics.publishDraftAccepted }} / {{ metrics.publishDraftTotal }} 条反馈
        </div>
      </el-card>

      <el-card shadow="never" class="card">
        <div class="card-label">检索改写采纳率</div>
        <div class="card-value">{{ percent(metrics.searchQueryAcceptRate) }}</div>
        <div class="card-sub">
          {{ metrics.searchQueryAccepted }} / {{ metrics.searchQueryTotal }} 条反馈
        </div>
      </el-card>

      <el-card shadow="never" class="card">
        <div class="card-label">耗时 P50 / P95 / P99</div>
        <div class="card-value small">
          {{ metrics.p50LatencyMs }} / {{ metrics.p95LatencyMs }} / {{ metrics.p99LatencyMs }} ms
        </div>
        <div class="card-sub">只统计成功调用</div>
      </el-card>

      <el-card shadow="never" class="card">
        <div class="card-label">累计估算成本</div>
        <div class="card-value">¥{{ Number(metrics.estimatedCost || 0).toFixed(4) }}</div>
        <div class="card-sub">{{ metrics.totalTokens }} tokens（按配置单价折算）</div>
      </el-card>
    </div>

    <el-card shadow="never" class="section">
      <template #header>
        <div class="section-head">
          <span>分场景明细</span>
          <el-button text :icon="Refresh" @click="loadMetrics">刷新</el-button>
        </div>
      </template>
      <el-table :data="metrics.scenes || []" size="small" empty-text="暂无调用记录">
        <el-table-column prop="scene" label="场景" min-width="140">
          <template #default="{ row }">{{ sceneText(row.scene) }}</template>
        </el-table-column>
        <el-table-column prop="calls" label="调用" width="80" />
        <el-table-column prop="successCount" label="成功" width="80" />
        <el-table-column prop="degradedCount" label="降级" width="80" />
        <el-table-column prop="failedCount" label="失败" width="80" />
        <el-table-column label="平均耗时" width="110">
          <template #default="{ row }">{{ row.avgLatencyMs }} ms</template>
        </el-table-column>
        <el-table-column label="估算成本" width="120">
          <template #default="{ row }">¥{{ Number(row.estimatedCost || 0).toFixed(6) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>
        <div class="section-head">
          <span>运营周报</span>
          <div class="head-actions">
            <el-input-number v-model="reportDays" :min="1" :max="30" size="small" style="width: 110px" />
            <el-button type="primary" size="small" :loading="reportLoading" @click="loadReport">
              生成周报
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="reportNote"
        type="info"
        :closable="false"
        :title="reportNote"
        class="report-note"
      />

      <pre v-if="report" class="report-text">{{ report }}</pre>
      <el-empty v-else description="点击「生成周报」，让 AI 把看板数字写成结论" :image-size="80" />

      <el-collapse v-if="facts" class="facts">
        <el-collapse-item title="查看事实快照（AI 写周报只依据这些数字）">
          <pre class="facts-text">{{ JSON.stringify(facts, null, 2) }}</pre>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>
        <div class="section-head">
          <span>内容审核（AI 风险预判）</span>
        </div>
      </template>
      <div class="moderate-row">
        <el-input
          v-model="moderateText"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="粘贴一段商品描述或评价内容，看看模型怎么判风险"
        />
        <div class="moderate-actions">
          <el-select v-model="moderateType" size="default" style="width: 120px">
            <el-option label="商品" value="item" />
            <el-option label="评价" value="review" />
            <el-option label="私信" value="message" />
          </el-select>
          <el-button type="primary" :loading="moderateLoading" :disabled="!moderateText.trim()" @click="runModerate">
            预判风险
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="moderateResult"
        class="moderate-result"
        :type="riskType(moderateResult.riskLevel)"
        :closable="false"
        show-icon
        :title="`风险等级：${riskText(moderateResult.riskLevel)}${moderateResult.autoReject ? '（建议拦截）' : ''}`"
      >
        <div class="moderate-detail">
          <div>类目：{{ (moderateResult.riskCategories || []).join('、') || '无' }}</div>
          <div>理由：{{ moderateResult.reason }}</div>
          <div>来源：{{ moderateResult.source === 'model' ? '模型判定' : '本地关键词规则' }}</div>
        </div>
      </el-alert>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>
        <div class="section-head">
          <span>审核结论记录</span>
          <el-button text :icon="Refresh" @click="loadModerationList">刷新</el-button>
        </div>
      </template>
      <el-table :data="moderationList" size="small" empty-text="暂无审核记录">
        <el-table-column prop="targetType" label="对象" width="90">
          <template #default="{ row }">{{ targetText(row.targetType) }}</template>
        </el-table-column>
        <el-table-column prop="targetId" label="对象ID" width="90" />
        <el-table-column label="风险" width="90">
          <template #default="{ row }">
            <el-tag :type="riskType(row.riskLevel)" size="small">{{ riskText(row.riskLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="riskCategories" label="类目" min-width="140" />
        <el-table-column prop="reason" label="理由" min-width="240" show-overflow-tooltip />
        <el-table-column prop="decisionSource" label="来源" width="80">
          <template #default="{ row }">{{ row.decisionSource === 'model' ? '模型' : '规则' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { adminApi, aiApi } from '@/api'

defineOptions({ name: 'AdminAiView' })

const SCENE_TEXT = {
  publish_draft: 'AI 发布助手',
  search_parse: '检索意图解析',
  search_rerank: '检索结果重排',
  moderation: '内容审核',
  admin_report: '运营周报',
  admin_ask: '运营问答'
}

const status = ref(null)
const metrics = ref({})
const moderationList = ref([])

const reportDays = ref(7)
const reportLoading = ref(false)
const report = ref('')
const reportNote = ref('')
const facts = ref(null)

const moderateText = ref('')
const moderateType = ref('item')
const moderateLoading = ref(false)
const moderateResult = ref(null)

function percent(value) {
  return `${((Number(value) || 0) * 100).toFixed(1)}%`
}

function sceneText(scene) {
  return SCENE_TEXT[scene] || scene
}

function riskText(level) {
  return ['低风险', '中风险', '高风险'][Number(level)] || '未知'
}

function riskType(level) {
  return ['info', 'warning', 'error'][Number(level)] || 'info'
}

function targetText(type) {
  return { item: '商品', review: '评价', message: '私信' }[type] || type
}

async function loadStatus() {
  status.value = await aiApi.status().catch(() => null)
}

async function loadMetrics() {
  try {
    metrics.value = await adminApi.aiMetrics()
  } catch (e) {
    // 03-ai.sql 未执行时后端会返回空统计而非报错；这里兜底避免页面崩
    metrics.value = {}
  }
}

async function loadModerationList() {
  try {
    moderationList.value = await adminApi.aiModerationList({})
  } catch (e) {
    moderationList.value = []
  }
}

async function loadReport() {
  reportLoading.value = true
  try {
    const data = await adminApi.aiReport(reportDays.value)
    report.value = data.report || ''
    facts.value = data.facts || null
    reportNote.value = data.source === 'rule'
      ? `当前为规则模式：${data.degradeNote || '未启用模型'}`
      : `由 ${status.value?.model || '模型'} 生成，耗时 ${data.latencyMs} ms`
  } catch (e) {
    // 提示由 http.js 处理
  } finally {
    reportLoading.value = false
  }
}

async function runModerate() {
  moderateLoading.value = true
  try {
    moderateResult.value = await adminApi.aiModerate({
      targetType: moderateType.value,
      content: moderateText.value.trim()
    })
    loadModerationList()
  } catch (e) {
    ElMessage.error('预判失败，请稍后重试')
  } finally {
    moderateLoading.value = false
  }
}

onMounted(() => {
  loadStatus()
  loadMetrics()
  loadModerationList()
})
</script>

<style scoped>
.ai-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}

.card-label {
  font-size: 13px;
  color: var(--bobo-text-secondary);
}

.card-value {
  margin: 6px 0 4px;
  font-size: 24px;
  font-weight: 700;
  color: var(--bobo-text);
}

.card-value.small {
  font-size: 18px;
}

.card-sub {
  font-size: 12px;
  color: var(--bobo-text-secondary);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.report-note {
  margin-bottom: 12px;
}

.report-text {
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  background: var(--bobo-bg);
  font-family: inherit;
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.facts {
  margin-top: 12px;
}

.facts-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  max-height: 320px;
  overflow: auto;
}

.moderate-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.moderate-actions {
  display: flex;
  gap: 10px;
}

.moderate-result {
  margin-top: 12px;
}

.moderate-detail {
  font-size: 13px;
  line-height: 1.8;
}
</style>
