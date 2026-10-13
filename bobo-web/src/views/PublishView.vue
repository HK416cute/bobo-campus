<template>
  <div class="bobo-page">
    <div class="bobo-container narrow">
      <div class="bobo-card">
        <h2 class="page-title">{{ isEdit ? '编辑宝贝' : '发布闲置' }}</h2>
        <p class="bobo-muted sub">
          {{ isEdit ? '修改后会重新进入审核，审核通过后才会展示给买家' : '发布后需要管理员审核，通常几分钟内完成' }}
        </p>

        <!--
          待审核配额提示：同一用户最多同时挂 pendingLimit 件待审核商品（第 6 件会被后端拒绝）。
          提前显示「还能发几件」，避免用户把表单填完才被拒——那种体验最差。
        -->
        <el-alert
          v-if="!isEdit && quota.known"
          class="quota-tip"
          :type="quota.full ? 'error' : (quota.remaining <= 2 ? 'warning' : 'info')"
          :closable="false"
          show-icon
          :title="quota.full
            ? `你已有 ${quota.pending} 件商品在审核中，达到上限 ${quota.limit} 件`
            : `审核中的商品 ${quota.pending}/${quota.limit} 件，还可以发布 ${quota.remaining} 件`"
          :description="quota.full
            ? '请等其中任意一件审核出结果（通过或驳回）后再发布新的闲置，名额会立即释放。'
            : '只有「审核中」的商品占名额；在售、已售、已下架都不占用。'"
        />

        <!-- AI 发布助手：只回填表单，不会自动提交。价格建议来自平台真实在售商品的价位分布。 -->
        <div v-if="!isEdit" class="ai-draft">
          <div class="ai-draft-head">
            <span class="ai-draft-title">
              <el-icon><MagicStick /></el-icon>
              AI 帮我填
            </span>
            <span class="ai-draft-hint">随便写一句，例如「九成新 iPad Air4 64G 含原装充电器 想卖1800」</span>
          </div>
          <el-input
            v-model="aiText"
            type="textarea"
            :rows="2"
            maxlength="1000"
            show-word-limit
            placeholder="用大白话描述你要卖的东西，AI 会整理成规范的标题、描述、分类、成色和标签"
          />
          <div class="ai-draft-actions">
            <el-button
              type="primary"
              plain
              :loading="aiLoading"
              :disabled="!aiText.trim()"
              @click="askAi"
            >
              {{ aiLoading ? '正在整理…' : '生成建议' }}
            </el-button>
            <span v-if="aiDegradeNote" class="ai-draft-note">{{ aiDegradeNote }}</span>
          </div>

          <!-- 价格建议：明确标注来源是平台真实在售数据，不是模型猜的 -->
          <div v-if="aiPriceSuggestion" class="ai-price">
            <span class="ai-price-label">平台同类在售价位</span>
            <span class="ai-price-values">
              ¥{{ aiPriceSuggestion.low }} / <b>¥{{ aiPriceSuggestion.mid }}</b> / ¥{{ aiPriceSuggestion.high }}
            </span>
            <span class="ai-price-basis">{{ aiPriceSuggestion.basis }}，共 {{ aiPriceSuggestion.sampleCount }} 件样本</span>
            <el-button link type="primary" size="small" @click="applyAiPrice">用中位价填售价</el-button>
          </div>

          <el-alert
            v-if="aiRiskFlags.length"
            class="ai-risk"
            type="warning"
            :closable="false"
            show-icon
            :title="`检测到可能违规的内容：${aiRiskFlags.join('、')}`"
            description="平台对这类内容审核较严，建议修改后再发布，否则很可能被驳回。"
          />
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <el-form-item label="宝贝图片" prop="images">
            <ImageUploader v-model="form.images" />
          </el-form-item>

          <el-form-item label="标题" prop="title">
            <el-input
              v-model="form.title"
              maxlength="60"
              show-word-limit
              placeholder="品牌型号 + 成色，例如：iPad Air 5 64G 深空灰 带笔"
            />
          </el-form-item>

          <el-form-item label="描述" prop="description">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="5"
              maxlength="2000"
              show-word-limit
              placeholder="购买时间、使用情况、有无磕碰、附赠配件…写得越清楚越好卖"
            />
          </el-form-item>

          <div class="grid-2">
            <el-form-item label="售价" prop="price">
              <el-input-number
                v-model="form.price"
                :min="0.01"
                :max="999999"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>

            <el-form-item label="原价（选填，用于展示折扣）">
              <el-input-number
                v-model="form.originalPrice"
                :min="0"
                :max="999999"
                :precision="2"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </div>

          <div class="grid-2">
            <el-form-item label="分类" prop="categoryId">
              <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%">
                <el-option
                  v-for="category in categories"
                  :key="category.categoryId"
                  :label="`${category.icon || ''} ${category.name}`"
                  :value="category.categoryId"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="成色" prop="conditionLevel">
              <el-select v-model="form.conditionLevel" style="width: 100%">
                <el-option v-for="level in conditions" :key="level" :label="level" :value="level" />
              </el-select>
            </el-form-item>
          </div>

          <div class="grid-2">
            <el-form-item label="交易方式">
              <el-radio-group v-model="form.tradeType">
                <el-radio-button :value="0">都支持</el-radio-button>
                <el-radio-button :value="1">仅当面</el-radio-button>
                <el-radio-button :value="2">仅邮寄</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-form-item label="所在校区">
              <el-input v-model="form.campus" placeholder="如：东校区" maxlength="32" />
            </el-form-item>
          </div>

          <el-form-item label="交易地点（选填）">
            <el-input v-model="form.location" placeholder="如：三食堂门口 / 图书馆一楼" maxlength="60" />
          </el-form-item>

          <div class="submit-row">
            <el-button @click="$router.back()">取消</el-button>
            <el-button type="primary" class="bobo-btn-primary" :loading="submitting" @click="submit">
              {{ isEdit ? '保存修改' : '立即发布' }}
            </el-button>
          </div>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MagicStick } from '@element-plus/icons-vue'
