import { useEffect, useRef, useState } from 'react'
import api from '../lib/api'
import type { ChatEntry } from '../lib/api'
import { wsClient } from '../lib/ws'

// この画面が表示に使うのは名前と本文と時刻だけ。WS 経由で届く chat イベントには
// playerUuid が入っていないので、それを含む ChatEntry をそのまま状態の型にすると
// 空文字を埋める羽目になる。画面が実際に必要とする分だけを型にしておく。
type ChatLine = Omit<ChatEntry, 'playerUuid'>

export default function Chat() {
  const [messages, setMessages] = useState<ChatLine[]>([])
  const [msg, setMsg] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api.chat().then(d => setMessages(d.reverse()))
    return wsClient.onMessage((type, data) => {
      if (type !== 'chat') return
      // WS は OpenAPI 仕様の対象外なのでここだけは手書きの形で受ける
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
      <div className="flex-1 overflow-y-auto space-y-2 rounded-xl p-3 bg-warp-panel">
        {messages.map(m => (
          <div key={m.id} className="flex gap-2 text-sm">
            <span className="text-gray-500 text-xs shrink-0">
              {new Date(m.ts).toLocaleTimeString('ja-JP')}
            </span>
            <span className="font-medium shrink-0 text-warp-accent">{m.playerName}</span>
            <span className="text-gray-300">{m.message}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      <form onSubmit={send} className="flex gap-2">
        <input
          value={msg}
          onChange={e => setMsg(e.target.value)}
          className="flex-1 px-4 py-2 rounded-lg text-white text-sm focus:outline-none bg-warp-panel border border-white/10"
          placeholder="メッセージを送信..."
        />
        <button
          type="submit"
          className="px-4 py-2 rounded-lg text-sm font-medium text-white bg-warp-accent"
        >
          送信
        </button>
      </form>
    </div>
  )
}
