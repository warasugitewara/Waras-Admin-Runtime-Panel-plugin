# WARP × SchemDepot 連携 設計書

**日付:** 2026-09-04
**対象:** WARP (Waras-Admin-Runtime-Panel) v1.2.0 以降
**関連:** SchemDepot v1.0.0 (`C:\Users\waras\Documents\SchemDepot`, Poteto-Groove/SchemDepot)

---

## 1. 目的

WARP のダッシュボードから SchemDepot のアセットレジストリを閲覧できるようにする。具体的には以下を確認できる状態を作る。

- 登録済みアセットの一覧（名前 / 作者 / 登録日時 / 寸法 / ファイル容量）
- 全体の総アセット数と総容量
- 作者ごとの登録件数・占有容量・占有率
- レジストリとファイル実体の不整合（**検出・表示のみ**）

SchemDepot 側の設計書は「Web UI」「REST API」を明確に**非目標**と宣言している（`docs/SchemDepot_DESIGN.md` の Non-Goals）。したがって UI を持つのは WARP 側の責務とし、SchemDepot 本体には手を入れない。

---

## 2. 絶対制約 — 既存アセットを絶対に失わない

本連携の最上位要件。ユーザーからの明示的な指示であり、他のあらゆる設計判断に優先する。

以下を設計・実装・レビューの不変条件とする。

1. **SchemDepot の SQLite に対して書き込み系 SQL を一切書かない。** 発行してよいのは `SELECT` と読み取り専用 `PRAGMA`（`user_version` / `query_only`）のみ。`INSERT` / `UPDATE` / `DELETE` / `CREATE` / `DROP` / `ALTER` / `VACUUM` はソースコード上に存在させない。
2. **接続は読み取り専用で開く。** `SQLiteConfig.setReadOnly(true)`（= `SQLITE_OPEN_READONLY`）で開き、さらに接続確立直後に `PRAGMA query_only = ON` を発行する。前者は OS/オープンモードによる防御、後者は SQLite エンジン自身による文レベルの拒否で、二重の安全弁とする。
3. **読み書きモードへのフォールバックを行わない。** 読み取り専用で開けなかった場合は「利用不可」として degrade する（§8）。読み書きで開くと WAL チェックポイントによって本体 DB ファイルへの書き込みが発生しうるため、たとえデータ喪失に直結しなくても行わない。
4. **`.schem` ファイルに対しては `Files.size()` と `Files.exists()` 相当の読み取りのみ。** 削除・移動・リネーム・書き込み・truncate を行う API をコード上に存在させない。
5. **不整合検出は報告のみ。** 孤児ファイル（DB に行が無い `.schem`）を検出しても削除しない。画面に一覧を表示するだけとし、実際の削除は `/sd` コマンド側の責務に残す。
6. **書き込み系 HTTP エンドポイントを作らない。** `/api/schemdepot/*` は `GET` のみ。`POST` / `DELETE` / `PUT` / `PATCH` を定義しない。

### 検証方法

- 実装完了時に `plugin/src/main/java/dev/warasugi/warp/schemdepot/` 配下を grep し、`INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|VACUUM|Files.delete|Files.move|Files.write|newOutputStream` が0件であることを確認する。この grep 自体をテストとして自動化する（§11）。
- 統合テストで、一時ディレクトリに作った SchemDepot 相当の DB とファイル群に対して全エンドポイントを叩き、実行前後で「DB ファイルのバイト列」と「`.schem` ファイルの一覧・サイズ」が変化しないことを assert する。

---

## 3. スコープ

### 含む

- SchemDepot データフォルダの検出（存在しなければ機能ごと非表示）
- SQLite レジストリの読み取りと `.schem` の容量集計
- 3本の `GET` API と、専用ページ + ダッシュボードのサマリーカード
- 不整合（欠損ファイル / 孤児ファイル）の検出・表示

### 含まない

