// Auto-generated types will be imported when openapi-typescript runs
// For now, use 'unknown' as placeholder

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
    throw new Error(`${res.status} ${res.statusText}`)
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
  addBan: (player: string, reason: string, duration?: string) =>
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
}

export default api
