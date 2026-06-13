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
      className="w-48 h-screen flex flex-col py-6 px-3 shrink-0 bg-warp-sidebar border-r border-white/5"
    >
      <div className="mb-8 px-3">
        <span className="text-xl font-bold text-warp-accent">WARP</span>
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
                  ? 'text-white font-medium bg-warp-accent/15'
                  : 'text-gray-400 hover:text-white'
              }`
            }
          >
            {link.label}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
