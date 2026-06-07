import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<Dashboard />}>
          <Route path="terminal" element={<div className="p-6 text-white">Terminal (準備中)</div>} />
          <Route path="players" element={<div className="p-6 text-white">Players (準備中)</div>} />
          <Route path="chat" element={<div className="p-6 text-white">Chat (準備中)</div>} />
          <Route path="bans" element={<div className="p-6 text-white">Bans (準備中)</div>} />
          <Route path="logs" element={<div className="p-6 text-white">Logs (準備中)</div>} />
          <Route path="history" element={<div className="p-6 text-white">History (準備中)</div>} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
