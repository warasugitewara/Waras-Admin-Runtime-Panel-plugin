import { createContext, createElement, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import api from '../lib/api'
import type { SchemDepotStatus } from '../lib/api'

// loading はAPIレスポンスには無いフロントエンド専用のUI状態のため、
// 生成型 (available/reason) に追加する形で保持する
type SchemDepotState = SchemDepotStatus & { loading: boolean }

const initial: SchemDepotState = { available: false, reason: null, loading: true }

const SchemDepotContext = createContext<SchemDepotState>(initial)

export function SchemDepotProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SchemDepotState>(initial)

  useEffect(() => {
    let cancelled = false
    api
      .schemDepotStatus()
      .then(s => {
        if (cancelled) return
        setStatus({ available: s.available, reason: s.reason, loading: false })
      })
      .catch(() => {
        if (!cancelled) setStatus({ available: false, reason: null, loading: false })
      })
    return () => {
      cancelled = true
    }
  }, [])

  return createElement(SchemDepotContext.Provider, { value: status }, children)
}

export function useSchemDepotStatus(): SchemDepotState {
  return useContext(SchemDepotContext)
}
