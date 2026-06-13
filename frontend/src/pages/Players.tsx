import { useEffect, useState } from 'react'
import api from '../lib/api'

interface Player {
  name: string
  uuid: string
  ping: number
  world: string
  x: number
  y: number
  z: number
}

export default function Players() {
  const [players, setPlayers] = useState<Player[]>([])

  useEffect(() => {
    const load = () => api.players().then(d => setPlayers(d as Player[]))
    load()
    const id = setInterval(load, 5000)
    return () => clearInterval(id)
  }, [])

  return (
    <div className="p-6">
      <h2 className="text-lg font-semibold text-white mb-4">
        オンラインプレイヤー ({players.length})
      </h2>
      <div className="rounded-xl overflow-hidden bg-warp-panel">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b border-white/5">
              <th className="px-4 py-3">名前</th>
              <th className="px-4 py-3">Ping</th>
              <th className="px-4 py-3">ワールド</th>
              <th className="px-4 py-3">座標</th>
            </tr>
          </thead>
          <tbody>
            {players.map(p => (
              <tr key={p.uuid} className="border-b border-white/3">
                <td className="px-4 py-3 text-white">{p.name}</td>
                <td className="px-4 py-3 text-gray-300">{p.ping}ms</td>
                <td className="px-4 py-3 text-gray-300">{p.world}</td>
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {p.x.toFixed(0)}, {p.y.toFixed(0)}, {p.z.toFixed(0)}
                </td>
              </tr>
            ))}
            {players.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-500">
                  オンラインプレイヤーなし
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