- WARP からのアセット追加・リネーム・削除（`/sd` コマンドの責務）
- スキマティックのプレビュー・3D 表示・ダウンロード
- SchemDepot 本体の改造
- SchemDepot 側の 26.1.2+ 要件とサーバーバージョンの整合（§12 で別途扱う）

### 別フェーズ

- WARP の Java → Kotlin 移行（§13）

---

## 4. アーキテクチャ

WARP は SchemDepot に対して**コンパイル依存も `plugin.yml` の `depend` / `softdepend` も持たない**。参照するのはファイルシステム上のパスだけであり、クラスを1つも共有しない。

```
WARP プラグイン (同一 JVM / 同一サーバー)
  └── SchemDepotReader
        ├─ read  plugins/SchemDepot/config.yml     … 保存先ディレクトリ名の取得
        ├─ read  plugins/SchemDepot/assets.db      … read-only JDBC, SELECT のみ
        └─ stat  plugins/SchemDepot/schematics/*   … Files.size() のみ
```

この構成の帰結:

- SchemDepot が**入っていない** → フォルダが無い → 機能ごと非表示。WARP は何事もなく起動する。
- SchemDepot が**入っているが無効化されている** → ファイルは残っているので閲覧できる（むしろ有用）。
- SchemDepot が**現行サーバーにロードできないバージョン** → WARP の動作に一切影響しない。
- クラスローダを跨がないため、Paper の `PluginRemapper` や `join-classpath` の影響を受けない。

### 新規クラス

`plugin/src/main/java/dev/warasugi/warp/schemdepot/`

| クラス | 責務 |
|---|---|
| `SchemDepotLocator` | データフォルダの検出と `config.yml` からのパス解決。結果を `SchemDepotPaths` として返す |
| `SchemDepotReader` | read-only 接続の確立、スキーマバージョン検査、`SELECT` 実行、ファイル容量の突き合わせ。TTL キャッシュを保持 |
| `SchemDepotSnapshot` | 1回の読み取り結果（アセット一覧 + 集計 + 不整合）を保持する不変オブジェクト |
| `SchemDepotHandler` | `RouteRegistrar` 実装。3本の `GET` を登録 |

DTO は既存 `PluginHandler` に倣い、ハンドラ内の `record` として定義する。

### 既存コードへの変更

- `WarpPlugin#initWeb` の `List.of(...)` に `schemDepotHandler` を1行追加
- `WarpPlugin#initWeb` 内で `SchemDepotReader` を1インスタンス生成（`getDataFolder().getParentFile()` を `plugins/` として渡す）
- `build.gradle.kts` は**変更なし**（`sqlite-jdbc` は既に依存にある）
- `plugin.yml` は**変更なし**

---

## 5. 検出とバージョン互換

### 5.1 パス解決

