<template>
  <span class="empty">
    <img :src="image" alt="" />
    <span class="text">{{ text }}</span>
    <slot></slot>
  </span>
</template>

<script setup>
import { DEFAULT_COVER } from '@/utils/format'

defineProps({
  text: { type: String, default: '这里还什么都没有' },
  // 用工厂函数返回默认值：'@/utils/format' 的 DEFAULT_COVER 是模块级常量，
  // 直接写 default: DEFAULT_COVER 也合法，但工厂函数能保持与其它 prop 一致的写法，
  // 也避免将来把默认值改成依赖运行时的值时踩坑（对象/数组默认值本来就该用工厂函数）
  image: { type: String, default: () => DEFAULT_COVER }
})
</script>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 56px 16px;
  color: var(--bobo-text-secondary);
}

.empty img {
  width: 120px;
  height: 120px;
  object-fit: contain;
  opacity: 0.85;
  border-radius: 12px;
}

.text {
  font-size: 14px;
}
</style>
