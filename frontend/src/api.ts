export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

async function csrfToken() {
  const response = await fetch('/api/admin/auth/csrf', { credentials: 'include' })
  const body: ApiResponse<{ token: string; headerName: string }> = await response.json()
  if (!response.ok) throw new Error(body.message || '无法获取安全令牌')
  return body.data
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method || 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrf = await csrfToken()
    headers.set(csrf.headerName, csrf.token)
  }
  const response = await fetch(path, { ...options, headers, credentials: 'include' })
  const body: ApiResponse<T> = await response.json()
  if (!response.ok || body.code >= 400) {
    const error = new Error(body.message || '请求失败') as Error & { status?: number }
    error.status = response.status
    throw error
  }
  return body.data
}
