import { useEffect, useState } from 'react'
import api from '../lib/api'

interface Ban {
  player: string
  reason: string | null
  expires: number | null
}

const DURATION_OPTIONS: { label: string; seconds: number | undefined }[] = [
  { label: '永久', seconds: undefined },
  { label: '1時間', seconds: 60 * 60 },
  { label: '1日', seconds: 60 * 60 * 24 },
  { label: '3日', seconds: 60 * 60 * 24 * 3 },
  { label: '7日', seconds: 60 * 60 * 24 * 7 },
  { label: '30日', seconds: 60 * 60 * 24 * 30 },
]

export default function Bans() {
  const [bans, setBans] = useState<Ban[]>([])
  const [newPlayer, setNewPlayer] = useState('')
  const [newReason, setNewReason] = useState('')
  const [newDuration, setNewDuration] = useState(DURATION_OPTIONS[0].label)

  const reload = () => api.bans().then(d => setBans(d as Ban[]))

  useEffect(() => { reload() }, [])

  async function addBan(e: React.FormEvent) {
    e.preventDefault()
    if (!newPlayer.trim()) return
    const duration = DURATION_OPTIONS.find(o => o.label === newDuration)?.seconds
    await api.addBan(newPlayer, newReason || 'Banned by WARP', duration)
    setNewPlayer('')
    setNewReason('')
    setNewDuration(DURATION_OPTIONS[0].label)
    reload()
  }

  return (
    <div className="p-6 space-y-6">
      <h2 className="text-lg font-semibold text-white">BAN 管理</h2>
      <form onSubmit={addBan} className="flex gap-2">
        <input
          value={newPlayer}
          onChange={e => setNewPlayer(e.target.value)}
          placeholder="プレイヤー名"
          className="flex-1 px-3 py-2 rounded-lg text-white text-sm focus:outline-none"
          style={{ backgroundColor: '#141c35', border: '1px solid rgba(255,255,255,0.1)' }}
        />
        <input
          value={newReason}
          onChange={e => setNewReason(e.target.value)}
          placeholder="理由 (任意)"
          className="flex-1 px-3 py-2 rounded-lg text-white text-sm focus:outline-none"
          style={{ backgroundColor: '#141c35', border: '1px solid rgba(255,255,255,0.1)' }}
        />
        <select
          value={newDuration}
          onChange={e => setNewDuration(e.target.value)}
          className="px-3 py-2 rounded-lg text-white text-sm focus:outline-none"
          style={{ backgroundColor: '#141c35', border: '1px solid rgba(255,255,255,0.1)' }}
        >
          {DURATION_OPTIONS.map(o => (
            <option key={o.label} value={o.label}>{o.label}</option>
          ))}
        </select>
        <button
          type="submit"
          className="px-4 py-2 rounded-lg text-sm font-medium text-white"
          style={{ backgroundColor: '#ef4444' }}
        >
          BAN
        </button>
      </form>
      <div className="rounded-xl overflow-hidden" style={{ backgroundColor: '#141c35' }}>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b" style={{ borderColor: 'rgba(255,255,255,0.05)' }}>
              <th className="px-4 py-3">プレイヤー</th>
              <th className="px-4 py-3">理由</th>
              <th className="px-4 py-3">期限</th>
              <th className="px-4 py-3"></th>
            </tr>
          </thead>
          <tbody>
            {bans.map(b => (
              <tr key={b.player} className="border-b" style={{ borderColor: 'rgba(255,255,255,0.03)' }}>
                <td className="px-4 py-3 text-white">{b.player}</td>
                <td className="px-4 py-3 text-gray-300">{b.reason ?? '—'}</td>
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {b.expires ? new Date(b.expires).toLocaleDateString('ja-JP') : '永続'}
                </td>
                <td className="px-4 py-3">
                  <button
                    onClick={() => api.removeBan(b.player).then(reload)}
                    className="text-red-400 hover:text-red-300 text-xs"
                  >
                    解除
                  </button>
                </td>
              </tr>
            ))}
            {bans.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-500">BANリストなし</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
