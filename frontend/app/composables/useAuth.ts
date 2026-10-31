import type { LoginResult, UserProfile } from '~/types/api'
import { clearAuthToken, getAuthToken, setAuthToken } from '~/utils/auth'

let initStarted = false

/**
 * 登录态与当前用户。token 持久化在 localStorage，SSR 阶段不读取浏览器存储。
 */
export function useAuth() {
  const api = useApi()
  const token = useState<string | null>('auth.token', () => null)
  const profile = useState<UserProfile | null>('auth.profile', () => null)
  const ready = useState<boolean>('auth.ready', () => false)

  const isLoggedIn = computed(() => Boolean(token.value))

  async function init(force = false): Promise<void> {
    if (!import.meta.client) return
    if (initStarted && !force) return
    initStarted = true

    token.value = getAuthToken()
    if (!token.value) {
      profile.value = null
      ready.value = true
      return
    }

    try {
      profile.value = await api.get<UserProfile>('/api/users/me', undefined, {
        redirectOnAuthError: false
      })
    } catch (error) {
      const status = (error as { status?: number }).status
      if (status === 401 || status === 403) {
        token.value = null
        profile.value = null
        clearAuthToken()
      }
    } finally {
      ready.value = true
    }
  }

  function setSession(result: LoginResult): void {
    setAuthToken(result.token, result.expiresAt)
    token.value = result.token
    profile.value = result.user
    ready.value = true
    initStarted = true
  }

  async function refreshProfile(): Promise<UserProfile | null> {
    try {
      profile.value = await api.get<UserProfile>('/api/users/me', undefined, {
        redirectOnAuthError: false
      })
    } catch {
      // 刷新失败保留旧资料，由调用方展示自己的错误提示。
    }
    return profile.value
  }

  function clearSession(): void {
    clearAuthToken()
    token.value = null
    profile.value = null
    ready.value = true
  }

  async function logout(redirectTo = '/'): Promise<void> {
    try {
      await api.post('/api/auth/logout', undefined, { redirectOnAuthError: false })
    } catch {
      // 后端会话已失效时仍然清理本地凭证。
    } finally {
      clearSession()
    }
    await navigateTo(redirectTo)
  }

  return {
    token,
    profile,
    ready,
    isLoggedIn,
    init,
    setSession,
    refreshProfile,
    clearSession,
    logout
  }
}

/**
 * 受保护页面：客户端初始化登录态，未登录时跳转登录并带上来源地址。
 */
export function useRequireAuth() {
  const auth = useAuth()
  const route = useRoute()

  onMounted(async () => {
    await auth.init()
    if (!auth.isLoggedIn.value) {
      await navigateTo({
        path: '/login',
        query: { redirect: route.fullPath }
      })
    }
  })

  return auth
}
