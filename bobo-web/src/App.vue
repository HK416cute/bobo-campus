<template>
  <router-view v-slot="{ Component }">
    <keep-alive :include="keepAliveViews">
      <component :is="Component" />
    </keep-alive>
  </router-view>
</template>

<script setup>
/**
 * 根组件：只负责渲染路由。
 * 列表页用 keep-alive 缓存（返回时保留滚动位置与筛选条件），详情/表单页不缓存。
 */
const keepAliveViews = ['HomeView', 'SearchView', 'FavoritesView']
</script>
