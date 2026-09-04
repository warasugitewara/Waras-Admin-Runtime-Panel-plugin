import { createContext, createElement, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import api from '../lib/api'

interface SchemDepotStatus {
  available: boolean
  reason: string | null
  loading: boolean
}

const initial: SchemDepotStatus = { available: false, reason: null, loading: true }

const SchemDepotContext = createContext<SchemDepotStatus>(initial)

export function SchemDepotProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SchemDepotStatus>(initial)

  useEffect(() => {
    let cancelled = false
    api
      .schemDepotStatus()
      .then(d => {
        if (cancelled) return
        const s = d as { available: boolean; reason: string | null }
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

export function useSchemDepotStatus(): SchemDepotStatus {
  return useContext(SchemDepotContext)
}
