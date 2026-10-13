<template>
  <!--
    商品卡片：首页瀑布流、搜索结果、收藏、他人主页共用。
    hover 时轻微上浮 + 阴影加深，是闲鱼列表的典型交互。
  -->
  <div class="item-card" @click="go">
    <div class="cover-wrap">
      <img :src="coverOf(item)" :alt="item.title" loading="lazy" />
      <span v-if="item.status === 2" class="sold-mask">已售出</span>
      <span v-else-if="item.status === 0" class="status-mask">审核中</span>
      <span v-else-if="item.status === 3" class="status-mask">已下架</span>
      <span v-else-if="item.status === 4" class="status-mask reject">未通过</span>
    </div>

    <div class="info">
      <p class="title ellipsis-2">{{ item.title }}</p>

      <div class="price-row">
        <span class="price">
          <span class="symbol">¥</span>{{ priceParts(item.price).int
          }}<span class="decimal">.{{ priceParts(item.price).decimal }}</span>
        </span>
        <span v-if="showOriginal && item.originalPrice" class="original">¥{{ money(item.originalPrice) }}</span>
      </div>

      <div class="meta">
        <span class="bobo-tag">{{ item.conditionLevel || '9成新' }}</span>
        <span v-if="item.campus" class="campus ellipsis">{{ item.campus }}</span>
        <span class="spacer"></span>
        <span class="stat" :title="`${item.wantCount || 0} 人想要`">
          <el-icon><Star /></el-icon>{{ item.wantCount || 0 }}
        </span>
      </div>

      <div v-if="showSeller" class="seller" @click.stop="goSeller">
        <img class="avatar" :src="avatarOf({ nickname: item.sellerNickname, avatar: item.sellerAvatar })" alt="" />
        <span class="ellipsis">{{ item.sellerNickname || '同学' }}</span>
        <span v-if="item.sellerVerifyStatus === 2" class="bobo-tag success">已认证</span>
      </div>
    </div>

    <div v-if="showActions" class="actions" @click.stop>
      <slot name="actions" :item="item"></slot>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { avatarOf, coverOf, money, priceParts } from '@/utils/format'

const props = defineProps({
  item: { type: Object, required: true },
  /** 是否展示卖家信息 */
  showSeller: { type: Boolean, default: false },
  /** 是否展示原价划线 */
  showOriginal: { type: Boolean, default: false },
  /** 是否展示底部操作区（用 slot 传按钮） */
  showActions: { type: Boolean, default: false }
})

const router = useRouter()

function go() {
  router.push(`/item/${props.item.itemId}`)
}

function goSeller() {
  if (props.item.sellerId) {
    router.push(`/user/${props.item.sellerId}`)
  }
}
</script>

<style scoped>
.item-card {
  background: #fff;
  border-radius: var(--bobo-radius);
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
  box-shadow: var(--bobo-shadow);
  display: flex;
  flex-direction: column;
  height: 100%;
}

.item-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--bobo-shadow-hover);
}

.cover-wrap {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  background: var(--bobo-bg);
  overflow: hidden;
}

.cover-wrap img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.sold-mask,
.status-mask {
  position: absolute;
  left: 0;
  top: 0;
  padding: 3px 10px;
  font-size: 12px;
  color: #fff;
  background: rgba(31, 33, 41, 0.72);
  border-bottom-right-radius: 10px;
}

.status-mask {
  background: rgba(255, 159, 10, 0.92);
}

.status-mask.reject {
  background: rgba(255, 77, 79, 0.92);
}

.info {
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.title {
  margin: 0;
  font-size: 14px;
  line-height: 20px;
  color: var(--bobo-text);
  min-height: 40px;
}

.price-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.price {
  color: var(--bobo-accent);
  font-weight: 700;
  font-size: 20px;
  line-height: 1;
}

.price .symbol {
  font-size: 13px;
  margin-right: 1px;
}

.price .decimal {
  font-size: 13px;
}

.original {
  color: var(--bobo-text-placeholder);
  font-size: 12px;
  text-decoration: line-through;
}

.meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--bobo-text-secondary);
}

.meta .campus {
  max-width: 80px;
}

.spacer {
  flex: 1;
}

.stat {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.seller {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--bobo-text-regular);
  padding-top: 6px;
  border-top: 1px dashed var(--bobo-border);
}

.seller .avatar {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  object-fit: cover;
}

.actions {
  padding: 8px 10px;
  border-top: 1px solid var(--bobo-border);
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
