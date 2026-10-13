<template>
  <div class="uploader">
    <!-- 已上传的图片：支持拖动排序（第一张即封面） -->
    <div v-for="(url, index) in modelValue" :key="url + index" class="thumb">
      <img :src="url" alt="" />
      <span v-if="index === 0" class="cover-badge">封面</span>
      <div class="thumb-actions">
        <el-icon v-if="index > 0" title="设为封面" @click="makeCover(index)"><Star /></el-icon>
        <el-icon title="删除" @click="remove(index)"><Delete /></el-icon>
      </div>
    </div>

    <!-- 上传按钮 -->
    <div v-if="modelValue.length < max" class="add" :class="{ loading }" @click="pick">
      <el-icon v-if="!loading" class="plus"><Plus /></el-icon>
      <span>{{ loading ? '上传中' : `${modelValue.length}/${max}` }}</span>
    </div>

    <input ref="inputRef" class="hidden-input" type="file" :accept="accept" multiple @change="onChange" />

    <p class="tip">{{ tip }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadApi } from '@/api'

/**
 * 图片上传组件。
 *
 * 为什么不用 el-upload：需要「第一张自动作封面 + 可调整顺序」这种业务语义，
 * 用原生 input 自己控制列表比在 el-upload 上覆盖 file-list 逻辑更直接。
 *
 * 上传是「先传图拿到 URL，再提交表单」的两步式：
 * 这样发布失败时图片已经落盘（会在运维清理任务里回收），
 * 好处是用户不用因为表单校验失败而重新选图。
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  max: { type: Number, default: 9 },
  accept: { type: String, default: 'image/jpeg,image/png,image/webp,image/gif' },
  tip: { type: String, default: '第一张为封面，最多 9 张，单张不超过 10MB' }
})

const emit = defineEmits(['update:modelValue'])

const inputRef = ref(null)
const loading = ref(false)

function pick() {
  if (loading.value) {
    return
  }
  inputRef.value?.click()
}

async function onChange(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length) {
    return
  }
  const remain = props.max - props.modelValue.length
  if (remain <= 0) {
    ElMessage.warning(`最多上传 ${props.max} 张图片`)
    return
  }
  const selected = files.slice(0, remain)
  if (files.length > remain) {
    ElMessage.warning(`最多上传 ${props.max} 张，已自动截取前 ${remain} 张`)
  }

  loading.value = true
  try {
    const result = await uploadApi.images(selected)
    const urls = (result || []).map((item) => item.url).filter(Boolean)
    emit('update:modelValue', [...props.modelValue, ...urls])
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    loading.value = false
  }
}

function remove(index) {
  const next = [...props.modelValue]
  next.splice(index, 1)
  emit('update:modelValue', next)
}

/** 把第 index 张挪到第一位，即设为封面 */
function makeCover(index) {
  const next = [...props.modelValue]
  const [target] = next.splice(index, 1)
  next.unshift(target)
  emit('update:modelValue', next)
}
</script>

<style scoped>
.uploader {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.thumb {
  position: relative;
  width: 92px;
  height: 92px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--bobo-border);
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-badge {
  position: absolute;
  left: 0;
  bottom: 0;
  padding: 2px 6px;
  font-size: 11px;
  color: #1f2129;
  background: var(--bobo-primary);
  border-top-right-radius: 8px;
}

.thumb-actions {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: rgba(31, 33, 41, 0.5);
  color: #fff;
  opacity: 0;
  transition: opacity 0.15s ease;
  font-size: 18px;
  cursor: pointer;
}

.thumb:hover .thumb-actions {
  opacity: 1;
}

.add {
  width: 92px;
  height: 92px;
  border: 1px dashed #d7dae0;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: var(--bobo-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease;
}

.add:hover {
  border-color: var(--bobo-primary-deep);
  color: var(--bobo-primary-deep);
}

.add.loading {
  cursor: wait;
}

.add .plus {
  font-size: 20px;
}

.hidden-input {
  display: none;
}

.tip {
  width: 100%;
  margin: 0;
  font-size: 12px;
  color: var(--bobo-text-secondary);
}
</style>
