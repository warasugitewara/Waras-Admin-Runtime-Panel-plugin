## WARP — 設計書

### プロジェクト概要

Paper 1.21.x 対応の Minecraft サーバー向け Web 管理パネルプラグイン。プラグイン内に Javalin による組み込み HTTP/WS サーバーを持ち、Cloudflare Tunnel を外部公開の窓口にする二段階防衛構成を取る。フロントエンドは React + TypeScript で構築した SPA で、プラグインの JAR に同梱して配信する。

**運用前提**: 1〜4人の共同管理者。アカウント概念は持たず、単一の共有 TOTP シークレットで認証する。個人特定が不要なため、ユーザーテーブル・ロール管理は不要。

---

### 技術スタック

| レイヤー | 採用技術 | 理由 |
|---|---|---|
| Plugin base | Paper API 1.21.x | 対象環境 |
| Build | Gradle 8 (Kotlin DSL) + Shadow 8.3 | Fat jar + relocation |
| Web framework | Javalin 6 (Jetty embedded) | シンプル API + WS、Virtual Threads 対応 |
| JWT | jjwt 0.12.6 | 安定・広範に使われている |
| TOTP | dev.samstevens.totp 1.7.1 | RFC 6238 準拠 |
| DB | SQLite (xerial sqlite-jdbc 3.46+) | 追加サービス不要、logs/chat/history/audit を格納 |
| API型生成 | Javalin OpenAPI plugin + openapi-typescript | Java⇄TS 間の型契約をビルド時に保証 |
| Frontend | React 18 + TypeScript + Vite 5 | 型安全 SPA |
| Charts | ApexCharts | ダークテーマ、リアルタイム更新 |
| Terminal | xterm.js 5 + FitAddon | 業界標準 |
| Styling | Tailwind CSS v3 | ユーティリティファースト |

**GraalVM について:** JVM モードのため通常の Java ライブラリはそのまま動作する。Native Image は対象外。

**最重要:** Javalin が同梱する Jetty と Paper が使う Jetty がクラスパス競合するため、Shadow jar で Javalin 側を **必ず relocation** すること。

---

### モジュール設計

#### Plugin Core

`onEnable()` で次の3つを起動する。

- Javalin Web サーバーを `127.0.0.1` バインドで起動（cloudflared 経由以外の直接アクセスを防ぐ）
- Log4j2 の `AbstractAppender` をプログラマティックに登録して Console Interceptor を有効化
- Bukkit Scheduler でメトリクスポーリングタスクを2秒周期で登録

`onDisable()` では Web サーバーを停止し、セッションを全消去する。

#### Web Server (Javalin 6)

ポートは `config.yml` で設定可能（デフォルト 8080）。静的ファイルは JAR 内の `/web/dist/` を Classpath Resource Manager で配信する。Javalin の `config.useVirtualThreads = true` を設定して Java 21 の Virtual Threads (Loom) を活用する。

#### Auth Module

TOTP は RFC 6238 準拠で ±30秒窓。**アカウントなし、単一共有 TOTP シークレット**（1〜4人が同じ QR を各自の認証アプリに登録）。

ログイン成功後、JWT（HS256、8時間有効）を **HttpOnly + Secure + SameSite=Strict Cookie** として発行する。JWT 秘密鍵は起動時に自動生成し `config.yml` 外のファイルに保存（`plugins/WARP/secret.key`、パーミッション 600 推奨）。

状態変更系 API（POST/DELETE）は **CSRF トークン（double-submit）** で保護する。CSRF トークンは非 HttpOnly Cookie としてフロントが読み取り、`X-CSRF-Token` ヘッダに付与する。

ログイン失敗は5回/10分でロックアウト。

#### Metrics Collector

Paper API から以下を収集し、2秒ごとに WebSocket でブロードキャストする。

- TPS: `server.getTPS()` → `double[]` {1min, 5min, 15min}
- MSPT: `server.getTickTimes()` → `long[]`（ナノ秒、直近100tick）→ 平均換算
- Player ping: `Player.getPing()` → int (ms)
- Memory: `Runtime.getRuntime()` → used / max

#### Console Interceptor