import { aiApi, authApi, categoryApi, itemApi } from '@/api'
import { useUserStore } from '@/stores/user'
import ImageUploader from '@/components/ImageUploader.vue'
import { CONDITION_OPTIONS } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isEdit = computed(() => route.name === 'item-edit')
const conditions = CONDITION_OPTIONS
const categories = ref([])
const submitting = ref(false)
const formRef = ref(null)

/**
 * 待审核配额（同一用户最多同时挂 5 件待审核商品）。
 * 上限由后端下发（overview.pendingLimit），**不在前端写死**——
 * 否则以后调限额要改两处，且很容易两边不一致。
 * known=false 表示还没拿到数据，此时不显示提示（避免闪一个错误的数字）。
 */
const quota = reactive({
  known: false,
  pending: 0,
  limit: 5,
  remaining: 5,
  full: false
})

async function loadQuota() {
  if (isEdit.value) {
    return
  }
  try {
    const data = await authApi.overview()
    const limit = Number(data?.pendingLimit ?? 5)
    const pending = Number(data?.pendingCount ?? 0)
    quota.limit = limit
    quota.pending = pending
    quota.remaining = Math.max(0, limit - pending)
    quota.full = pending >= limit
    quota.known = true
  } catch (e) {
    // 配额只是提示，拿不到就不显示，不打扰用户
  }
}

const form = reactive({
  title: '',
  description: '',
  price: null,
  originalPrice: null,
  categoryId: null,
  conditionLevel: '95新',
  tradeType: 0,
  campus: '',
  location: '',
  images: []
})

const rules = {
  title: [{ required: true, message: '请填写标题', trigger: 'blur' }],
  price: [{ required: true, message: '请填写售价', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  images: [
    {
      validator: (rule, value, callback) => {
        if (!value || !value.length) {
          callback(new Error('至少上传一张实物图，买家更信任有图的宝贝'))
          return
        }
        callback()
      },
      trigger: 'change'
    }
  ]
}

onMounted(async () => {
  categories.value = await categoryApi.list().catch(() => [])
  const user = await userStore.fetchUser()
  if (user && !form.campus) {
    form.campus = user.campus || ''
  }
  if (isEdit.value) {
    await loadItem()
  } else {
    // 新发布时才需要配额提示
    loadQuota()
  }
})

async function loadItem() {
  try {
    const detail = await itemApi.detail(route.params.id)
    if (detail.sellerId !== userStore.userId) {
      ElMessage.error('只能编辑自己发布的商品')
      router.replace(`/item/${route.params.id}`)
      return
    }
    form.title = detail.title
    form.description = detail.description || ''
    form.price = Number(detail.price)
    form.originalPrice = detail.originalPrice ? Number(detail.originalPrice) : null
    form.categoryId = detail.categoryId
    form.conditionLevel = detail.conditionLevel || '95新'
    form.tradeType = detail.tradeType ?? 0
    form.campus = detail.campus || ''
    form.location = detail.location || ''
    form.images = detail.images || (detail.cover ? [detail.cover] : [])
  } catch (e) {
    router.replace('/my-items')
  }
}

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    if (isEdit.value) {
      await itemApi.update(route.params.id, { ...form })
      ElMessage.success('已保存，等待管理员重新审核')
      router.push('/my-items')
    } else {
      const res = await itemApi.publish({ ...form })
      // 采纳反馈：埋点失败不能影响发布结果，所以单独 catch
      recordAiFeedback(res.itemId)
      ElMessage.success('发布成功，等待管理员审核')
      router.push(`/item/${res.itemId}`)
    }
  } catch (e) {
    // 提示由 http.js 处理
  } finally {
    submitting.value = false
  }
}

