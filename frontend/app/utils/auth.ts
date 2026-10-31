const TOKEN_KEY = 'waibao.auth.token'
const EXPIRES_KEY = 'waibao.auth.expiresAt'

/**
 * 登录凭证保存在 localStorage：前端不接触 HttpOnly Cookie，便于在 Bearer 请求中
 * 显式携带；token 只含 JWT 与过期时间，不保存身份证号等明文。
 */
export function getAuthToken(): string | null {
  if (!import.meta.client) return null
  try {
    const token = localStorage.getItem(TOKEN_KEY)
    const expiresAt = localStorage.getItem(EXPIRES_KEY)
    if (!token) return null
    if (expiresAt) {
      const expires = new Date(expiresAt)
      if (!Number.isNaN(expires.getTime()) && Date.now() >= expires.getTime()) {
        clearAuthToken()
        return null
      }
    }
    return token
  } catch {
    return null
  }
}

export function setAuthToken(token: string, expiresAt: string | null | undefined): void {
  if (!import.meta.client) return
  try {
    localStorage.setItem(TOKEN_KEY, token)
    if (expiresAt) {
      localStorage.setItem(EXPIRES_KEY, expiresAt)
    } else {
      localStorage.removeItem(EXPIRES_KEY)
    }
  } catch {
    // 隐私模式下无法持久化，本次会话仍可通过内存中的 token 使用。
  }
}

export function clearAuthToken(): void {
  if (!import.meta.client) return
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(EXPIRES_KEY)
  } catch {
    // 忽略存储不可用的场景。
  }
}