Log4j2 の `AbstractAppender` をプラグイン起動時に登録し、すべてのログイベントをブロッキングキュー経由で WebSocket スレッドに転送する。循環バッファは最大1000行。

#### Thread Safety（必須事項）

Paper API のほぼすべての操作は main thread からのみ呼び出せる。Javalin は独立したスレッドプールで動作するため、次のパターンで委譲する。

```java
// コマンド実行
server.getScheduler().runTask(plugin, () ->
    Bukkit.dispatchCommand(consoleSender, command));

// 戻り値が必要な場合
CompletableFuture<List<Player>> future =
    server.getScheduler().callSyncMethod(plugin,
        () -> new ArrayList<>(Bukkit.getOnlinePlayers()));
```

---

### 永続化設計

#### SQLite ファイル

`plugins/WARP/warp.db` に配置。プラグイン起動時に自動作成。

#### テーブルスキーマ

```sql
-- サーバーログ（Console Interceptor から書き込み）
CREATE TABLE IF NOT EXISTS logs (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    ts      INTEGER NOT NULL,          -- epoch millis
    level   TEXT    NOT NULL,          -- INFO / WARN / ERROR etc.
    logger  TEXT    NOT NULL,
    message TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_logs_ts    ON logs (ts DESC);
CREATE INDEX IF NOT EXISTS idx_logs_level ON logs (level);

-- チャット履歴
CREATE TABLE IF NOT EXISTS chat (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ts          INTEGER NOT NULL,
    player_uuid TEXT    NOT NULL,
    player_name TEXT    NOT NULL,
    message     TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chat_ts ON chat (ts DESC);

-- プレイヤー履歴（join/quit/death/teleport 等）
CREATE TABLE IF NOT EXISTS player_history (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ts          INTEGER NOT NULL,
    player_uuid TEXT    NOT NULL,
    player_name TEXT    NOT NULL,
    event_type  TEXT    NOT NULL,      -- join / quit / death / kick / etc.
    world       TEXT,
    x           REAL,
    y           REAL,
    z           REAL
);
CREATE INDEX IF NOT EXISTS idx_ph_uuid ON player_history (player_uuid);
CREATE INDEX IF NOT EXISTS idx_ph_ts   ON player_history (ts DESC);

-- 操作監査ログ（誰が何をしたか。個人特定せず IP のみ）
CREATE TABLE IF NOT EXISTS audit (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    ts        INTEGER NOT NULL,
    source_ip TEXT    NOT NULL,
    action    TEXT    NOT NULL,        -- ban / unban / kick / console / chat_send etc.
    detail    TEXT                     -- JSON string
);
CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit (ts DESC);
```

#### データ保持・ローテーション

| テーブル | デフォルト保持件数 | config.yml キー |
|---|---|---|
| logs | 100,000 件 | `storage.logs-max-rows` |
| chat | 50,000 件 | `storage.chat-max-rows` |
| player_history | 50,000 件 | `storage.history-max-rows` |
| audit | 無制限（手動クリアのみ） | — |

プラグイン起動時に古いレコードを `DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY ts DESC LIMIT ?)` で整理する。

#### BAN / IP-BAN

**Paper の `BanList` を唯一の SoT** として使用する。WARP は `getBanList(BanList.Type.NAME)` / `getBanList(BanList.Type.IP)` を読み書きする。BAN 操作は必ず Paper API 経由で実行し、`audit` テーブルに操作記録だけ残す。SQLite に独自 BAN テーブルは持たない。

#### ページング・検索クエリ方針

```sql
-- logs: level フィルター + キーワード検索 + ページング
SELECT * FROM logs
WHERE level = :level         -- 省略時は全件
  AND message LIKE :q        -- '%keyword%'、省略時は全件
ORDER BY ts DESC
LIMIT :pageSize OFFSET :offset;
```

`pageSize` デフォルト 100、最大 500。

---

### API 仕様

全 REST エンドポイントは **Cookie 認証**（HttpOnly JWT Cookie を自動付与）。`Authorization: Bearer` ヘッダは使わない。

**OpenAPI 仕様を SoT** として Javalin OpenAPI plugin で生成し、`openapi-typescript` でフロント側の型を自動生成する。

**REST**

