<script setup lang="ts">
import type { NotificationItem, NotificationPage } from '~/types/api'

useSeoMeta({ title: '通知中心 · 外包点评', robots: 'noindex' })

const api = useApi()
const auth = useRequireAuth()

const loading = ref(false)
const loadError = ref('')
const pageData = ref<NotificationPage | null>(null)
const unreadOnly = ref(false)
const page = ref(1)
const actionError = ref('')
const marking = ref(false)

const items = computed(() => pageData.value?.items ?? [])
const total = computed(() => pageData.value?.total ?? 0)
const unreadCount = computed(() => pageData.value?.unreadCount ?? 0)
const totalPages = computed(() =>
  Math.max(1, Math.ceil(total.value / (pageData.value?.size || 50)))
)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    pageData.value = await api.get<NotificationPage>('/api/users/me/notifications', {
      unreadOnly: unreadOnly.value,
      page: page.value,
      size: 50
    })
  } catch (error) {
    loadError.value = (error as { message?: string }).message || '通知加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await auth.init()
  if (!auth.isLoggedIn.value) return
  await load()
})

async function toggleUnread() {
  unreadOnly.value = !unreadOnly.value
  page.value = 1
  await load()
}

async function markRead(item: NotificationItem) {
  if (item.readAt) return
  actionError.value = ''
  try {
    await api.post(`/api/users/me/notifications/${encodeURIComponent(item.id)}/read`)
    await load()
  } catch (error) {
    actionError.value = (error as { message?: string }).message || '标记已读失败'
  }
}

async function markAllRead() {
  actionError.value = ''
  marking.value = true
  try {
    await api.post('/api/users/me/notifications/read-all')
    await load()
  } catch (error) {
    actionError.value = (error as { message?: string }).message || '全部已读操作失败'
  } finally {
    marking.value = false
  }
}

async function goPage(target: number) {
  if (target < 1 || target > totalPages.value) return
  page.value = target
  await load()
}

function notificationLink(item: NotificationItem): string {
  if (item.refType === 'VERIFICATION') return '/me/verification'
  if (item.refType === 'COMPLAINT' || item.refType === 'REVIEW') return '/me/reviews'
  return '/me/reviews'
}

function typeIcon(type: string): string {
  return NOTIFICATION_TYPE_ICONS[type] || '通'
}
</script>

<template>
  <main class="wrap page">
    <div class="mono">NOTIFICATIONS · 通知中心</div>
    <h1>通知中心</h1>
    <p class="meta">
      争议开始、举证提醒、裁定结果、佐证驳回与申诉结果属于时限类必达通知，不提供退订入口。
    </p>

    <div v-if="!auth.ready.value" class="empty-state">
      <h2>正在校验登录状态</h2>
      <p>请稍候，正在读取本机登录凭证。</p>
    </div>

    <div v-else-if="!auth.isLoggedIn.value" class="empty-state">
      <h2>登录后查看通知</h2>
      <p>时限类通知只对你本人可见，请登录后查看。</p>
      <NuxtLink class="button primary" to="/login?redirect=/me/notifications">去登录</NuxtLink>
    </div>

    <template v-else>
      <div class="notification-toolbar">
        <div class="button-row">
          <button
            type="button"
            class="button"
            :class="{ primary: unreadOnly }"
            :aria-pressed="unreadOnly"
            @click="toggleUnread"
          >
            {{ unreadOnly ? '只看未读' : '全部通知' }}
          </button>
          <button
            type="button"
            class="button ghost"
            :disabled="marking || unreadCount === 0"
            @click="markAllRead"
          >
            {{ marking ? '处理中…' : '全部已读' }}
          </button>
        </div>
        <span class="mono">未读 {{ unreadCount }} 条 · 共 {{ total }} 条</span>
      </div>

      <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>

      <div v-if="loading" class="skeleton-list">
        <div class="skeleton skeleton-card" />
        <div class="skeleton skeleton-card" />
      </div>

      <div v-else-if="loadError" class="empty-state">
        <h2>通知加载失败</h2>
        <p>{{ loadError }}</p>
        <button type="button" class="button" @click="load">重试</button>
      </div>

      <div v-else-if="items.length === 0" class="empty-state">
        <h2>{{ unreadOnly ? '没有未读通知' : '暂无通知' }}</h2>
        <p>发生争议、举证提醒、裁定、核验驳回或申诉结果时，通知会出现在这里。</p>
        <NuxtLink class="button ghost" to="/me/reviews">返回我的点评</NuxtLink>
      </div>

      <ul v-else class="notification-list">
        <li
          v-for="item in items"
          :key="item.id"
          class="notification-item"
          :class="{ unread: !item.readAt }"
        >
          <span class="notification-icon" aria-hidden="true">{{ typeIcon(item.type) }}</span>
          <div class="notification-body">
            <div class="notification-head">
              <span class="notification-type">{{ notificationTypeLabel(item.type) }}</span>
              <span v-if="!item.readAt" class="status-tag">未读</span>
              <time class="review-date" :datetime="item.createdAt || undefined">
                {{ formatDateTime(item.createdAt) }}
              </time>
            </div>
            <h2 class="notification-title">{{ item.title }}</h2>
            <p class="notification-text">{{ item.body }}</p>
            <div class="notification-actions">
              <NuxtLink class="button ghost" :to="notificationLink(item)">查看详情</NuxtLink>
              <button
                v-if="!item.readAt"
                type="button"
                class="button"
                @click="markRead(item)"
              >
                标记已读
              </button>
            </div>
          </div>
        </li>
      </ul>

      <nav v-if="totalPages > 1" class="pagination" aria-label="通知分页">
        <button type="button" class="button ghost" :disabled="page <= 1" @click="goPage(page - 1)">
          上一页
        </button>
        <span class="mono">第 {{ page }} / {{ totalPages }} 页</span>
        <button type="button" class="button ghost" :disabled="page >= totalPages" @click="goPage(page + 1)">
          下一页
        </button>
      </nav>
    </template>
  </main>
</template>
