import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Terminal from './pages/Terminal'
import Players from './pages/Players'
import Chat from './pages/Chat'
import Bans from './pages/Bans'
import Logs from './pages/Logs'
import History from './pages/History'
import Audit from './pages/Audit'
import RequireAuth from './components/RequireAuth'
import WsProvider from './components/WsProvider'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route
          path="/"
          element={
            <RequireAuth>
              <WsProvider>
                <Dashboard />
              </WsProvider>
            </RequireAuth>
          }
        >
          <Route path="terminal" element={<Terminal />} />
          <Route path="players" element={<Players />} />
          <Route path="chat" element={<Chat />} />
          <Route path="bans" element={<Bans />} />
          <Route path="logs" element={<Logs />} />
          <Route path="history" element={<History />} />
          <Route path="audit" element={<Audit />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
