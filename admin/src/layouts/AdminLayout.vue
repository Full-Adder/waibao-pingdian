<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  ChatLineSquare,
  DocumentChecked,
  Flag,
  Fold,
  OfficeBuilding,
  Odometer,
  Service,
  Stamp,
  UploadFilled,
  User,
  Tickets
} from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { authState, isSuperAdmin, logout } from '../auth'
import { ROLE_LABEL } from '../utils/labels'

const route = useRoute()
const router = useRouter()

const menus = computed(() => [
  { path: '/dashboard', title: '数据看板', icon: Odometer },
  { path: '/reviews', title: '审核队列', icon: DocumentChecked },
  { path: '/verifications', title: '核验审核', icon: Stamp },
  { path: '/complaints', title: '投诉工单', icon: Flag },
  { path: '/responses', title: '企业回应', icon: ChatLineSquare },
  { path: '/reports', title: '举报处置', icon: Tickets },
  { path: '/companies', title: '公司管理', icon: OfficeBuilding },
  { path: '/imports', title: '导入任务', icon: UploadFilled },
  { path: '/users', title: '用户管理', icon: User },
  ...(isSuperAdmin() ? [{ path: '/audit', title: '审计日志', icon: Fold }] : []),
  { path: '/feedback', title: '帮助反馈', icon: Service }
])

const pageTitle = computed(() => String(route.meta.title ?? ''))
const roleLabel = computed(() => ROLE_LABEL[authState.profile?.role ?? ''] ?? '—')

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出管理后台？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside width="212px" class="aside">
      <div class="brand">
        <div class="brand-title">外包点评</div>
        <div class="brand-sub">ADMIN CONSOLE</div>
      </div>
      <el-menu :default-active="route.path" router class="menu">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar" height="54px">
        <div class="topbar-title">{{ pageTitle }}</div>
        <div class="topbar-right">
          <el-tag size="small" effect="plain" :type="isSuperAdmin() ? 'success' : 'info'">
            {{ roleLabel }}
          </el-tag>
          <el-dropdown trigger="click" @command="handleLogout">
            <span class="account">
              {{ authState.profile?.account ?? '管理员' }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
}

.aside {
  background: #fff;
  border-right: 1px solid var(--rule);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.brand {
  padding: 20px 20px 14px;
  border-bottom: 1px solid var(--rule);
}

.brand-title {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.brand-sub {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--muted);
  letter-spacing: 0.12em;
  margin-top: 2px;
}

.menu {
  border-right: none;
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.menu .el-menu-item {
  height: 40px;
  line-height: 40px;
  font-size: 13px;
}

.topbar {
  background: #fff;
  border-bottom: 1px solid var(--rule);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
}

.topbar-title {
  font-size: 14px;
  font-weight: 600;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.account {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  font-size: 12.5px;
  color: var(--ink);
  outline: none;
}

.main {
  padding: 0;
  background: var(--page-bg);
  overflow-y: auto;
}
</style>
