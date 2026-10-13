<template>
  <span class="empty">
    <img :src="image" alt="" />
    <span class="text">{{ text }}</span>
    <slot></slot>
  </span>
</template>

<script setup>
defineProps({
  text: { type: String, default: '这里还什么都没有' },
  image: { type: String, default: '/img/img-default-cover.svg' }
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
