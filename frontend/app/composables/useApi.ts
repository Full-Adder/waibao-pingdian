import type { ApiEnvelope } from '~/types/api'
import { clearAuthToken, getAuthToken } from '~/utils/auth'

export class ApiError extends Error {
  status: number
  code: number
  details: unknown

  constructor(message: string, status: number, code: number, details?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

interface RequestOptions extends Record<string, unknown> {
  redirectOnAuthError?: boolean
}

const STATUS_MESSAGES: Record<number, string> = {
  400: '请求参数有误，请检查后再提交',
  401: '登录已失效，请重新登录',
  403: '请先登录后再操作',
  404: '请求的内容不存在',
  409: '数据状态冲突，请刷新后重试',
  429: '操作过于频繁，请稍后再试',
  500: '服务暂时不可用，请稍后再试',
  502: '服务暂时不可用，请稍后再试'
}

function currentLocation(): string {
  if (!import.meta.client) return ''
  return `${window.location.pathname}${window.location.search}`
}

/**
 * 统一读取后端 { code, message, data, traceId } 响应体。
 * 自动附加 Bearer token；401 或未携带 token 的 403 会清理本地凭证并跳转登录。
 */
export function useApi() {
  const config = useRuntimeConfig()
  const base = String(config.public.apiBase || '').replace(/\/+$/, '')

  function headers(): Record<string, string> {
    const token = getAuthToken()
    return token ? { Authorization: `Bearer ${token}` } : {}
  }

  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const { redirectOnAuthError = true, ...fetchOptions } = options
    const existingHeaders = (fetchOptions.headers as Record<string, string> | undefined) ?? {}

    try {
      const response = await $fetch<ApiEnvelope<T>>(`${base}${path}`, {
        ...fetchOptions,
        headers: { ...headers(), ...existingHeaders }
      })
      if (!response || response.code !== 0) {
        throw new ApiError(response?.message || '接口返回异常', 502, response?.code ?? 502, response?.data)
      }
      return response.data
    } catch (error) {
      if (error instanceof ApiError) throw error

      const fetchError = error as {
        status?: number
        statusCode?: number
        data?: { code?: number; message?: string; data?: unknown }
        message?: string
      }
      const status = Number(fetchError.status ?? fetchError.statusCode ?? 0)
      const body = fetchError.data
      const backendMessage = typeof body?.message === 'string' ? body.message : ''
      const message = backendMessage || STATUS_MESSAGES[status] || '网络异常，请稍后再试'

      const token = getAuthToken()
      const unauthenticated = status === 401 || (status === 403 && !token)
      if (unauthenticated) {
        clearAuthToken()
        if (import.meta.client && redirectOnAuthError) {
          const redirect = currentLocation()
          const reason = status === 401 ? 'expired' : 'login'
          navigateTo({
            path: '/login',
            query: {
              ...(redirect && redirect !== '/login' ? { redirect } : {}),
              reason
            }
          }).catch(() => {
            // 跳转失败时保留原始错误提示。
          })
        }
      }

      throw new ApiError(
        message,
        status || 502,
        Number(body?.code ?? status ?? 502),
        body?.data
      )
    }
  }

  function get<T>(path: string, query?: Record<string, unknown>, options?: RequestOptions): Promise<T> {
    return request<T>(path, { method: 'GET', query, ...options })
  }

  function post<T>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
    return request<T>(path, { method: 'POST', body, redirectOnAuthError: true, ...options })
  }

  function patch<T>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
    return request<T>(path, { method: 'PATCH', body, redirectOnAuthError: true, ...options })
  }

  function upload<T>(path: string, body: FormData, options: RequestOptions = {}): Promise<T> {
    return request<T>(path, { method: 'POST', body, redirectOnAuthError: true, ...options })
  }

  return { base, get, post, patch, upload }
}