1. `plugins/SchemDepot/` が存在しなければ → 利用不可（理由: `not_installed`）
2. `plugins/SchemDepot/config.yml` があれば `YamlConfiguration` で読み、`storage.database-file` / `storage.schematics-directory` を取得する。無い場合・空の場合は既定値 `assets.db` / `schematics` を使う
3. 取得した値を SchemDepot 本体と同じ規則で検証する（パス区切り `/` `\` `:` を含まない、`.` `..` でない、単一パス要素であること）。逸脱していれば既定値にフォールバックし WARNING を出す。これは SchemDepot 側 `SchemDepotConfig.requireNonBlankPath` と同じ制約であり、WARP がディレクトリ外を読みに行かないための防御でもある
4. `assets.db` が存在しなければ → 利用不可（理由: `no_database`）

### 5.2 スキーマバージョン

SchemDepot は `PRAGMA user_version` でスキーマ版を管理しており、v1.0.0 時点の値は **1**。

- `user_version == 1` → 正常
- `user_version > 1` → **利用不可（理由: `schema_too_new`）**。列構成が変わっている可能性があるため、推測で読まずに機能を落とす。画面には「SchemDepot のスキーマ版 N は本バージョンの WARP が未対応です」と表示する
- `user_version == 0` → 未マイグレーション。利用不可（理由: `not_migrated`）

WARP が期待する v1 のスキーマ（`DatabaseMigration.kt` より）:

```sql
CREATE TABLE assets (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    normalized_name TEXT NOT NULL UNIQUE,
    author_uuid     TEXT NOT NULL,
    author_name     TEXT NOT NULL,
    created_at      INTEGER NOT NULL,   -- epoch millis
    updated_at      INTEGER NOT NULL,   -- epoch millis
    size_x          INTEGER NOT NULL,
    size_y          INTEGER NOT NULL,
    size_z          INTEGER NOT NULL,
    schematic_file  TEXT NOT NULL UNIQUE
)
```

容量を表す列は存在しない。したがって容量は `schematics/<schematic_file>` を実測して得る。

### 5.3 接続文字列

```java
SQLiteConfig cfg = new SQLiteConfig();
cfg.setReadOnly(true);
Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, cfg.toProperties());
try (Statement st = conn.createStatement()) {
    st.execute("PRAGMA query_only = ON");
}
```

接続は毎回の読み取りで開いて閉じる（`try-with-resources`）。TTL キャッシュにより実際の接続頻度は低く、長命な接続を持ち続けて SchemDepot 側の WAL チェックポイントを妨げるより望ましい。

### 5.4 WAL に関する既知の制約

SchemDepot は `PRAGMA journal_mode = WAL` で DB を開く。SQLite の WAL は `-shm` 共有メモリファイルを必要とし、読み取り専用オープンではプロセスが `-shm` に対する書き込み権限（または `-shm` が無い場合はディレクトリへの書き込み権限）を持つ必要がある。

WARP と SchemDepot は同一サーバープロセス = 同一 OS ユーザーで動くため、通常この条件は満たされる。満たされずオープンに失敗した場合は §2-3 のとおり読み書きへ昇格せず、利用不可（理由: `open_failed`）として degrade し、原因を1度だけ WARNING ログに出す。

---

## 6. API 仕様

すべて `GET`。既存の `AuthMiddleware` / `CsrfMiddleware` の配下に入るため、認証は既存経路をそのまま継承する。

### `GET /api/schemdepot/status`

```jsonc
{ "available": true }
// または
{ "available": false, "reason": "not_installed" }
```

`reason` の値: `not_installed` | `no_database` | `not_migrated` | `schema_too_new` | `open_failed` | `read_failed`

### `GET /api/schemdepot/assets`

クエリ: `page`（0 始まり、既存 `PagingParams` に準拠）/ `q`（名前・作者名の部分一致、大文字小文字無視）/ `sort`（`created` | `name` | `size` | `author`、既定 `created`）/ `order`（`asc` | `desc`、既定 `desc`）

```jsonc
{
  "total": 128,
  "page": 0,
  "items": [
    {
      "id": "4a42e070-ae41-4e5a-98e2-d311dd28c59a",
      "name": "MyHouse",
      "authorUuid": "…",
      "authorName": "warasugi",
      "createdAt": 1756900000000,
      "updatedAt": 1756900000000,
      "sizeX": 32, "sizeY": 18, "sizeZ": 24,
      "volume": 13824,
      "bytes": 48231,
      "fileMissing": false
    }
  ]
}
```

`bytes` はファイルが無い場合 `0`、`fileMissing: true` とする。

### `GET /api/schemdepot/stats`

```jsonc
{
  "totalCount": 128,
  "totalBytes": 5242880,
  "authorCount": 7,
  "authors": [
    { "uuid": "…", "name": "warasugi", "count": 42, "bytes": 2097152, "share": 0.4 }
  ],
  "integrity": {
    "missingFiles": [ { "id": "…", "name": "MyHouse", "file": "….schem" } ],
    "orphanFiles": [ { "file": "….schem", "bytes": 12345 } ],
    "orphanBytes": 12345
  }
}
```

`authors` は `bytes` 降順。`share` は `bytes / totalBytes`（`totalBytes == 0` なら `0`）。

`orphanFiles` は `schematics/` 内の `.schem` のうち、どの行の `schematic_file` にも一致しないもの。**表示のみで削除は行わない。**

---

## 7. フロントエンド

### 新規

- `frontend/src/pages/SchemDepot.tsx`
  - 上段: サマリーカード3枚（総アセット数 / 総容量 / 作者数）
  - 中段: 作者別テーブル（作者名・件数・容量・占有率バー）。`bytes` 降順
  - 下段: アセット一覧テーブル（検索ボックス、列ソート、ページング）。`fileMissing` の行は警告色でマークする
  - 不整合がある場合のみ、下段の下に「不整合」セクション（欠損ファイル / 孤児ファイル）を出す。**削除ボタンは置かない**

### 変更

- `frontend/src/lib/api.ts` — `schemDepotStatus` / `schemDepotAssets` / `schemDepotStats` を追加
- `frontend/src/App.tsx` — `/schemdepot` ルートを追加
- `frontend/src/components/layout/Sidebar.tsx` — `status.available` が true のときのみ項目を出す
- `frontend/src/pages/Dashboard.tsx` — available 時のみ小カード1枚（総数・総容量、クリックで `/schemdepot` へ）

`status` は Sidebar と Dashboard の両方で必要なので、`useSchemDepotStatus` フックを1つ作り `Layout` レベルで1回だけ取得して共有する。

### 容量の表示

バイト数は `formatBytes`（B / KiB / MiB / GiB, 小数1桁）で整形する。この関数は `frontend/src/lib/format.ts` に新規作成し、この用途に限定する。

---

## 8. エラー処理と degrade

いかなる失敗も **WARP 本体の起動・動作を妨げない**。

| 状況 | 挙動 |
|---|---|
| フォルダ / DB が無い | `status.available = false`、ログ出力なし（正常系） |
| スキーマ版が不一致 | `available = false` + `reason`、起動時に1度 INFO |
| read-only オープン失敗 | `available = false` + `reason: open_failed`、1度だけ WARNING（スタックトレース付き） |
| `SELECT` が例外 | `available = false` + `reason: read_failed`、WARNING。キャッシュは更新せず前回値も破棄する |
| `.schem` の `Files.size()` が失敗 | その1件を `bytes: 0, fileMissing: true` として扱い、全体は成功させる |
| `schematics/` が読めない | 容量集計を全件 `0` として一覧は返し、`integrity` に読み取り不能である旨を残す |

「1度だけ」の警告は理由が変化したときに再度出す（永久に沈黙させない）。

---

## 9. パフォーマンス

- `SchemDepotReader` は **TTL 30秒**のスナップショットキャッシュを持つ。3本の API はすべて同一スナップショットから応答する
- スナップショット生成は「`SELECT * FROM assets`（全件）+ `schematics/` の `Files.walk` 1回」。数千件規模を想定し、これで十分軽い。件数が万を超えるようなら SQL 側ページングへ切り替えるが、現時点では YAGNI
- Bukkit API を一切呼ばないため `callSyncMethod` は不要。Javalin の仮想スレッド上でそのまま実行してよい（CLAUDE.md の「Javalin Handler から直接 Bukkit API を呼ばない」に抵触しない）
- キャッシュ更新は単一の `ReentrantLock` で保護し、同時リクエストが重複読み取りを走らせないようにする

---

## 10. セキュリティ

- エンドポイントは既存の `AuthMiddleware` 配下に入るため、未認証アクセスは既存経路で 401 になる
- **ファイルシステムのパスをレスポンスに含めない。** 返すのは `schematic_file`（ファイル名のみ）であり、絶対パスやデータフォルダの位置は出さない。SchemDepot 設計書 §21-8 の方針に揃える
- `author_uuid` は返す（作者別集計のキーとして必要、かつ管理者専用画面のため）
- `config.yml` 由来のディレクトリ名は §5.1-3 の検証を通すため、`plugins/SchemDepot/` の外を読みに行くことはない
- 監査ログ（`AuditRepository`）には記録しない。読み取り専用の閲覧操作であり、他の `GET` 系ハンドラ（`LogHandler` 等）も記録していないため整合させる

---

## 11. テスト方針

既存の `plugin/src/test/java/` に合わせ JUnit 5 で書く。Bukkit を必要としない純粋なクラスに切り出しているため、モックなしでテストできる。

1. **`SchemDepotLocatorTest`** — フォルダ無し / `config.yml` 無し / 不正なディレクトリ名（`../`, 絶対パス）でのフォールバック
2. **`SchemDepotReaderTest`** — 一時ディレクトリに v1 スキーマの DB と `.schem` を作り、集計結果・作者別集計・欠損 / 孤児検出を検証。`user_version` を 0 / 2 に変えた場合の degrade も検証
3. **`SchemDepotReadOnlyTest`（絶対制約の回帰テスト）**
   - a. 全エンドポイント実行の前後で DB ファイルのハッシュと `.schem` の一覧・サイズが不変であること
   - b. `schemdepot` パッケージのソースに書き込み系 SQL / ファイル変更 API のトークンが1つも現れないことを静的に検査
4. **`SchemDepotHandlerTest`** — `javalin-testtools`（既に依存にある）で 3 本の `GET` のレスポンス形状と、利用不可時の `status` を検証

---

## 12. サーバーバージョンについて（連携とは独立）

- SchemDepot v1.0.0 は **Paper / Purpur 26.1.2+** を要求する（`api-version: '26.1'`, Java 25 toolchain）
- WARP は `paper-api 1.21.1-R0.1-SNAPSHOT` / Java 21 でビルドされ、現在サーバー上で正常動作している

本設計は WARP と SchemDepot の間にクラス依存を作らないため、**両者のターゲットバージョンが揃っていなくても WARP 側は成立する**。したがって本フェーズではサーバーバージョンにも `build.gradle.kts` にも手を入れない（動いているものを触らない）。

`paper-api` を稼働サーバーと一致させる更新（`1.21.11-R0.1-SNAPSHOT`, Maven メタデータで実在確認済み）は、`Bukkit.getBanList` や `PluginDescriptionFile` の非推奨対応を伴うため、§13 の Kotlin 移行フェーズにまとめて実施する。

---

## 13. 別フェーズ: Java → Kotlin 移行（本設計の対象外）

本連携が動作確認できた後に、別の設計・計画として扱う。現時点で分かっている前提のみ記録する。

- 対象は `plugin/src/main/java/` の 33 ファイル / 2,025 行と、`plugin/src/test/java/` の 8 ファイル / 360 行
- WARP が使う Bukkit API 面は狭い（`getScheduler` 16 / `getBanList` 6 / `getPluginManager` 4 / `PluginDescriptionFile` 2 / `getOnlinePlayers` 2 / `dispatchCommand` 2 / `getTPS` 1 / `broadcast` 1）
- `javalin-openapi-plugin` と `openapi-annotation-processor` は依存に宣言されているが**実際には未使用**（`@OpenApi` 注釈は0件、`openapi-typescript` の出力 `api-types.ts` も未生成）。したがって Kotlin 移行にあたって kapt / KSP は不要。この未使用依存を外すか、逆に OpenAPI を正式に SoT として整備するかは移行フェーズで決める
- 既存テスト 8 ファイルを回帰チェックとして使い、Java と Kotlin を共存させながら段階的に移行できる（Gradle の `kotlin-jvm` プラグインは `src/main/java` と `src/main/kotlin` の混在を許容する）

---

## 14. 未決事項

なし。実装計画の作成に進んでよい状態。
