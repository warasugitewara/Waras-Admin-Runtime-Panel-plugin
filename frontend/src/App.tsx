import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Login from './pages/Login'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="*" element={<div className="p-4 text-white">Loading...</div>} />
      </Routes>
    </BrowserRouter>
  )
}
