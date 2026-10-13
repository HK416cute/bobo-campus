<template>
  <div class="ai-page">
    <!-- 状态：先让管理员知道 AI 到底在不在工作，而不是看一堆 0 -->
    <el-alert
      v-if="settings.storageWarning"
      type="error"
      :closable="false"
      show-icon
      title="AI 配置表不可用，配置无法保存"
      :description="settings.storageWarning"
    />
    <el-alert
      v-else-if="settings.clientError"
      type="error"
      :closable="false"
      show-icon
      title="按当前配置无法构建模型客户端，AI 正在走规则降级"
      :description="settings.clientError"
    />
    <el-alert
      v-else-if="settings.degraded"
      type="warning"
      :closable="false"
      show-icon
      :title="`AI 当前处于规则模式：${settings.degradeReason || '未配置模型'}`"
      description="规则模式下发布助手与检索解析仍可用（结果会标注「规则推算」），只是语义泛化能力弱一些。在下面的表单里填好 Key 并保存即可启用模型。"
    />
    <el-alert
      v-else
      type="success"
      :closable="false"
      show-icon
      :title="`AI 已启用：${settings.model}`"
      description="配置改动保存后立即生效，无需重启容器。"
    />

    <!-- ============================ 模型配置 ============================ -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="section-head">
          <span>模型配置</span>
          <span class="head-hint">保存即生效，无需重启容器</span>
        </div>
      </template>

      <el-form :model="form" label-width="140px" class="settings-form">
        <el-form-item label="启用 AI">
          <el-switch v-model="form.enabled" active-text="开" inactive-text="关" inline-prompt />
          <span class="field-hint">关闭后所有场景走规则实现，不再产生任何模型调用与费用</span>
        </el-form-item>

        <el-form-item label="API Key">
          <!--
            安全约定：这里永远不预填明文。
            · 输入框为 password 类型，仅在用户主动点「眼睛」图标时明文显示（即"仅输入时可见"）
            · 服务端已配置时，placeholder 显示脱敏值（如 sk-709****407e）
            · 留空提交 = 不修改；要清空必须勾选下面的复选框
          -->
          <div class="key-row">
            <el-input
              v-model="form.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="keyPlaceholder"
              clearable
            />
            <el-tag v-if="settings.apiKeyConfigured" type="success" size="small" effect="plain">已配置</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">未配置</el-tag>
          </div>
          <div class="field-hint">
            留空表示保持原值不变；可直接粘贴 <code>Bearer sk-xxx</code>，保存时会自动去掉前缀。
          </div>
          <el-checkbox v-model="form.clearApiKey" class="danger-check">
            清空已保存的 Key（清空后 AI 立即降级为规则模式）
          </el-checkbox>
        </el-form-item>

        <el-form-item label="服务地址">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
          <div class="field-hint">换代理或自建网关只改这里；结尾多余斜杠会自动去掉。</div>
        </el-form-item>

        <el-form-item label="模型">
          <div class="model-row">
            <el-select v-model="form.model" allow-create filterable default-first-option style="width: 260px">
              <el-option v-for="m in MODEL_OPTIONS" :key="m.value" :label="m.label" :value="m.value" />
            </el-select>
            <span class="field-hint">
              <code>deepseek-chat</code> 便宜快；<code>deepseek-reasoner</code> 更准但更慢更贵
            </span>
          </div>
        </el-form-item>

        <el-divider content-position="left">调用参数</el-divider>

        <div class="grid-2">
          <el-form-item label="超时（毫秒）">
            <el-input-number v-model="form.timeoutMs" :min="1000" :max="300000" :step="1000" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="失败重试次数">
            <el-input-number v-model="form.maxAttempts" :min="1" :max="5" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="采样温度">
            <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="每日配额（次）">
            <el-input-number v-model="form.dailyQuota" :min="1" :max="100000" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="field-hint param-hint">
          结构化抽取要稳定，温度建议 0.1~0.3；配额用于防止脚本刷接口烧穿额度。
        </div>

        <el-divider content-position="left">限流与计价</el-divider>

        <div class="grid-2">
          <el-form-item label="全局 QPS">
            <el-input-number v-model="form.globalQps" :min="1" :max="1000" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="单用户 QPS">
            <el-input-number v-model="form.userQps" :min="1" :max="1000" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="输入单价（元/百万）">
            <el-input-number v-model="form.priceInput" :min="0" :step="0.5" :precision="4" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="输出单价（元/百万）">
            <el-input-number v-model="form.priceOutput" :min="0" :step="0.5" :precision="4" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="field-hint param-hint">单价只用于把 token 折算成成本展示，不影响实际计费。</div>

        <el-form-item class="actions">
          <el-button type="primary" :loading="saving" @click="saveSettings">保存并生效</el-button>
          <el-button :loading="testing" @click="testConnection">测试连通性</el-button>
          <el-button text @click="loadSettings">重新读取</el-button>
          <span v-if="settings.updateTime" class="field-hint">最后修改：{{ settings.updateTime }}</span>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="testResult"
        :type="testResult.success ? 'success' : 'error'"
        :closable="false"
        show-icon
        class="test-result"
        :title="testResult.success
          ? `连通正常（${testResult.model}，${testResult.latencyMs} ms）`
          : `测试失败：${testResult.message}`"
      >
        <div v-if="testResult.success" class="test-detail">
          模型回复片段：<code>{{ testResult.replyPreview }}</code>
          <span v-if="testResult.promptTokens"> · 用量 {{ testResult.promptTokens }}+{{ testResult.completionTokens }} tokens</span>
        </div>
      </el-alert>
    </el-card>

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