```
GET  /api/status           → {tps, mspt, players, uptime, memory}
GET  /api/players          → [{name, uuid, ping, world, location}]
GET  /api/bans             → [{player, reason, expires, banner}]
POST /api/bans             → {player, reason, ?duration}        ※ CSRF トークン必要
DEL  /api/bans/:player                                           ※ CSRF トークン必要
GET  /api/ipbans           → [{ip, reason, expires}]
POST /api/ipbans           → {ip, reason}                       ※ CSRF トークン必要
DEL  /api/ipbans/:ip                                             ※ CSRF トークン必要
GET  /api/logs?page=&level=&q=
GET  /api/chat?page=&since=
POST /api/chat             → {message}                          ※ CSRF トークン必要
POST /api/console          → {command}                          ※ CSRF トークン必要
GET  /api/history?player=&page=
```

**Auth**

```
POST /auth/login           body: {"totp":"123456"}
                           → Cookie: jwt=...(HttpOnly+Secure+SameSite=Strict)
                              Cookie: csrf_token=...(非HttpOnly、JSから読める)
POST /auth/logout          → Cookie クリア
POST /auth/one-time        body: {"token":"<32char>"}
                           → 上記と同様の Cookie（緊急時用、5分有効）
```

Refresh Token は削除。Cookie の `Max-Age=28800`（8時間）で自然期限切れさせる。

**WebSocket** `/ws`

Cookie が自動付与されるため `?token=` クエリパラメータは不要。

サーバー → クライアント:

```json
{"type":"metrics","data":{"tps":[20.0,20.0,19.9],"mspt":1.23,"players":3,"memory":{"used":512,"max":1024}}}
{"type":"chat","data":{"player":"Steve","msg":"Hello","time":1749180000000}}
{"type":"log","data":{"level":"WARN","msg":"..","time":1749180000000}}
{"type":"console","data":{"line":"[INFO] Server started","time":1749180000000}}
{"type":"player_event","data":{"event":"join","player":"Steve","time":1749180000000}}
```

クライアント → サーバー:

```json
{"type":"console_in","cmd":"say Hello"}
{"type":"chat_send","msg":"[Admin] Hello"}
```

---

### 認証フロー

**初期セットアップ（一回のみ）**

OP が `/warp setup` を実行すると TOTP シークレットを生成し、`config.yml` に暗号化保存。コンソールに `otpauth://` URI を出力するので、Google Authenticator や Authy でスキャンする。1〜4人が**同じ QR**を各自の認証アプリに登録する。

**通常ログイン**

1. GET `/login` → React SPA を表示
2. 6桁 TOTP コードを入力
3. POST `/auth/login` → JWT を HttpOnly Cookie + CSRF トークン Cookie として発行
4. 以降の全リクエストはブラウザが Cookie を自動付与
5. 状態変更系リクエストは `X-CSRF-Token` ヘッダを追加（JS が CSRF Cookie から読み取り付与）

**緊急時**

OP が `/warp token` を実行 → ランダム32文字トークン生成（有効5分） → `/auth/one-time` で Cookie 取得。

**Plugin コマンド一覧**

メインコマンドは `/warp`。エイリアス `/waras-admin-runtime-panel` も同一動作で使用可能（plugin.yml の `aliases` で定義）。

| コマンド | 動作 |
|---|---|
| `/warp setup` | TOTP 登録（初回） |
| `/warp status` | パネル状態確認 |
| `/warp reload` | config 再読込 |
| `/warp token` | 緊急ワンタイムトークン発行 |

---

### セキュリティ設計（多層防御）

```
Layer 1 — Cloudflare
  - Tunnel（ポート非公開、8080 は 127.0.0.1 バインドのみ）
  - Zero Trust Access（オプション: メールアドレス制限）
  - WAF / DDoS 保護

Layer 2 — WARP Auth
  - TOTP RFC 6238（±30秒窓）
  - JWT HS256、8時間期限（HttpOnly + Secure + SameSite=Strict Cookie）
  - 秘密鍵: 起動時自動生成、plugins/WARP/secret.key（パーミッション 600）
  - ログイン試行制限: 5回/10分でロックアウト
  - CSRF: double-submit cookie パターン

Layer 3 — API Security
  - 全エンドポイント Cookie JWT 必須
  - 状態変更系エンドポイントは X-CSRF-Token ヘッダ必須
  - CORS: config.yml で許可 Origin を限定
  - Command filter: config.yml でブロックリスト定義可能
  - Input validation: Javalin before handler で実施
  - 操作監査: 全 POST/DELETE を audit テーブルに記録
```

