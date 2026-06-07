type MessageHandler = (type: string, data: unknown) => void

export class WsClient {
  private ws: WebSocket | null = null
  private handlers: MessageHandler[] = []
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private shouldReconnect = false

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

  onMessage(handler: MessageHandler) {
    this.handlers.push(handler)
  }

  private openConnection() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/ws`
    this.ws = new WebSocket(url)

    this.ws.onmessage = (event) => {
      try {
        const { type, data } = JSON.parse(event.data as string) as { type: string; data: unknown }
        for (const handler of this.handlers) {
          handler(type, data)
        }
      } catch {
        // ignore parse errors
      }
    }

    this.ws.onclose = () => {
      if (this.shouldReconnect) {
        this.reconnectTimer = setTimeout(() => this.openConnection(), 3000)
      }
    }

    this.ws.onerror = () => {
      this.ws?.close()
    }
  }
}

export const wsClient = new WsClient()
