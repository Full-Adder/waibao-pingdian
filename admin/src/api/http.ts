import { ElMessage } from 'element-plus'

const API_BASE = (import.meta.env.VITE_API_BASE ?? 'http://localhost:8080').replace(/\/$/, '')
export const TOKEN_KEY = 'waibao_admin_token'

export class ApiError extends Error {
  status: number
  code: number
  traceId?: string

  constructor(message: string, status: number, code = -1, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.traceId = traceId
  }
}

interface Envelope<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

function queryString(params?: Record<string, unknown>): string {
  if (!params) return ''
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    search.append(key, String(value))
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

async function parseBody(response: Response): Promise<Envelope<unknown> | null> {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text) as Envelope<unknown>
  } catch {
    return null
  }
}

function redirectToLogin(): void {
  clearToken()
  if (window.location.pathname === '/login') return
  const redirect = window.location.pathname + window.location.search
  window.location.assign(`/login?redirect=${encodeURIComponent(redirect)}`)
}

export interface RequestOptions {
  query?: Record<string, unknown>
  body?: unknown
  formData?: FormData
  silent?: boolean
}

export async function request<T>(
  method: string,
  path: string,
  options: RequestOptions = {}
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  let body: BodyInit | undefined
  if (options.formData) {
    body = options.formData
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}${queryString(options.query)}`, {
      method,
      headers,
      body
    })
  } catch {
    const message = '无法连接后端服务，请确认服务已启动'
    if (!options.silent) ElMessage.error(message)
    throw new ApiError(message, 0)
  }

  const envelope = await parseBody(response)

  if (response.status === 401) {
    const message = envelope?.message || '登录已失效，请重新登录'
    if (!options.silent) ElMessage.error(message)
    redirectToLogin()
    throw new ApiError(message, 401, envelope?.code)
  }

  if (response.status === 403) {
    const message = envelope?.message || '无权限执行该操作'
    if (!options.silent) ElMessage.error(`无权限：${message}`)
    throw new ApiError(message, 403, envelope?.code, envelope?.traceId)
  }

  if (!response.ok) {
    const message = envelope?.message || `请求失败（HTTP ${response.status}）`
    if (!options.silent) ElMessage.error(message)
    throw new ApiError(message, response.status, envelope?.code, envelope?.traceId)
  }

  if (!envelope) {
    const message = '服务响应格式不正确'
    if (!options.silent) ElMessage.error(message)
    throw new ApiError(message, response.status)
  }

  if (envelope.code !== 0) {
    if (!options.silent) ElMessage.error(envelope.message || '操作失败')
    throw new ApiError(envelope.message || '操作失败', response.status, envelope.code, envelope.traceId)
  }

  return envelope.data as T
}

export const http = {
  get<T>(path: string, query?: Record<string, unknown>) {
    return request<T>('GET', path, { query })
  },
  post<T>(path: string, body?: unknown) {
    return request<T>('POST', path, { body })
  },
  put<T>(path: string, body?: unknown) {
    return request<T>('PUT', path, { body })
  },
  patch<T>(path: string, body?: unknown) {
    return request<T>('PATCH', path, { body })
  }
}