// ------------------------------------------------------------------ AI 发布助手

const aiText = ref('')
const aiLoading = ref(false)
const aiPriceSuggestion = ref(null)
const aiRiskFlags = ref([])
const aiDegradeNote = ref('')
/** 上一次 AI 建议的快照，提交时用来判断用户改没改（= 是否采纳） */
const aiSnapshot = ref(null)

async function askAi() {
  if (!aiText.value.trim()) {
    return
  }
  aiLoading.value = true
  try {
    const draft = await aiApi.publishDraft({
      text: aiText.value.trim(),
      categoryId: form.categoryId || null
    })
    applyDraft(draft)
  } catch (e) {
    // http.js 已提示
  } finally {
    aiLoading.value = false
  }
}

/**
 * 回填规则：**只填空字段，不覆盖用户已经填的内容**。
 * 用户手动写的东西永远优先于 AI 建议——这是"助手"和"自动提交"的区别。
 */
function applyDraft(draft) {
  const filled = []
  if (draft.title && !form.title) {
    form.title = draft.title
    filled.push('标题')
  }
  if (draft.description && !form.description) {
    form.description = draft.description
    filled.push('描述')
  }
  if (draft.categoryId && !form.categoryId) {
    form.categoryId = draft.categoryId
    filled.push('分类')
  }
  if (draft.conditionLevel) {
    form.conditionLevel = draft.conditionLevel
    filled.push('成色')
  }

  aiPriceSuggestion.value = draft.priceSuggestion || null
  aiRiskFlags.value = draft.riskFlags || []
  aiDegradeNote.value = draft.source === 'rule'
    ? `当前为规则模式：${draft.degradeNote || '未启用模型'}`
    : ''
  aiSnapshot.value = {
    title: draft.title || '',
    description: draft.description || '',
    categoryId: draft.categoryId || null,
    conditionLevel: draft.conditionLevel || '',
    logId: draft.logId || null
  }

  if (filled.length) {
    ElMessage.success(`已填入：${filled.join('、')}，请检查后发布`)
  } else {
    ElMessage.info('建议已生成，但你已填写的内容不会被覆盖')
  }
}

function applyAiPrice() {
  if (aiPriceSuggestion.value?.mid) {
    form.price = Number(aiPriceSuggestion.value.mid)
    ElMessage.success('已按平台中位价填入售价')
  }
}

/**
 * 采纳反馈：把「模型建议」与「用户最终提交」一起上报。
 * 这是采纳率指标的来源——没有它，"采纳率"只能靠估。
 */
function recordAiFeedback(itemId) {
  const snapshot = aiSnapshot.value
  if (!snapshot) {
    return
  }
  const accepted = form.title === snapshot.title && form.description === snapshot.description
  aiApi
    .feedback({
      suggestionType: 'publish_draft',
      accepted,
      suggestedValue: snapshot.title,
      finalValue: form.title,
      targetId: itemId || null,
      logId: snapshot.logId
    })
    .catch(() => {})
}
</script>

<style scoped>
.narrow {
  max-width: 760px;
}

.page-title {
  margin: 0 0 4px;
  font-size: 20px;
}

.sub {
  margin: 0 0 20px;
  font-size: 13px;
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

/* ---------------------------------------------------------------- AI 发布助手 */
.ai-draft {
  margin-bottom: 20px;
  padding: 14px;
  border: 1px dashed var(--el-color-primary-light-5);
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
}

.ai-draft-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
}

.ai-draft-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-color-primary);
}

.ai-draft-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-draft-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}

.ai-draft-note {
  font-size: 12px;
  color: var(--el-color-warning);
}

.ai-price {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
}

.ai-price-label {
  color: var(--el-text-color-secondary);
}

.ai-price-values {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--el-color-danger);
}

.ai-price-basis {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-risk {
  margin-top: 12px;
}

.quota-tip {
  margin-bottom: 16px;
}

.submit-row {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 8px;
}

@media (max-width: 640px) {
  .grid-2 {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
