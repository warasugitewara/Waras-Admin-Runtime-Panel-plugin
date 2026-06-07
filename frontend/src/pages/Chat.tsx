import { useEffect, useRef, useState } from 'react'
import api from '../lib/api'
import { wsClient } from '../lib/ws'

interface ChatMsg {
  id: number
  ts: number
  playerName: string
  message: string
}

export default function Chat() {
  const [messages, setMessages] = useState<ChatMsg[]>([])
  const [msg, setMsg] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api.chat().then(d => setMessages((d as ChatMsg[]).reverse()))
    wsClient.onMessage((type, data) => {
      if (type !== 'chat') return
      const d = data as { player: string; msg: string; time: number }
      setMessages(prev => [
        ...prev,
        { id: Date.now(), ts: d.time, playerName: d.player, message: d.msg },
      ])
    })
  }, [])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  async function send(e: React.FormEvent) {
    e.preventDefault()
    if (!msg.trim()) return
    await api.sendChat(msg)
    setMsg('')
  }

  return (
    <div className="flex flex-col h-full p-4 gap-3">
      <h2 className="text-lg font-semibold text-white">チャット</h2>
      <div className="flex-1 overflow-y-auto space-y-2 rounded-xl p-3" style={{ backgroundColor: '#141c35' }}>
        {messages.map(m => (
          <div key={m.id} className="flex gap-2 text-sm">
            <span className="text-gray-500 text-xs shrink-0">
              {new Date(m.ts).toLocaleTimeString('ja-JP')}
            </span>
            <span style={{ color: '#10b981' }} className="font-medium shrink-0">{m.playerName}</span>
            <span className="text-gray-300">{m.message}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      <form onSubmit={send} className="flex gap-2">
        <input
          value={msg}
          onChange={e => setMsg(e.target.value)}
          className="flex-1 px-4 py-2 rounded-lg text-white text-sm focus:outline-none"
          style={{ backgroundColor: '#141c35', border: '1px solid rgba(255,255,255,0.1)' }}
          placeholder="メッセージを送信..."
        />
        <button
          type="submit"
          className="px-4 py-2 rounded-lg text-sm font-medium text-white"
          style={{ backgroundColor: '#10b981' }}
        >
          送信
        </button>
      </form>
    </div>
  )
}
