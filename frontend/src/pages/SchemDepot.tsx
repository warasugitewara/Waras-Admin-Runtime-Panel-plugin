import { useEffect, useState } from 'react'
import api from '../lib/api'
import type { SchemDepotAssets, SchemDepotStats } from '../lib/api'
import { formatBytes } from '../lib/format'
import { useSchemDepotStatus } from '../hooks/useSchemDepot'

const SORTS = [
  { key: 'created', label: '登録日' },
  { key: 'name', label: '名前' },
  { key: 'size', label: '容量' },
  { key: 'author', label: '作者' },
] as const

export default function SchemDepot() {
  const status = useSchemDepotStatus()
  const [stats, setStats] = useState<SchemDepotStats | null>(null)
  const [page, setPage] = useState(0)
  const [assets, setAssets] = useState<SchemDepotAssets | null>(null)
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<string>('created')
  const [order, setOrder] = useState<'asc' | 'desc'>('desc')

  useEffect(() => {
    if (!status.available) return
    api.schemDepotStats().then(setStats)
  }, [status.available])

  useEffect(() => {
    if (!status.available) return
    api.schemDepotAssets(page, query, sort, order).then(setAssets)
  }, [status.available, page, query, sort, order])

  if (status.loading) {
    return <div className="p-6 text-gray-400">読み込み中…</div>
  }

  if (!status.available) {
    return (
      <div className="p-6 space-y-2">
        <h2 className="text-lg font-semibold text-white">SchemDepot</h2>
        <p className="text-sm text-gray-400">
          SchemDepot が見つかりません（{status.reason ?? 'not_installed'}）
        </p>
      </div>
    )
  }

  function toggleSort(key: string) {
    if (sort === key) {
      setOrder(o => (o === 'asc' ? 'desc' : 'asc'))
    } else {
      setSort(key)
      setOrder('desc')
    }
    setPage(0)
  }

  const totalPages = assets ? Math.max(1, Math.ceil(assets.total / assets.pageSize)) : 1
  const integrity = stats?.integrity
  const hasIntegrityIssue =
    !!integrity &&
    (integrity.missingFiles.length > 0 ||
      integrity.orphanFiles.length > 0 ||
      integrity.schematicsUnreadable)

  return (
    <div className="p-6 space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-white">SchemDepot</h2>
        <p className="text-sm text-gray-400 mt-1">登録済みアセットと容量の内訳（閲覧のみ）</p>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <div className="rounded-xl p-4 bg-warp-panel">
          <p className="text-xs text-gray-400">アセット数</p>
          <p className="text-2xl font-bold mt-1 text-warp-accent">{stats?.totalCount ?? '—'}</p>
        </div>
        <div className="rounded-xl p-4 bg-warp-panel">
          <p className="text-xs text-gray-400">総容量</p>
          <p className="text-2xl font-bold mt-1 text-warp-accent">
            {stats ? formatBytes(stats.totalBytes) : '—'}
          </p>
        </div>
        <div className="rounded-xl p-4 bg-warp-panel">
          <p className="text-xs text-gray-400">作者数</p>
          <p className="text-2xl font-bold mt-1 text-warp-accent">{stats?.authorCount ?? '—'}</p>
        </div>
      </div>

      <div className="rounded-xl overflow-hidden bg-warp-panel">
        <div className="px-4 py-3 border-b border-white/5 text-sm text-gray-300">作者別</div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b border-white/5">
              <th className="px-4 py-3">作者</th>
              <th className="px-4 py-3 w-24 text-right">件数</th>
              <th className="px-4 py-3 w-28 text-right">容量</th>
              <th className="px-4 py-3 w-48">占有率</th>
            </tr>
          </thead>
          <tbody>
            {(stats?.authors ?? []).map(a => (
              <tr key={a.uuid} className="border-b border-white/3">
                <td className="px-4 py-3 text-white">{a.name}</td>
                <td className="px-4 py-3 text-right text-gray-300">{a.count}</td>
                <td className="px-4 py-3 text-right text-gray-300">{formatBytes(a.bytes)}</td>
                <td className="px-4 py-3">
                  <div className="flex items-center gap-2">
                    <div className="flex-1 h-1.5 rounded-full bg-white/10 overflow-hidden">
                      <div
                        className="h-full bg-warp-accent"
                        style={{ width: `${Math.round(a.share * 100)}%` }}
                      />
                    </div>
                    <span className="text-xs text-gray-400 w-10 text-right">
                      {Math.round(a.share * 100)}%
                    </span>
                  </div>
                </td>
              </tr>
            ))}
            {stats && stats.authors.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-500">
                  アセットがありません
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="rounded-xl overflow-hidden bg-warp-panel">
        <div className="px-4 py-3 border-b border-white/5 flex items-center justify-between gap-4">
          <span className="text-sm text-gray-300">アセット一覧</span>
          <input
            value={query}
            onChange={e => {
              setQuery(e.target.value)
              setPage(0)
            }}
            placeholder="名前・作者で検索"
            className="px-3 py-1.5 rounded-lg bg-black/20 text-sm text-white placeholder-gray-500 outline-none focus:ring-1 focus:ring-warp-accent"
          />
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b border-white/5">
              {SORTS.map(s => (
                <th
                  key={s.key}
                  onClick={() => toggleSort(s.key)}
                  className="px-4 py-3 cursor-pointer select-none hover:text-white"
                >
                  {s.label}
                  {sort === s.key && <span className="ml-1">{order === 'asc' ? '▲' : '▼'}</span>}
                </th>
              ))}
              <th className="px-4 py-3">寸法</th>
            </tr>
          </thead>
          <tbody>
            {(assets?.items ?? []).map(a => (
              <tr key={a.id} className="border-b border-white/3">
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {new Date(a.createdAt).toLocaleString()}
                </td>
                <td className="px-4 py-3 text-white">
                  {a.name}
                  {a.fileMissing && (
                    <span className="ml-2 text-xs text-amber-400">ファイル欠損</span>
                  )}
                </td>
                <td className="px-4 py-3 text-gray-300">{formatBytes(a.bytes)}</td>
                <td className="px-4 py-3 text-gray-300">{a.authorName}</td>
                <td className="px-4 py-3 text-gray-400 text-xs font-mono">
                  {a.sizeX}×{a.sizeY}×{a.sizeZ}
                </td>
              </tr>
            ))}
            {assets && assets.items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-gray-500">
                  該当するアセットがありません
                </td>
              </tr>
            )}
          </tbody>
        </table>
        {assets && assets.total > assets.pageSize && (
          <div className="px-4 py-3 flex items-center justify-between text-sm border-t border-white/5">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
              className="px-3 py-1 rounded-lg bg-white/5 text-gray-300 disabled:opacity-30"
            >
              前へ
            </button>
            <span className="text-gray-400 text-xs">
              {page + 1} / {totalPages}
            </span>
            <button
              onClick={() => setPage(p => (p + 1 < totalPages ? p + 1 : p))}
              disabled={page + 1 >= totalPages}
              className="px-3 py-1 rounded-lg bg-white/5 text-gray-300 disabled:opacity-30"
            >
              次へ
            </button>
          </div>
        )}
      </div>

      {hasIntegrityIssue && (
        <div className="rounded-xl overflow-hidden bg-warp-panel border border-amber-500/30">
          <div className="px-4 py-3 border-b border-white/5 text-sm text-amber-400">
            不整合（表示のみ・WARPからは削除しません）
          </div>
          <div className="px-4 py-3 space-y-3 text-sm">
            {integrity.schematicsUnreadable && (
              <p className="text-gray-300">
                schematics ディレクトリを読めないため、容量を集計できていません
              </p>
            )}
            {integrity.missingFiles.length > 0 && (
              <div>
                <p className="text-xs text-gray-400 mb-1">
                  ファイルが見つからないアセット（{integrity.missingFiles.length}件）
                </p>
                <ul className="space-y-0.5">
                  {integrity.missingFiles.map(m => (
                    <li key={m.id} className="text-gray-300">
                      {m.name} <span className="text-gray-500 font-mono text-xs">{m.file}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {integrity.orphanFiles.length > 0 && (
              <div>
                <p className="text-xs text-gray-400 mb-1">
                  参照されていないファイル（{integrity.orphanFiles.length}件・
                  {formatBytes(integrity.orphanBytes)}）
                </p>
                <ul className="space-y-0.5">
                  {integrity.orphanFiles.map(o => (
                    <li key={o.file} className="text-gray-300 font-mono text-xs">
                      {o.file} <span className="text-gray-500">{formatBytes(o.bytes)}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
