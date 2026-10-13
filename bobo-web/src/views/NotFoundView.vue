<template>
  <div class="not-found">
    <img :src="DEFAULT_COVER" alt="" />
    <h1>404</h1>
    <p>这个页面大概已经被同学买走了</p>
    <el-button type="primary" class="bobo-btn-primary" @click="$router.push('/')">回首页逛逛</el-button>
  </div>
</template>

<script setup>
import { DEFAULT_COVER } from '@/utils/format'
</script>

<style scoped>
.not-found {
  min-height: 70vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  text-align: center;
  padding: 24px;
}

.not-found img {
  width: 160px;
  height: 160px;
  border-radius: 18px;
}

.not-found h1 {
  margin: 0;
  font-size: 40px;
  color: var(--bobo-text);
}

.not-found p {
  margin: 0 0 12px;
  color: var(--bobo-text-secondary);
}
</style>