---

### フロントエンド設計

**ページ構成**

- `/login` — TOTP 入力フォーム
- `/` (Dashboard) — メトリクスカード + リアルタイムグラフ
- `/terminal` — xterm.js コンソール
- `/players` — オンラインプレイヤーテーブル（ping 付き）
- `/chat` — チャット履歴 + Admin 送信フォーム
- `/bans` — BAN / IP-BAN 管理テーブル
- `/logs` — ログビューア（検索・レベルフィルター）
- `/history` — プレイヤー履歴タイムライン

**Dashboard グラフ（ApexCharts）**

- TPS Line Chart: 1min / 5min / 15min の3系列、60秒ローリングウィンドウ
- MSPT Area Chart: 50ms 警告ライン表示
- Player Count Bar: 時系列
- Memory Donut: used / free

**テーマ（ポートフォリオブランドカラー統一）**

```typescript
const theme = {
  bg: {
    primary:   '#0a0e27',
    secondary: '#111828',
    card:      '#141c35',
  },
  accent: {
    primary: '#10b981',  // emerald
    glow:    '#34d399',
  },
}
// TPS < 15 → amber 警告、TPS < 10 → red 緊急
```

**API クライアント層**

OpenAPI spec から `openapi-typescript` で型を自動生成。`fetch` ラッパが `X-CSRF-Token` ヘッダを自動付与する。axios は使わず、`fetch` + 生成型で完結させる。

```
frontend/src/lib/
  api.ts     ← fetch ラッパ（CSRF ヘッダ自動付与）
  api-types.ts ← openapi-typescript で自動生成（手書き禁止）
  ws.ts      ← WebSocket 管理
```

---

### ディレクトリ構造

```
warp/
├── plugin/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/dev/warasugi/warp/
│       │   ├── WarpPlugin.java
│       │   ├── config/PanelConfig.java
│       │   ├── db/
│       │   │   ├── DatabaseManager.java      ← SQLite 初期化・接続管理
│       │   │   ├── LogRepository.java
│       │   │   ├── ChatRepository.java
│       │   │   ├── HistoryRepository.java
│       │   │   └── AuditRepository.java
│       │   ├── web/
│       │   │   ├── WebServer.java
│       │   │   ├── middleware/
│       │   │   │   ├── AuthMiddleware.java   ← Cookie JWT 検証
│       │   │   │   └── CsrfMiddleware.java
│       │   │   ├── handlers/
│       │   │   │   ├── StatusHandler.java
│       │   │   │   ├── PlayerHandler.java
│       │   │   │   ├── BanHandler.java
│       │   │   │   ├── ChatHandler.java
│       │   │   │   ├── ConsoleHandler.java
│       │   │   │   └── LogHandler.java
│       │   │   └── ws/AdminWsHandler.java
│       │   ├── auth/
│       │   │   ├── TotpManager.java
│       │   │   ├── JwtManager.java
│       │   │   └── RateLimiter.java
│       │   ├── metrics/MetricsCollector.java
│       │   ├── console/WebSocketAppender.java
│       │   └── commands/WarpCommand.java
│       └── resources/
│           ├── plugin.yml
│           ├── config.yml
│           └── web/          ← Vite ビルド成果物を配置
└── frontend/
    ├── package.json
    ├── vite.config.ts
    ├── tailwind.config.ts
    └── src/
        ├── lib/
        │   ├── api.ts        ← fetch ラッパ + CSRF 自動付与
        │   ├── api-types.ts  ← openapi-typescript 自動生成（手書き禁止）
        │   └── ws.ts         ← WebSocket 管理
        ├── pages/
        │   ├── Login.tsx
        │   ├── Dashboard.tsx
        │   ├── Terminal.tsx
        │   ├── Players.tsx
        │   ├── Chat.tsx
        │   ├── Bans.tsx
        │   ├── Logs.tsx
        │   └── History.tsx
        └── components/
            ├── charts/
            │   ├── TpsChart.tsx
            │   ├── MsptChart.tsx
            │   └── PlayerChart.tsx
            └── layout/Sidebar.tsx
```

