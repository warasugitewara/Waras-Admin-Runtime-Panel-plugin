import { NavLink } from 'react-router-dom'

const links = [
  { to: '/', label: 'Dashboard' },
  { to: '/terminal', label: 'Terminal' },
  { to: '/players', label: 'Players' },
  { to: '/chat', label: 'Chat' },
  { to: '/bans', label: 'Bans' },
  { to: '/logs', label: 'Logs' },
  { to: '/history', label: 'History' },
  { to: '/audit', label: 'Audit' },
]

export default function Sidebar() {
  return (
    <aside
      className="w-48 h-screen flex flex-col py-6 px-3 shrink-0"
      style={{ backgroundColor: '#111828', borderRight: '1px solid rgba(255,255,255,0.05)' }}
    >
      <div className="mb-8 px-3">
        <span className="text-xl font-bold" style={{ color: '#10b981' }}>WARP</span>
      </div>
      <nav className="flex flex-col gap-1">
        {links.map(link => (
          <NavLink
            key={link.to}
            to={link.to}
            end={link.to === '/'}
            className={({ isActive }) =>
              `px-3 py-2 rounded-lg text-sm transition-colors ${
                isActive
                  ? 'text-white font-medium'
                  : 'text-gray-400 hover:text-white'
              }`
            }
            style={({ isActive }) =>
              isActive ? { backgroundColor: 'rgba(16,185,129,0.15)' } : {}
            }
          >
            {link.label}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
