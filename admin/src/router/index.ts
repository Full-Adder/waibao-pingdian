import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import AdminLayout from '../layouts/AdminLayout.vue'
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'
import ReviewsView from '../views/ReviewsView.vue'
import VerificationsView from '../views/VerificationsView.vue'
import ComplaintsView from '../views/ComplaintsView.vue'
import ResponsesView from '../views/ResponsesView.vue'
import ReportsView from '../views/ReportsView.vue'
import CompaniesView from '../views/CompaniesView.vue'
import ImportsView from '../views/ImportsView.vue'
import UsersView from '../views/UsersView.vue'
import AuditView from '../views/AuditView.vue'
import FeedbackView from '../views/FeedbackView.vue'
import { authState, isSuperAdmin, loadProfile } from '../auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: LoginView,
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: AdminLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: DashboardView, meta: { title: '数据看板' } },
      { path: 'reviews', component: ReviewsView, meta: { title: '审核队列' } },
      { path: 'verifications', component: VerificationsView, meta: { title: '核验审核' } },
      { path: 'complaints', component: ComplaintsView, meta: { title: '投诉工单' } },
      { path: 'responses', component: ResponsesView, meta: { title: '企业回应' } },
      { path: 'reports', component: ReportsView, meta: { title: '举报处置' } },
      { path: 'companies', component: CompaniesView, meta: { title: '公司管理' } },
      { path: 'imports', component: ImportsView, meta: { title: '导入任务' } },
      { path: 'users', component: UsersView, meta: { title: '用户管理' } },
      { path: 'audit', component: AuditView, meta: { title: '审计日志', superOnly: true } },
      { path: 'feedback', component: FeedbackView, meta: { title: '帮助反馈' } }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    if (authState.token && to.path === '/login') {
      return '/dashboard'
    }
    return true
  }

  if (!authState.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  try {
    await loadProfile()
  } catch {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (to.meta.superOnly && !isSuperAdmin()) {
    ElMessage.warning('该页面仅超级管理员可见')
    return '/dashboard'
  }

  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 外包点评管理后台` : '外包点评 · 管理后台'
})

export default router
