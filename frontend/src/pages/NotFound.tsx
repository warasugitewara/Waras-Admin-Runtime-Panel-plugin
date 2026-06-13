import { Link } from 'react-router-dom'

export default function NotFound() {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-warp-bg gap-4">
      <h1 className="text-4xl font-bold text-warp-accent">404</h1>
      <p className="text-gray-400">ページが見つかりません</p>
      <Link to="/" className="text-warp-accent hover:underline">ダッシュボードに戻る</Link>
    </div>
  )
}
