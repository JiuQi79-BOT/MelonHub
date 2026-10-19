export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId?: string) { super(message) }
}

const TOKEN = 'melonhub-token'
export const session = {
  token: () => sessionStorage.getItem(TOKEN),
  save: (token: string) => sessionStorage.setItem(TOKEN, token),
  clear: () => sessionStorage.removeItem(TOKEN),
}

export type Query = Record<string, string | number | boolean | null | undefined>
export function qs(query?: Query) {
  if (!query) return ''
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) if (value !== undefined && value !== null && value !== '') params.set(key, String(value))
  const text = params.toString()
  return text ? `?${text}` : ''
}

function fallbackMessage(status: number, retryAfter: string | null) {
  if (status === 503) return `服务繁忙，请${retryAfter ? ` ${retryAfter} 秒后` : '稍后'}重试`
  if (status === 429) return '请求过于频繁，请稍后重试'
  if (status === 0) return '无法连接服务，请检查网络或后端是否启动'
  return `请求失败（HTTP ${status}）`
}

export async function api<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
  const token = session.token()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  let response: Response
  try {
    response = await fetch(`/api${path}`, { ...options, headers: { ...headers, ...(options.headers as Record<string, string> | undefined) } })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', fallbackMessage(0, null))
  }
  const body = await response.json().catch(() => null) as Record<string, unknown> | null
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/auth/login') && session.token() === token) {
      session.clear()
      window.dispatchEvent(new Event('session-expired'))
    }
    const message = typeof body?.message === 'string' ? body.message : fallbackMessage(response.status, response.headers.get('Retry-After'))
    const code = typeof body?.code === 'string' ? body.code : `HTTP_${response.status}`
    throw new ApiError(response.status, code, message, response.headers.get('X-Request-Id') ?? (body?.requestId as string | undefined))
  }
  return body as T
}

export const get = <T = unknown>(path: string, query?: Query) => api<T>(path + qs(query))
export const send = <T = unknown>(method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, body?: unknown, query?: Query) =>
  api<T>(path + qs(query), { method, body: body === undefined ? undefined : JSON.stringify(body) })

export function errorText(error: unknown) {
  if (error instanceof ApiError) return error.requestId && error.status >= 500 ? `${error.message}（请求编号 ${error.requestId}）` : error.message
  return (error as Error)?.message || '操作失败'
}

export interface PageResult<T> { items: T[]; total: number; page: number; size: number }
