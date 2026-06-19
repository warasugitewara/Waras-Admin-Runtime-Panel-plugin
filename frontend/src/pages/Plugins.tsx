import { useEffect, useState } from 'react'
import api from '../lib/api'

interface PluginInfo {
  name: string
  version: string
  enabled: boolean
  description: string | null
  authors: string[]
  self: boolean
}

interface SelfUpdateInfo {
  currentVersion: string
  latestVersion: string | null
  updateAvailable: boolean
  releaseUrl: string | null
}

export default function Plugins() {
  const [plugins, setPlugins] = useState<PluginInfo[]>([])
  const [update, setUpdate] = useState<SelfUpdateInfo | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const reload = () => api.plugins().then(d => setPlugins(d as PluginInfo[]))

  useEffect(() => {
    reload()
    api.selfUpdate().then(d => setUpdate(d as SelfUpdateInfo))
  }, [])

  async function toggle(p: PluginInfo) {
    if (p.self) return
    setBusy(p.name)
    try {
      await (p.enabled ? api.disablePlugin(p.name) : api.enablePlugin(p.name))
      await reload()
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="p-6 space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-white">Plugins</h2>
        <p className="text-sm text-gray-400 mt-1">インストール済みプラグインの有効・無効を切り替えます</p>
      </div>

      {update && (
        <div className="rounded-xl bg-warp-panel border border-warp-accent/30 px-5 py-4 flex items-center justify-between">
          <div>
            <div className="text-sm text-gray-400">WARP</div>
            <div className="text-white font-mono text-sm mt-0.5">
              v{update.currentVersion}
              {update.updateAvailable && update.latestVersion && (
                <span className="text-gray-500"> → v{update.latestVersion}</span>
              )}
            </div>
          </div>
          {update.updateAvailable ? (
            <a
              href={update.releaseUrl ?? undefined}
              target="_blank"
              rel="noreferrer"
              className="px-4 py-2 rounded-lg text-sm font-medium text-white bg-warp-accent hover:bg-warp-accent/90 transition-colors"
            >
              リリースを見る
            </a>
          ) : (
            <span className="text-sm text-warp-accent">最新版です</span>
          )}
        </div>
      )}

      <div className="rounded-xl overflow-hidden bg-warp-panel">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b border-white/5">
              <th className="px-4 py-3 w-8"></th>
              <th className="px-4 py-3">プラグイン</th>
              <th className="px-4 py-3">バージョン</th>
              <th className="px-4 py-3">説明</th>
              <th className="px-4 py-3"></th>
            </tr>
          </thead>
          <tbody>
            {plugins.map(p => (
              <tr key={p.name} className="border-b border-white/3">
                <td className="px-4 py-3">
                  <span
                    className={`inline-block w-2 h-2 rounded-full ${
                      p.enabled ? 'bg-warp-accent animate-pulse' : 'bg-gray-600'
                    }`}
                  />
                </td>
                <td className="px-4 py-3 text-white">
                  {p.name}
                  {p.self && <span className="ml-2 text-xs text-warp-accent/80">(自身)</span>}
                </td>
                <td className="px-4 py-3">
                  <span className="font-mono text-xs text-gray-300 bg-black/20 rounded px-2 py-0.5">
                    v{p.version}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-400 text-xs">{p.description ?? '—'}</td>
                <td className="px-4 py-3 text-right">
                  <button
                    onClick={() => toggle(p)}
                    disabled={p.self || busy === p.name}
                    title={p.self ? 'WARP自身は無効化できません' : undefined}
                    className={`relative w-11 h-6 rounded-full transition-colors ${
                      p.enabled ? 'bg-warp-accent' : 'bg-white/10'
                    } ${p.self ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer'}`}
                  >
                    <span
                      className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white transition-transform ${
                        p.enabled ? 'translate-x-5' : ''
                      }`}
                    />
                  </button>
                </td>
              </tr>
            ))}
            {plugins.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-gray-500">
                  プラグインがありません
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
