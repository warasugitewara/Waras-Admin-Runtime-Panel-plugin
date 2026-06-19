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
import Plugins from './pages/Plugins'
import NotFound from './pages/NotFound'
import RequireAuth from './components/RequireAuth'
import WsProvider from './components/WsProvider'
import Layout from './components/layout/Layout'

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
                <Layout />
              </WsProvider>
            </RequireAuth>
          }
        >
          <Route index element={<Dashboard />} />
          <Route path="terminal" element={<Terminal />} />
          <Route path="players" element={<Players />} />
          <Route path="chat" element={<Chat />} />
          <Route path="bans" element={<Bans />} />
          <Route path="logs" element={<Logs />} />
          <Route path="history" element={<History />} />
          <Route path="audit" element={<Audit />} />
          <Route path="plugins" element={<Plugins />} />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Routes>
    </BrowserRouter>
  )
}
