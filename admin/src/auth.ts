import { reactive } from 'vue'
import { authApi } from './api'
import type { AdminProfile } from './api/types'
import { clearToken, getToken, setToken } from './api/http'

export const authState = reactive({
  token: getToken(),
  profile: null as AdminProfile | null,
  loading: false
})

export async function login(account: string, password: string): Promise<void> {
  const result = await authApi.login(account, password)
  setToken(result.token)
  authState.token = result.token
  authState.profile = {
    id: '',
    account,
    role: result.role,
    status: 'ACTIVE'
  }
  await loadProfile()
}

export async function loadProfile(): Promise<AdminProfile | null> {
  if (!authState.token) return null
  if (authState.profile?.id) return authState.profile
  authState.loading = true
  try {
    const profile = await authApi.me()
    authState.profile = profile
    return profile
  } finally {
    authState.loading = false
  }
}

export function logout(): void {
  clearToken()
  authState.token = ''
  authState.profile = null
}

export function isSuperAdmin(): boolean {
  return authState.profile?.role === 'SUPER_ADMIN'
}
