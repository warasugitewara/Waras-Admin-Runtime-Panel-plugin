import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import TpsChart from '../components/charts/TpsChart'
import api from '../lib/api'
import type { SchemDepotStats, Status } from '../lib/api'
import { formatBytes } from '../lib/format'
import { useSchemDepotStatus } from '../hooks/useSchemDepot'
import { wsClient } from '../lib/ws'

export default function Dashboard() {
  const [metrics, setMetrics] = useState<Status | null>(null)
  const [tpsHistory, setTpsHistory] = useState<number[]>([])
  const schemDepot = useSchemDepotStatus()
  const [schemStats, setSchemStats] = useState<SchemDepotStats | null>(null)

  useEffect(() => {
    if (!schemDepot.available) return
    api.schemDepotStats().then(setSchemStats)
  }, [schemDepot.available])

  useEffect(() => {
    return wsClient.onMessage((type, data) => {
      if (type !== 'metrics') return
      // WSはOpenAPI仕様の対象外だが、バックエンドはWSでも同じMetricsCollector.Statusを送信しているためStatusにキャストする
      const m = data as Status
      setMetrics(m)
      setTpsHistory(prev => [...prev.slice(-59), m.tps[0]])
    })
  }, [])

  const cards = metrics
    ? [
        { label: 'TPS (1min)', value: metrics.tps[0].toFixed(1) },
        { label: 'MSPT', value: metrics.mspt.toFixed(1) + 'ms' },
        { label: 'Players', value: String(metrics.players) },
        { label: 'Memory', value: metrics.memoryUsedMb + ' MB' },
      ]
    : []

  return (
    <div className="p-6 space-y-6">
      <h2 className="text-lg font-semibold text-white">Dashboard</h2>
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {cards.map(card => (
          <div
            key={card.label}
            className="rounded-xl p-4 bg-warp-panel"
          >
            <p className="text-xs text-gray-400">{card.label}</p>
            <p className="text-2xl font-bold mt-1 text-warp-accent">
              {card.value}
            </p>
          </div>
        ))}
      </div>
      {schemDepot.available && schemStats && (
        <Link
          to="/schemdepot"
          className="block rounded-xl p-4 bg-warp-panel hover:bg-warp-panel/80 transition-colors"
        >
          <p className="text-xs text-gray-400">SchemDepot</p>
          <p className="text-2xl font-bold mt-1 text-warp-accent">
            {schemStats.totalCount}
            <span className="text-sm font-normal text-gray-400 ml-2">アセット</span>
            <span className="text-sm font-normal text-gray-400 ml-3">
              {formatBytes(schemStats.totalBytes)}
            </span>
          </p>
        </Link>
      )}
      {tpsHistory.length > 0 && (
        <div className="rounded-xl p-4 bg-warp-panel">
          <p className="text-xs text-gray-400 mb-2">TPS History</p>
          <TpsChart series={tpsHistory} />
        </div>
      )}
    </div>
  )
}
