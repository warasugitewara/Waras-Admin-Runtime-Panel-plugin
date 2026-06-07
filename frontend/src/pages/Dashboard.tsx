import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import Sidebar from '../components/layout/Sidebar'
import TpsChart from '../components/charts/TpsChart'
import { wsClient } from '../lib/ws'

interface Metrics {
  tps: [number, number, number]
  mspt: number
  players: number
  uptime: number
  memoryUsedMb: number
}

function DashboardHome() {
  const [metrics, setMetrics] = useState<Metrics | null>(null)
  const [tpsHistory, setTpsHistory] = useState<number[]>([])

  useEffect(() => {
    wsClient.connect()
    wsClient.onMessage((type, data) => {
      if (type !== 'metrics') return
      const m = data as Metrics
      setMetrics(m)
      setTpsHistory(prev => [...prev.slice(-59), m.tps[0]])
    })
    return () => wsClient.disconnect()
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
            className="rounded-xl p-4"
            style={{ backgroundColor: '#141c35' }}
          >
            <p className="text-xs text-gray-400">{card.label}</p>
            <p className="text-2xl font-bold mt-1" style={{ color: '#10b981' }}>
              {card.value}
            </p>
          </div>
        ))}
      </div>
      {tpsHistory.length > 0 && (
        <div className="rounded-xl p-4" style={{ backgroundColor: '#141c35' }}>
          <p className="text-xs text-gray-400 mb-2">TPS History</p>
          <TpsChart series={tpsHistory} />
        </div>
      )}
    </div>
  )
}

export default function Dashboard() {
  const location = useLocation()
  const isRoot = location.pathname === '/'

  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar />
      <main className="flex-1 overflow-y-auto">
        {isRoot ? <DashboardHome /> : <Outlet />}
      </main>
    </div>
  )
}
