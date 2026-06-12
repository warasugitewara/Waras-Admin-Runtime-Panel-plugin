type MessageHandler = (type: string, data: unknown) => void

const INITIAL_RECONNECT_DELAY = 1000
const MAX_RECONNECT_DELAY = 30000

export class WsClient {
  private ws: WebSocket | null = null
  private handlers = new Set<MessageHandler>()
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private shouldReconnect = false
  private reconnectDelay = INITIAL_RECONNECT_DELAY

  connect() {
    this.shouldReconnect = true
    this.openConnection()
  }

  disconnect() {
    this.shouldReconnect = false
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
  }

  onMessage(handler: MessageHandler): () => void {
    this.handlers.add(handler)
    return () => this.handlers.delete(handler)
  }

  private openConnection() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/ws`
    const socket = new WebSocket(url)
    this.ws = socket

    socket.onopen = () => {
      this.reconnectDelay = INITIAL_RECONNECT_DELAY
    }

    socket.onmessage = (event) => {
      try {
        const { type, data } = JSON.parse(event.data as string) as { type: string; data: unknown }
        for (const handler of this.handlers) {
          handler(type, data)
        }
      } catch {
        // ignore parse errors
      }
    }

    socket.onclose = (event) => {
      // StrictMode等でconnect/disconnectが連続実行された際、古いsocketの
      // closeイベントが現在のsocketの状態に影響しないようにする
      if (this.ws !== socket) return
      // サーバーが認証エラーで切断した場合は再接続せずログインへ
      if (event.code === 4401) {
        this.shouldReconnect = false
        window.location.href = '/login'
        return
      }
      if (this.shouldReconnect) {
        this.reconnectTimer = setTimeout(() => this.openConnection(), this.reconnectDelay)
        this.reconnectDelay = Math.min(this.reconnectDelay * 2, MAX_RECONNECT_DELAY)
      }
    }

    socket.onerror = () => {
      socket.close()
    }
  }
}

export const wsClient = new WsClient()
