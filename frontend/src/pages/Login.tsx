import { useState } from 'react'
import api from '../lib/api'

export default function Login() {
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await api.login(code)
      window.location.href = '/'
    } catch {
      setError('認証コードが正しくありません')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-warp-bg">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-warp-accent">WARP</h1>
          <p className="text-gray-400 text-sm mt-1">Waras-Admin-Runtime-Panel</p>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm text-gray-400 mb-1">
              認証コード (6桁)
            </label>
            <input
              type="text"
              inputMode="numeric"
              pattern="[0-9]{6}"
              maxLength={6}
              value={code}
              onChange={e => setCode(e.target.value.replace(/\D/g, ''))}
              className="w-full px-4 py-3 rounded-lg text-white text-center text-2xl tracking-widest focus:outline-none bg-warp-panel border border-white/10"
              placeholder="000000"
              autoComplete="one-time-code"
              autoFocus
            />
          </div>
          {error && (
            <p className="text-red-400 text-sm text-center">{error}</p>
          )}
          <button
            type="submit"
            disabled={loading || code.length !== 6}
            className="w-full py-3 rounded-lg font-medium text-white transition-opacity disabled:opacity-50 bg-warp-accent"
          >
            {loading ? '認証中...' : 'ログイン'}
          </button>
        </form>
      </div>
    </div>
  )
}
