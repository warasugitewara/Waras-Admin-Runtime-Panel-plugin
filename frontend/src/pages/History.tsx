import { useState } from 'react'
import api from '../lib/api'

interface HistoryEntry {
  id: number
  ts: number
  playerUuid: string
  playerName: string
  eventType: string
  world: string
  x: number
  y: number
  z: number
}

export default function History() {
  const [player, setPlayer] = useState('')
  const [results, setResults] = useState<HistoryEntry[]>([])

  async function search(e: React.FormEvent) {
    e.preventDefault()
    const data = await api.history(player, 0)
    setResults(data as HistoryEntry[])
  }

  return (
    <div className="p-6 space-y-4">
      <h2 className="text-lg font-semibold text-white">プレイヤー履歴</h2>
      <form onSubmit={search} className="flex gap-2">
        <input
          value={player}
          onChange={e => setPlayer(e.target.value)}
          placeholder="UUID を入力"
          className="flex-1 px-3 py-2 rounded-lg text-white text-sm focus:outline-none"
          style={{ backgroundColor: '#141c35', border: '1px solid rgba(255,255,255,0.1)' }}
        />
        <button
          type="submit"
          className="px-4 py-2 rounded-lg text-sm font-medium text-white"
          style={{ backgroundColor: '#10b981' }}
        >
          検索
        </button>
      </form>
      <div className="rounded-xl overflow-hidden" style={{ backgroundColor: '#141c35' }}>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b" style={{ borderColor: 'rgba(255,255,255,0.05)' }}>
              <th className="px-4 py-3">時刻</th>
              <th className="px-4 py-3">プレイヤー</th>
              <th className="px-4 py-3">イベント</th>
              <th className="px-4 py-3">座標</th>
            </tr>
          </thead>
          <tbody>
            {results.map(r => (
              <tr key={r.id} className="border-b" style={{ borderColor: 'rgba(255,255,255,0.03)' }}>
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {new Date(r.ts).toLocaleString('ja-JP')}
                </td>
                <td className="px-4 py-3 text-white">{r.playerName}</td>
                <td className="px-4 py-3" style={{ color: '#10b981' }}>{r.eventType}</td>
                <td className="px-4 py-3 text-gray-500 text-xs">
                  {r.world} ({r.x?.toFixed(0)}, {r.y?.toFixed(0)}, {r.z?.toFixed(0)})
                </td>
              </tr>
            ))}
            {results.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-500">
                  UUID を入力して検索
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
