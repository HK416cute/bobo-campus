<template>
  <!-- 骨架屏：列表首屏加载时占位，比转圈更不容易让页面跳动 -->
  <div class="grid">
    <div v-for="n in count" :key="n" class="skeleton">
      <div class="block cover"></div>
      <div class="block line"></div>
      <div class="block line short"></div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  count: { type: Number, default: 8 }
})
</script>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 14px;
}

.skeleton {
  background: #fff;
  border-radius: var(--bobo-radius);
  padding: 0 0 12px;
  overflow: hidden;
}

.block {
  background: linear-gradient(90deg, #f0f2f5 25%, #e6e9ee 37%, #f0f2f5 63%);
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
}

.cover {
  width: 100%;
  aspect-ratio: 1 / 1;
}

.line {
  height: 12px;
  margin: 10px 10px 0;
  border-radius: 6px;
}

.line.short {
  width: 50%;
}

@keyframes shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}
</style>
