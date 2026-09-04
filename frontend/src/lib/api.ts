// Auto-generated types will be imported when openapi-typescript runs
// For now, use 'unknown' as placeholder

export class ApiError extends Error {
  status: number

  constructor(status: number, statusText: string) {
    super(`${status} ${statusText}`)
    this.name = 'ApiError'
    this.status = status
  }
}

async function getCsrfToken(): Promise<string> {
  const match = document.cookie.match(/csrf_token=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : ''
}

async function request<T>(
  path: string,
  method: 'GET' | 'POST' | 'DELETE' = 'GET',
  body?: unknown
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (method !== 'GET') {
    headers['X-CSRF-Token'] = await getCsrfToken()
  }
  const res = await fetch(path, {
    method,
    credentials: 'include',
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    if (res.status === 401) {
      window.location.href = '/login'
    }
    let message = res.statusText
    try {
      const data = await res.json()
      if (data && typeof data.error === 'string') {
        message = data.error
      }
    } catch {
      // JSON以外のエラーレスポンスはstatusTextを使う
    }
    throw new ApiError(res.status, message)
  }
  const text = await res.text()
  if (!text) return undefined as T
  return JSON.parse(text) as T
}

const api = {
  login: (code: string) =>
    request<void>('/api/auth/login', 'POST', { code }),
  logout: () =>
    request<void>('/api/auth/logout', 'POST'),
  status: () =>
    request<unknown>('/api/status'),
  players: () =>
    request<unknown[]>('/api/players'),
  bans: () =>
    request<unknown[]>('/api/bans'),
  addBan: (player: string, reason: string, duration?: number) =>
    request<void>('/api/bans', 'POST', { player, reason, duration }),
  removeBan: (player: string) =>
    request<void>(`/api/bans/${encodeURIComponent(player)}`, 'DELETE'),
  ipbans: () =>
    request<unknown[]>('/api/ipbans'),
  addIpBan: (ip: string, reason: string) =>
    request<void>('/api/ipbans', 'POST', { ip, reason }),
  removeIpBan: (ip: string) =>
    request<void>(`/api/ipbans/${encodeURIComponent(ip)}`, 'DELETE'),
  logs: (page = 0, level?: string, q?: string) =>
    request<unknown[]>(
      `/api/logs?page=${page}${level ? `&level=${encodeURIComponent(level)}` : ''}${q ? `&q=${encodeURIComponent(q)}` : ''}`
    ),
  chat: (page = 0) =>
    request<unknown[]>(`/api/chat?page=${page}`),
  sendChat: (message: string) =>
    request<void>('/api/chat', 'POST', { message }),
  sendCommand: (command: string) =>
    request<void>('/api/console', 'POST', { command }),
  history: (player: string, page = 0) =>
    request<unknown[]>(`/api/history?player=${encodeURIComponent(player)}&page=${page}`),
  audit: (page = 0) =>
    request<unknown[]>(`/api/audit?page=${page}`),
  plugins: () =>
    request<unknown[]>('/api/plugins'),
  enablePlugin: (name: string) =>
    request<void>(`/api/plugins/${encodeURIComponent(name)}/enable`, 'POST'),
  disablePlugin: (name: string) =>
    request<void>(`/api/plugins/${encodeURIComponent(name)}/disable`, 'POST'),
  selfUpdate: () =>
    request<unknown>('/api/plugins/self-update'),
  schemDepotStatus: () =>
    request<unknown>('/api/schemdepot/status'),
  schemDepotAssets: (page = 0, q?: string, sort?: string, order?: string) =>
    request<unknown>(
      `/api/schemdepot/assets?page=${page}` +
        `${q ? `&q=${encodeURIComponent(q)}` : ''}` +
        `${sort ? `&sort=${encodeURIComponent(sort)}` : ''}` +
        `${order ? `&order=${encodeURIComponent(order)}` : ''}`
    ),
  schemDepotStats: () =>
    request<unknown>('/api/schemdepot/stats'),
}

export default api
