import { useEffect, useState } from 'react'
import api from '../lib/api'

interface Log {
  id: number
  ts: number
  level: string
  logger: string
  message: string
}

const LEVELS = ['', 'INFO', 'WARN', 'ERROR']
const levelColor: Record<string, string> = {
  INFO: '#60a5fa',
  WARN: '#fbbf24',
  ERROR: '#f87171',
}

export default function Logs() {
  const [logs, setLogs] = useState<Log[]>([])
  const [level, setLevel] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)

  useEffect(() => {
    api.logs(page, level || undefined, q || undefined).then(d => setLogs(d as Log[]))
  }, [page, level, q])

  return (
    <div className="p-6 space-y-4">
      <h2 className="text-lg font-semibold text-white">ログ</h2>
      <div className="flex gap-2">
        <select
          value={level}
          onChange={e => { setLevel(e.target.value); setPage(0) }}
          className="px-3 py-2 rounded-lg text-white text-sm focus:outline-none"
          style={{ backgroundColor: '#141c35', border: '1px solid rgba(255,255,255,0.1)' }}
        >
          {LEVELS.map(l => <option key={l} value={l}>{l || '全レベル'}</option>)}
        </select>
        <input
          value={q}
          onChange={e => { setQ(e.target.value); setPage(0) }}
          placeholder="キーワード検索"
          className="flex-1 px-3 py-2 rounded-lg text-white text-sm focus:outline-none"
          style={{ backgroundColor: '#141c35', border: '1px solid rgba(255,255,255,0.1)' }}
        />
      </div>
      <div className="rounded-xl overflow-hidden font-mono text-xs" style={{ backgroundColor: '#141c35' }}>
        {logs.map(log => (
          <div
            key={log.id}
            className="px-4 py-1.5 border-b flex gap-3"
            style={{ borderColor: 'rgba(255,255,255,0.03)' }}
          >
            <span className="text-gray-500 shrink-0">
              {new Date(log.ts).toLocaleTimeString('ja-JP')}
            </span>
            <span className="shrink-0 font-medium" style={{ color: levelColor[log.level] ?? '#9ca3af' }}>
              {log.level}
            </span>
            <span className="text-gray-300 truncate">{log.message}</span>
          </div>
        ))}
        {logs.length === 0 && (
          <div className="px-4 py-8 text-center text-gray-500">ログなし</div>
        )}
      </div>
      <div className="flex gap-2">
        <button
          onClick={() => setPage(p => Math.max(0, p - 1))}
          disabled={page === 0}
          className="px-3 py-1 rounded text-sm text-gray-400 disabled:opacity-30"
          style={{ backgroundColor: '#141c35' }}
        >
          ← 前
        </button>
        <span className="px-3 py-1 text-sm text-gray-400">Page {page + 1}</span>
        <button
          onClick={() => setPage(p => p + 1)}
          disabled={logs.length < 100}
          className="px-3 py-1 rounded text-sm text-gray-400 disabled:opacity-30"
          style={{ backgroundColor: '#141c35' }}
        >
          次 →
        </button>
      </div>
    </div>
  )
}
