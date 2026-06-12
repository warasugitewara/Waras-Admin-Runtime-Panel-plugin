import { useEffect, type ReactNode } from 'react'
import { wsClient } from '../lib/ws'

export default function WsProvider({ children }: { children: ReactNode }) {
  useEffect(() => {
    wsClient.connect()
    return () => wsClient.disconnect()
  }, [])

  return <>{children}</>
}
