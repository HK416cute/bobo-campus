<template>
  <div class="bobo-page">
    <div class="bobo-container narrow">
      <!-- 资料卡 -->
      <div class="bobo-card profile">
        <img class="avatar" :src="avatarOf(userStore.user)" alt="" />
        <div class="info">
          <div class="line1">
            <h2>{{ userStore.nickname }}</h2>
            <span v-if="user?.verifyStatus === 2" class="bobo-tag success">学生已认证</span>
            <span v-else-if="user?.verifyStatus === 1" class="bobo-tag">认证审核中</span>
            <span v-else-if="user?.verifyStatus === 3" class="bobo-tag danger">认证被驳回</span>
          </div>
          <p class="bobo-muted">
            {{ user?.campus || '未设置校区' }}
            <span v-if="user?.college"> · {{ user?.college }}</span>
            <span v-if="user?.grade"> · {{ user?.grade }} 级</span>
          </p>
          <p class="bio">{{ user?.bio || '这个人很懒，什么都没写～' }}</p>
        </div>
        <div class="credit">
          <span class="score">{{ user?.creditScore ?? 100 }}</span>
          <span class="label">信用分</span>
        </div>
        <el-button class="edit" size="small" @click="$router.push('/me/profile')">编辑资料</el-button>
      </div>

      <!-- 数据概览 -->
      <div class="bobo-card stats">
        <div class="stat" @click="$router.push('/my-items')">
          <span class="num">{{ overview.onSaleCount || 0 }}</span>
          <span class="label">在售</span>
        </div>
        <div class="stat" @click="$router.push('/my-items')">
          <span class="num">{{ overview.soldCount || 0 }}</span>
          <span class="label">已售</span>
        </div>
        <div class="stat" @click="$router.push('/favorites')">
          <span class="num">{{ overview.favoriteCount || 0 }}</span>
          <span class="label">想要</span>
        </div>
        <div class="stat" @click="$router.push('/orders')">
          <span class="num">{{ overview.orderBuyCount || 0 }}</span>
          <span class="label">买到的</span>
        </div>
        <div class="stat" @click="$router.push({ path: '/orders', query: { role: 'sell' } })">
          <span class="num">{{ overview.orderSellCount || 0 }}</span>
          <span class="label">卖出的</span>
        </div>
      </div>

      <!-- 功能入口 -->
      <div class="bobo-card">
        <el-cell-group :border="false">
          <el-cell title="我的发布" label="管理在售、已售与审核状态" is-link @click="$router.push('/my-items')">
            <template #icon><el-icon><Goods /></el-icon></template>
          </el-cell>
          <el-cell title="我的想要" label="收藏的宝贝" is-link @click="$router.push('/favorites')">
            <template #icon><el-icon><Star /></el-icon></template>
          </el-cell>
          <el-cell title="我的订单" label="买到的与卖出的订单" is-link @click="$router.push('/orders')">
            <template #icon><el-icon><List /></el-icon></template>
          </el-cell>
          <el-cell title="收货地址" label="邮寄交易使用" is-link @click="$router.push('/me/addresses')">
            <template #icon><el-icon><Location /></el-icon></template>
          </el-cell>
          <el-cell title="消息通知" is-link @click="$router.push('/notices')">
            <template #icon><el-icon><Bell /></el-icon></template>
            <template #value>
              <el-badge :value="userStore.unreadNotice" :hidden="!userStore.unreadNotice" :max="99" />
            </template>
          </el-cell>
          <el-cell
            v-if="user?.verifyStatus !== 2"
            title="学生认证"
            label="认证后主页展示认证标识，更受信任"
            is-link
            @click="verifyVisible = true"
          >
            <template #icon><el-icon><Medal /></el-icon></template>
          </el-cell>
        </el-cell-group>

        <el-divider />

        <div class="bottom-actions">
          <el-button v-if="userStore.isAdmin" @click="$router.push('/admin')">进入管理后台</el-button>
          <el-button @click="passwordVisible = true">修改密码</el-button>
          <el-button type="danger" plain @click="logout">退出登录</el-button>
        </div>
      </div>
    </div>

    <!-- 学生认证 -->
    <el-dialog v-model="verifyVisible" title="学生认证" width="420px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="请填写真实学号与学院，管理员核对后即可通过"
        style="margin-bottom: 14px"
      />
      <el-form :model="verifyForm" label-width="70px">
        <el-form-item label="学号">
          <el-input v-model="verifyForm.studentNo" maxlength="32" placeholder="如：2023010101" />
        </el-form-item>
        <el-form-item label="学院">
          <el-input v-model="verifyForm.college" maxlength="32" placeholder="如：计算机学院" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="verifyVisible = false">取消</el-button>
        <el-button type="primary" class="bobo-btn-primary" :loading="submitting" @click="submitVerify">提交认证</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码 -->
    <el-dialog v-model="passwordVisible" title="修改密码" width="420px">
      <el-form :model="passwordForm" label-width="80px">
        <el-form-item label="原密码">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="passwordForm.newPassword" type="password" show-password placeholder="6-32 位" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" class="bobo-btn-primary" :loading="submitting" @click="changePassword">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bell, Goods, List, Location, Medal, Star } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { avatarOf } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

