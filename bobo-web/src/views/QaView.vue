<template>
  <div class="bobo-page qa-page">
    <div class="bobo-container narrow">
      <header class="qa-header">
        <div>
          <h1>智能问答</h1>
          <p class="bobo-muted">
            基于平台《交易规则》《违禁品清单》《纠纷处理流程》回答，答案末尾给出可核验的规则引用。
          </p>
        </div>
        <el-tag v-if="features" :type="features.ragReady ? 'success' : 'warning'" effect="plain" round>
          {{ features.ragReady ? `知识库 ${features.ragChunkCount} 个片段` : '知识库未建索引' }}
        </el-tag>
      </header>

      <el-alert
        v-if="features && !features.ragReady"
        type="warning"
        :closable="false"
        show-icon
        title="知识库尚未初始化"
        description="请管理员在「AI 运营 → 知识库」点一次「重建索引」，或重启后端服务。"
      />

      <div class="bobo-card ask-card">
        <div class="suggestions">
          <span class="bobo-muted">试试问：</span>
          <el-tag
            v-for="item in suggestions"
            :key="item"
            class="suggestion"
            effect="plain"
            round
            @click="useSuggestion(item)"
          >
            {{ item }}
          </el-tag>
        </div>
        <el-input
          v-model="question"
          type="textarea"
          :rows="3"
          maxlength="300"
          show-word-limit
          placeholder="例如：买到假货怎么办？可以申请退款吗？"
          @keydown.enter.exact.prevent="ask()"
        />
        <div class="ask-row">
          <span class="policy">
            <el-icon><Lock /></el-icon>
            回答仅基于知识库片段；没有依据时会明确回答「不在知识库范围」。
          </span>
          <el-button type="primary" class="bobo-btn-primary" :loading="asking" @click="ask()">
            提问
          </el-button>
        </div>
      </div>

      <div v-if="!history.length" class="empty">
        <el-icon :size="40"><ChatLineRound /></el-icon>
        <p>还没有提问。先试试上面的示例问题吧。</p>
      </div>

      <div v-for="item in history" :key="item.id" class="qa-item">
        <div class="question-line">
          <span class="question-badge">问</span>
          <span>{{ item.question }}</span>
        </div>

        <div class="bobo-card answer-card">
          <div v-if="item.loading" class="answer-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            正在检索规则并组织回答…
          </div>

          <template v-else-if="item.result">
            <div class="answer-meta">
              <el-tag :type="item.result.source === 'model' ? 'success' : 'info'" effect="plain" size="small">
                {{ item.result.source === 'model' ? 'AI 回答' : '规则摘录' }}
              </el-tag>
              <span class="bobo-muted" v-if="item.result.hitCount">
                命中 {{ item.result.hitCount }} 个规则片段 · {{ item.result.latencyMs }}ms
              </span>
              <el-tag v-if="!item.result.inScope" type="warning" effect="plain" size="small">拒答</el-tag>
            </div>

            <p class="answer-text">{{ item.result.answer }}</p>

            <p v-if="item.result.degradeNote" class="degrade-note">
              <el-icon><InfoFilled /></el-icon>
              {{ item.result.degradeNote }}
            </p>

            <div v-if="item.result.citations?.length" class="citations">
              <div class="citations-title">引用来源</div>
              <div v-for="citation in item.result.citations" :key="citation.ref" class="citation">
                <div class="citation-head">
                  <span class="ref">[{{ citation.ref }}]</span>
                  <strong>{{ citation.docTitle }}</strong>
                  <span class="bobo-muted">{{ citation.category }} · 片段 {{ citation.chunkNo }}</span>
                </div>
                <p class="snippet">{{ citation.snippet }}</p>
              </div>
            </div>
          </template>

          <div v-else class="answer-loading error">
            本次提问失败，请稍后重试。
          </div>
        </div>
      </div>

      <p class="footer-tip bobo-muted">
        问答结果不构成平台处理结论；涉及退款、封禁等处置，请以订单页与管理员通知为准。
      </p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ChatLineRound, InfoFilled, Loading, Lock } from '@element-plus/icons-vue'
import { aiApi } from '@/api'

const suggestions = [
  '买到假货怎么办？',
  '哪些东西不能在平台卖？',
  '已经付款了还能退款吗？',
  '当面交易要注意什么？'
]

const question = ref('')
const asking = ref(false)
const history = ref([])
const features = ref(null)

onMounted(async () => {
  features.value = await aiApi.features().catch(() => null)
})

function useSuggestion(text) {
  question.value = text
  ask(text)
}

async function ask(text) {
  const query = (text || question.value || '').trim()
  if (!query) {
    return
  }
  if (query.length > 300) {
    return
  }
  question.value = ''
  asking.value = true
  history.value.unshift({
    id: `${Date.now()}-${Math.random()}`,
    question: query,
    loading: true,
    result: null
  })
  const item = history.value[0]
  try {
    item.result = await aiApi.ask(query)
  } catch (e) {
    item.result = null
  } finally {
    item.loading = false
    asking.value = false
    if (history.value.length > 20) {
      history.value.splice(20)
    }
  }
}
</script>

<style scoped>
.narrow {
  max-width: 860px;
}

.qa-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.qa-header h1 {
  margin: 0 0 6px;
  font-size: 22px;
}

.qa-header p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
}

.ask-card {
  padding: 16px;
  margin-bottom: 18px;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 13px;
}

.suggestion {
  cursor: pointer;
}

.ask-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
}

.policy {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.empty {
  padding: 50px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.qa-item {
  margin-bottom: 18px;
}

.question-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
  font-weight: 600;
  line-height: 1.6;
}

.question-badge {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--bobo-primary);
  color: #1f2129;
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 1px;
}

.answer-card {
  padding: 14px 16px;
}

.answer-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.answer-loading.error {
  color: var(--el-color-danger);
}

.answer-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  margin-bottom: 8px;
}

.answer-text {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.85;
  font-size: 14px;
}

.degrade-note {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--el-color-warning);
}

.citations {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.citations-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.citation {
  padding: 8px 0;
}

.citation + .citation {
  border-top: 1px dotted var(--el-border-color-lighter);
}

.citation-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
}

.citation .ref {
  color: var(--el-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.citation .bobo-muted {
  font-size: 12px;
}

.snippet {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
  padding: 6px 8px;
}

.footer-tip {
  margin: 24px 0 8px;
  font-size: 12px;
  text-align: center;
}

@media (max-width: 640px) {
  .qa-header {
    flex-direction: column;
  }

  .ask-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