---

### config.yml スキーマ

```yaml
server:
  host: "127.0.0.1"
  port: 8080
  cors-origins:
    - "https://admin.warasugi.com"

auth:
  totp-issuer: "WARP"
  totp-secret: ""           # /warp setup で自動生成
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

JWT 秘密鍵は `config.yml` に持たず、`plugins/WARP/secret.key` に自動生成・保存する。

---

### build.gradle.kts（主要部分）

```kotlin
plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.3"
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    implementation("io.javalin:javalin:6.4.0")
    implementation("io.javalin:javalin-openapi-plugin:6.4.0")   // OpenAPI 生成
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("dev.samstevens.totp:totp:1.7.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")           // SQLite
}

tasks.shadowJar {
    // Paper との Jetty クラス競合を回避するため必須
    relocate("io.javalin", "dev.warasugi.warp.libs.javalin")
    relocate("io.jsonwebtoken", "dev.warasugi.warp.libs.jwt")
    relocate("com.fasterxml.jackson", "dev.warasugi.warp.libs.jackson")
    mergeServiceFiles()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
```

---

### Cloudflare Tunnel デプロイ手順

```bash
# 1. 認証
cloudflared tunnel login

# 2. トンネル作成
cloudflared tunnel create warp

# 3. ~/.cloudflared/config.yml を作成
cat << 'EOF' > ~/.cloudflared/config.yml
tunnel: <トンネルUUID>
credentials-file: /root/.cloudflared/<UUID>.json
ingress:
  - hostname: admin.warasugi.com
    service: http://localhost:8080
  - service: http_status:404
EOF

# 4. DNS レコード登録
cloudflared tunnel route dns warp admin.warasugi.com

# 5. systemd サービス登録・起動
cloudflared service install
systemctl enable --now cloudflared
```

WARP の Javalin サーバーは `127.0.0.1:8080` にバインドするため、cloudflared を経由しない直接アクセスはできない。

---

### 実装順序（推奨）

1. **Plugin Core** — `onEnable()` / `onDisable()` の骨格、コマンド登録
2. **Auth Module** — TOTP セットアップ、JWT 発行/検証（HttpOnly Cookie 版）
3. **Persistence Layer** — SQLite 初期化、各 Repository（logs/chat/history/audit）
4. **Web Server** — Javalin 起動、Cookie 認証ミドルウェア、CSRF ミドルウェア、基本的な REST ルーティング
5. **Metrics Collector** — Paper API からのデータ取得、WS ブロードキャスト
6. **Console Interceptor** — Log4j2 Appender 登録、WS 転送、SQLite 書き込み
7. **Frontend** — Vite プロジェクト作成、OpenAPI 型生成、Dashboard から順に実装、JAR へのバンドル

---

### 注意点まとめ

- `server.getTickTimes()` は `long[]`（ナノ秒）。MSPT は `Arrays.stream(server.getTickTimes()).average().getAsDouble() / 1_000_000.0` で計算する（`getAverageTickTime()` は Paper の public API に存在しない可能性があるため直接使わない）
- Log4j2 Appender の登録タイミングはプラグインの load フェーズで行う必要がある場合あり——`onEnable()` で十分なことが多いが、他プラグインとの依存関係に注意
- GraalVM JVM モードでは reflection は通常通り動作するが、Shadow jar で relocate した後のクラス名でアノテーションやリフレクションを扱う際は注意が必要
- Cloudflare Zero Trust Access でメールアドレス認証を追加すると防衛がさらに1層加わる（ただし設定は別途 Cloudflare ダッシュボードで行う）
- SQLite は WAL モードを有効化すること（`PRAGMA journal_mode=WAL`）——読み取りと書き込みを並行実行できる
- Cookie の `SameSite=Strict` は Cloudflare Tunnel 経由でも有効。ただし初回アクセスがリダイレクト経由の場合はトップレベルナビゲーション扱いで Cookie が付与されないケースに注意——`Lax` への変更を検討しても良い
