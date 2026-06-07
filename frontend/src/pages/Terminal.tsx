import { useEffect, useRef, useState } from 'react'
import { Terminal as XTerm } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import api from '../lib/api'
import { wsClient } from '../lib/ws'

export default function Terminal() {
  const termRef = useRef<HTMLDivElement>(null)
  const xtermRef = useRef<XTerm | null>(null)
  const [cmd, setCmd] = useState('')

  useEffect(() => {
    const term = new XTerm({
      theme: { background: '#0a0e27', foreground: '#e5e7eb', cursor: '#10b981' },
      fontSize: 13,
      fontFamily: 'monospace',
    })
    const fit = new FitAddon()
    term.loadAddon(fit)
    if (termRef.current) {
      term.open(termRef.current)
      fit.fit()
    }
    xtermRef.current = term

    wsClient.onMessage((type, data) => {
      if (type === 'log' || type === 'console') {
        const d = data as { line?: string; msg?: string }
        term.writeln(d.line ?? d.msg ?? '')
      }
    })

    const ro = new ResizeObserver(() => fit.fit())
    if (termRef.current) ro.observe(termRef.current)

    return () => {
      ro.disconnect()
      term.dispose()
    }
  }, [])

  async function sendCommand(e: React.FormEvent) {
    e.preventDefault()
    if (!cmd.trim()) return
    try {
      await api.sendCommand(cmd)
      setCmd('')
    } catch {
      // ignore
    }
  }

  return (
    <div className="flex flex-col h-full p-4 gap-2">
      <div ref={termRef} className="flex-1 rounded-lg overflow-hidden" style={{ minHeight: '300px' }} />
      <form onSubmit={sendCommand} className="flex gap-2">
        <input
          value={cmd}
          onChange={e => setCmd(e.target.value)}
          className="flex-1 px-4 py-2 rounded-lg text-white text-sm focus:outline-none"
          style={{ backgroundColor: '#141c35', border: '1px solid rgba(255,255,255,0.1)' }}
          placeholder="コマンドを入力..."
        />
        <button
          type="submit"
          className="px-4 py-2 rounded-lg text-sm font-medium text-white"
          style={{ backgroundColor: '#10b981' }}
        >
          実行
        </button>
      </form>
    </div>
  )
}
