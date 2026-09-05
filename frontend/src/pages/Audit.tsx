import { useEffect, useState } from 'react'
import api from '../lib/api'
import type { AuditEntry } from '../lib/api'

export default function Audit() {
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [page, setPage] = useState(0)

  useEffect(() => {
    api.audit(page).then(setEntries)
  }, [page])

  return (
    <div className="p-6 space-y-4">
      <h2 className="text-lg font-semibold text-white">監査ログ</h2>
      <div className="rounded-xl overflow-hidden bg-warp-panel">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b border-white/5">
              <th className="px-4 py-3">時刻</th>
              <th className="px-4 py-3">送信元IP</th>
              <th className="px-4 py-3">アクション</th>
              <th className="px-4 py-3">詳細</th>
            </tr>
          </thead>
          <tbody>
            {entries.map(e => (
              <tr key={e.id} className="border-b border-white/3">
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {new Date(e.ts).toLocaleString('ja-JP')}
                </td>
                <td className="px-4 py-3 text-gray-300 font-mono text-xs">{e.sourceIp}</td>
                <td className="px-4 py-3 text-warp-accent">{e.action}</td>
                <td className="px-4 py-3 text-gray-500 font-mono text-xs truncate max-w-md">{e.detail ?? '-'}</td>
              </tr>
            ))}
            {entries.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-500">監査ログなし</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="flex gap-2">
        <button
          onClick={() => setPage(p => Math.max(0, p - 1))}
          disabled={page === 0}
          className="px-3 py-1 rounded text-sm text-gray-400 disabled:opacity-30 bg-warp-panel"
        >
          ← 前
        </button>
        <span className="px-3 py-1 text-sm text-gray-400">Page {page + 1}</span>
        <button
          onClick={() => setPage(p => p + 1)}
          disabled={entries.length < 100}
          className="px-3 py-1 rounded text-sm text-gray-400 disabled:opacity-30 bg-warp-panel"
        >
          次 →
        </button>
      </div>
    </div>
  )
}
