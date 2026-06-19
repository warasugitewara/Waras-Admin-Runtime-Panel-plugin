# WARP — Waras-Admin-Runtime-Panel

Paper 1.21.x 対応の Minecraft サーバー向け Web 管理パネルプラグイン。
<img width="1867" height="437" alt="2026-06-14_13h44_23" src="https://github.com/user-attachments/assets/29b08c8e-9759-4084-b097-b8a84e5a71fa" />
プラグイン内に Javalin による組み込み HTTP/WS サーバーを持ち、Cloudflare Tunnel を経由して安全に外部公開する。フロントエンドは React + TypeScript で構築した SPA で、プラグインの JAR に同梱して配信する。


## 機能

- **リアルタイムダッシュボード** — TPS / MSPT / プレイヤー数 / メモリをリアルタイムグラフで表示
- **コンソール** — xterm.js によるブラウザ内コンソール、コマンド送信対応
- **プレイヤー管理** — オンラインプレイヤー一覧（Ping・座標付き）
- **チャット** — チャット履歴閲覧 + Admin メッセージ送信
- **BAN / IP-BAN 管理** — 追加・解除（Paper BanList と完全同期）、BAN は期限指定（期間限定BAN）に対応
- **ログビューア** — レベル・キーワードでフィルター可能、ページング対応
- **プレイヤー履歴** — join/quit/death などのイベント履歴を検索
- **監査ログ** — BAN操作・コマンド実行・チャット送信の履歴を閲覧
- **プラグイン管理** — 導入済みプラグインの一覧・有効/無効切替・バージョン確認、WARP自身の更新有無をGitHub Releasesでチェック
- **共有 TOTP 認証** — アカウント不要。1〜4人が同一QRを認証アプリに登録して利用

## アーキテクチャ

```
Internet
  │
Cloudflare Tunnel (HTTPS)
  │
Javalin 6 (127.0.0.1:8080)  ← Paper Plugin 組み込み
  ├── REST API  (Cookie JWT + CSRF)
  ├── WebSocket (リアルタイムメトリクス・ログ)
  └── Static    (React SPA / JAR 同梱)
  │
SQLite (logs / chat / history / audit)
Paper BanList (BAN の SoT)
```

## 技術スタック

| レイヤー | 技術 |
|---|---|
| Plugin | Java 21, Paper API 1.21.1, Gradle 8 + Shadow |
| Web Server | Javalin 6 (Virtual Threads) |
| 認証 | TOTP (RFC 6238), JWT (jjwt 0.12.6), HttpOnly Cookie |
| DB | SQLite (xerial 3.46) |
| API 型 | 手書き型定義（`api.ts`） — OpenAPI 自動生成は未対応 |
| Frontend | React 19 + TypeScript 5 + Vite 8 + Tailwind 4 |
| グラフ | ApexCharts |
| ターミナル | xterm.js 5 |

## セキュリティ設計

- **Layer 1 — Cloudflare**: Tunnel でポート非公開・WAF・DDoS 保護
- **Layer 2 — 認証**: 共有 TOTP + JWT (HttpOnly + Secure + SameSite=Strict Cookie)
- **Layer 3 — API**: 全エンドポイント Cookie 認証必須・状態変更系は CSRF トークン必須
- JWT 秘密鍵は起動時に自動生成（`plugins/WARP/secret.key`）
- ログイン試行: 5回/10分でロックアウト
- BAN 操作・コマンド実行・チャット送信はすべて audit テーブルに記録

## セットアップ

### 必要環境

- Paper 1.21.x サーバー（Java 21 以上）
- Cloudflare アカウント（無料プラン可）

### インストール

1. [Releases](https://github.com/warasugitewara/Waras-Admin-Runtime-Panel-plugin/releases) から `warp-x.x.x-all.jar` をダウンロード
2. Paper サーバーの `plugins/` に配置してサーバーを起動
3. コンソールで TOTP を設定:

```
/warp setup
```

QR コードの URI が出力されるので、Google Authenticator / Authy 等でスキャン。1〜4人が同じ QR を登録する。

4. `https://your-domain/login` にアクセスして認証アプリの6桁コードを入力

### Cloudflare Tunnel 設定

```bash
# トンネル作成
cloudflared tunnel login
cloudflared tunnel create warp
cloudflared tunnel route dns warp admin.example.com

# config.yml
cat << 'EOF' > ~/.cloudflared/config.yml
tunnel: <トンネルUUID>
credentials-file: /root/.cloudflared/<UUID>.json
ingress:
  - hostname: admin.example.com
    service: http://localhost:8080
  - service: http_status:404
EOF

# systemd に登録
cloudflared service install
systemctl enable --now cloudflared
```

### config.yml

`plugins/WARP/config.yml` で設定をカスタマイズ:

```yaml
server:
  host: "127.0.0.1"   # 変更不要（cloudflared 経由のみ許可）
  port: 8080
  cors-origins:
    - "https://admin.example.com"

auth:
  totp-issuer: "WARP"
  login-max-attempts: 5
  login-lockout-minutes: 10
  session-hours: 8

storage:
  logs-max-rows: 100000
  chat-max-rows: 50000
  history-max-rows: 50000

console:
  command-blocklist:
    - "stop"
    - "restart"
```

## コマンド

| コマンド | 動作 |
|---|---|
| `/warp setup` | TOTP 登録（初回） |
| `/warp status` | 現在の TPS・MSPT・プレイヤー数を表示 |
| `/warp reload` | config.yml を再読込 |
| `/warp otp` | 現在のログインコード（6桁）をコンソールに表示 |

権限: `warp.admin`（デフォルト: OP のみ）

## ビルド

```powershell
# リポジトリをクローン
git clone https://github.com/warasugitewara/Waras-Admin-Runtime-Panel-plugin.git
cd Waras-Admin-Runtime-Panel-plugin

# フロントエンドの依存関係をインストール
cd frontend
npm install
cd ..

# JAR ビルド（フロントエンドを自動バンドル）
cd plugin
.\gradlew.bat shadowJar
# → plugin/build/libs/warp-<version>-all.jar
```
## スクリーンショット
<img width="1867" height="437" alt="2026-06-14_13h44_23" src="https://github.com/user-attachments/assets/29b08c8e-9759-4084-b097-b8a84e5a71fa" />
<img width="1872" height="968" alt="2026-06-14_13h43_48" src="https://github.com/user-attachments/assets/8d38b3ab-6282-4c62-8a69-ad4961a4746d" />
<img width="1874" height="965" alt="2026-06-14_13h55_44" src="https://github.com/user-attachments/assets/2a17a1bd-12ba-4b3f-ac29-52bed5121ed2" />

## ライセンス

MIT
