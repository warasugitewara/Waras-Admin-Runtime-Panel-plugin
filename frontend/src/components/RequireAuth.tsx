import { useEffect, useState, type ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import api, { ApiError } from '../lib/api'

export default function RequireAuth({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<'loading' | 'authed' | 'unauthed'>('loading')

  useEffect(() => {
    api.status()
      .then(() => setStatus('authed'))
      .catch((e) => {
        // 401以外（ネットワーク断・5xx等)では不要なログアウトを避ける
        setStatus(e instanceof ApiError && e.status === 401 ? 'unauthed' : 'authed')
      })
  }, [])

  if (status === 'loading') return null
  if (status === 'unauthed') return <Navigate to="/login" replace />
  return <>{children}</>
}