// ------------------------------------------------------------------ 模型配置
/**
 * 表单字段与后端 AiSettingsDTO 一一对应。
 * 语义：**留空 / null = 不修改**（部分更新），所以除了 enabled 与 key 之外，
 * 其余字段都从服务端读回当前值直接展示，用户不动就原样回传。
 */
const settings = ref({})
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

const MODEL_OPTIONS = [
  { label: 'deepseek-chat（通用，便宜快）', value: 'deepseek-chat' },
  { label: 'deepseek-reasoner（推理，更慢更贵）', value: 'deepseek-reasoner' }
]

const form = ref({
  enabled: true,
  apiKey: '',
  clearApiKey: false,
  baseUrl: '',
  model: 'deepseek-chat',
  timeoutMs: 15000,
  maxAttempts: 1,
  temperature: 0.2,
  globalQps: 10,
  userQps: 2,
  dailyQuota: 200,
  priceInput: 2,
  priceOutput: 8
})

/** Key 输入框的提示语：已配置时显示脱敏值，让管理员知道当前用的是哪个 Key 而不泄露它 */
const keyPlaceholder = ref('请输入 API Key')

function applySettings(data) {
  settings.value = data || {}
  form.value.enabled = data?.enabled !== false
  // 关键：apiKey 永远不预填明文，保持输入框为空
  form.value.apiKey = ''
  form.value.clearApiKey = false
  form.value.baseUrl = data?.baseUrl || ''
  form.value.model = data?.model || 'deepseek-chat'
  form.value.timeoutMs = data?.timeoutMs ?? 15000
  form.value.maxAttempts = data?.maxAttempts ?? 1
  form.value.temperature = data?.temperature ?? 0.2
  form.value.globalQps = data?.globalQps ?? 10
  form.value.userQps = data?.userQps ?? 2
  form.value.dailyQuota = data?.dailyQuota ?? 200
  form.value.priceInput = Number(data?.priceInput ?? 2)
  form.value.priceOutput = Number(data?.priceOutput ?? 8)
  keyPlaceholder.value = data?.apiKeyConfigured
    ? `已配置：${data.apiKeyMasked}（留空则不修改）`
    : '尚未配置，请填入 API Key'
}

async function loadSettings() {
  try {
    applySettings(await adminApi.aiGetSettings())
  } catch (e) {
    // 提示由 http.js 处理
  }
}

async function saveSettings() {
  if (form.value.clearApiKey && form.value.apiKey) {
    ElMessage.warning('既要清空又填了新 Key，请二选一')
    return
  }
  saving.value = true
  try {
    // 只提交需要变更的字段：Key 留空时不传，避免把已保存的 Key 覆盖成空
    const payload = {
      enabled: form.value.enabled,
      clearApiKey: form.value.clearApiKey,
      baseUrl: form.value.baseUrl,
      model: form.value.model,
      timeoutMs: form.value.timeoutMs,
      maxAttempts: form.value.maxAttempts,
      temperature: form.value.temperature,
      globalQps: form.value.globalQps,
      userQps: form.value.userQps,
      dailyQuota: form.value.dailyQuota,
      priceInput: form.value.priceInput,
      priceOutput: form.value.priceOutput
    }
    if (form.value.apiKey.trim()) {
      payload.apiKey = form.value.apiKey.trim()
    }
    applySettings(await adminApi.aiSaveSettings(payload))
    testResult.value = null
    ElMessage.success('配置已保存并立即生效')
    loadStatus()
  } catch (e) {
    // 提示由 http.js 处理
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  testing.value = true
  testResult.value = null
  try {
    // 用「已保存的」配置测试：未保存的改动不参与，避免误以为改了就生效
    testResult.value = await adminApi.aiTestSettings(form.value.model)
  } catch (e) {
    // 提示由 http.js 处理
  } finally {
    testing.value = false
  }
}

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
  loadSettings()
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

.head-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--bobo-text-secondary);
}

/* ---------------------------------------------------------------- 模型配置表单 */
.settings-form {
  max-width: 760px;
}

.settings-form .field-hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.7;
  color: var(--bobo-text-secondary);
}

.settings-form .param-hint {
  margin: -6px 0 14px;
}

.key-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.key-row .el-input {
  flex: 1;
}

.danger-check {
  margin-top: 4px;
}

.model-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
}

.settings-form .grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.settings-form .actions .el-form-item__content {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.test-result {
  margin-top: 4px;
}

.test-detail {
  font-size: 13px;
  line-height: 1.8;
}

.test-detail code {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--bobo-bg);
}

@media (max-width: 720px) {
  .settings-form .grid-2 {
    grid-template-columns: 1fr;
  }
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