const overview = ref({})
const verifyVisible = ref(false)
const passwordVisible = ref(false)
const submitting = ref(false)

const verifyForm = reactive({ studentNo: '', college: '' })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const user = computed(() => userStore.user)

onMounted(async () => {
  await userStore.fetchUser(true)
  overview.value = await authApi.overview().catch(() => ({}))
  verifyForm.studentNo = userStore.user?.studentNo || ''
  verifyForm.college = userStore.user?.college || ''
})

async function submitVerify() {
  if (!verifyForm.studentNo.trim() || !verifyForm.college.trim()) {
    ElMessage.warning('请填写学号与学院')
    return
  }
  submitting.value = true
  try {
    await authApi.submitVerify({ ...verifyForm })
    verifyVisible.value = false
    ElMessage.success('已提交认证，请等待管理员审核')
    userStore.fetchUser(true)
  } finally {
    submitting.value = false
  }
}

async function changePassword() {
  if (!passwordForm.oldPassword || !passwordForm.newPassword) {
    ElMessage.warning('请填写完整')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  submitting.value = true
  try {
    await authApi.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    passwordVisible.value = false
    ElMessage.success('密码已修改，请重新登录')
    userStore.logout()
    router.push('/login')
  } finally {
    submitting.value = false
  }
}

async function logout() {
  await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
  userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/')
}
</script>

<style scoped>
.narrow {
  max-width: 720px;
}

.profile {
  display: flex;
  align-items: center;
  gap: 16px;
  position: relative;
}

.profile .avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  object-fit: cover;
}

.profile .info {
  flex: 1;
  min-width: 0;
}

.profile .line1 {
  display: flex;
  align-items: center;
  gap: 8px;
}

.profile h2 {
  margin: 0;
  font-size: 19px;
}

.profile p {
  margin: 4px 0 0;
  font-size: 13px;
}

.profile .bio {
  color: var(--bobo-text-regular);
}

.credit {
  text-align: center;
  padding-right: 6px;
}

.credit .score {
  display: block;
  font-size: 22px;
  font-weight: 800;
  color: var(--bobo-success);
}

.credit .label {
  font-size: 11px;
  color: var(--bobo-text-secondary);
}

.profile .edit {
  position: absolute;
  right: 16px;
  top: 16px;
}

.stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  text-align: center;
  padding: 16px 8px;
}

.stat {
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat .num {
  font-size: 20px;
  font-weight: 700;
}

.stat .label {
  font-size: 12px;
  color: var(--bobo-text-secondary);
}

.bottom-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .profile .edit {
    position: static;
  }

  .stats {
    grid-template-columns: repeat(3, 1fr);
    row-gap: 14px;
  }
}
</style>
