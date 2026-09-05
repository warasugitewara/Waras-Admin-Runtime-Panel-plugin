# SchemDepot 連携 — 実機での「消えていない」確認手順

WARP は SchemDepot のデータを**読むだけ**で、書き込みは一切しない。
それをコード側の保証（読み取り専用接続 + `PRAGMA query_only` + 禁止語の静的検査テスト）だけでなく、
**運用者が自分の目で確かめられる**ようにするための手順。

かかる時間は 5 分程度。SchemDepot のアセットが多い場合はハッシュ計算に少し時間がかかる。

---

## 前提

- SchemDepot が `plugins/SchemDepot/` に導入済みで、アセットが 1 件以上ある
- WARP が起動していて、管理画面にログインできる
- サーバーに SSH で入れて `sha256sum` が使える（Debian/Ubuntu なら標準で入っている）

対象ファイルは 2 種類:

| 対象 | 既定のパス |
|---|---|
| アセット DB | `plugins/SchemDepot/assets.db` |
| スキマティック本体 | `plugins/SchemDepot/schematics/*.schem` |

`plugins/SchemDepot/config.yml` で `storage.database-file` /
`storage.schematics-directory` を変えている場合はそちらのパスに読み替えること。

---

## 手順

### 1. 触る前のハッシュを取る

サーバーの SSH セッションで:

```sh
cd /path/to/server/plugins/SchemDepot

# DB 本体と、SQLite が使う WAL/ジャーナルもまとめて
sha256sum assets.db assets.db-wal assets.db-shm 2>/dev/null > /tmp/schemdepot-before.txt

# スキマティック全件
find schematics -type f -name '*.schem' -print0 \
  | sort -z \
  | xargs -0 sha256sum >> /tmp/schemdepot-before.txt

wc -l /tmp/schemdepot-before.txt
cat /tmp/schemdepot-before.txt
```

`wc -l` の行数が「DB + アセット数」と合っていることを確認する。

### 2. WARP 側で一通り触る

ブラウザで管理画面を開き、**読み取り経路を全部通す**:

1. ダッシュボードを開く（SchemDepot サマリーカードが出る = `/api/schemdepot/stats` を叩いた）
2. サイドバーの **SchemDepot** をクリック（`/api/schemdepot/assets` + `/api/schemdepot/stats`）
3. 検索ボックスに何か入力する
4. 並び替えを切り替える（名前 / 作者 / サイズ / 更新日時、昇順・降順とも）
5. ページを 2 ページ目以降まで送る
6. 作者別の内訳と整合性セクションを表示する
7. **ブラウザを 40 秒以上放置してからページを再読み込みする**
   （サーバー側キャッシュの TTL は 30 秒。期限切れ後の再読み込み経路も通すため）

### 3. 触った後のハッシュを取って比較する

```sh
cd /path/to/server/plugins/SchemDepot

sha256sum assets.db assets.db-wal assets.db-shm 2>/dev/null > /tmp/schemdepot-after.txt
find schematics -type f -name '*.schem' -print0 \
  | sort -z \
  | xargs -0 sha256sum >> /tmp/schemdepot-after.txt

diff /tmp/schemdepot-before.txt /tmp/schemdepot-after.txt && echo "一致: 変更なし"
```

**期待する結果: `diff` が何も出力せず `一致: 変更なし` が表示される。**

### 4. ファイル数も直接数える

ハッシュ一致に加えて、件数そのものも見ておく:

```sh
find /path/to/server/plugins/SchemDepot/schematics -type f -name '*.schem' | wc -l
```

作業前後で同じ数であること。WARP の SchemDepot ページに出ている「アセット数」とも突き合わせる
（DB 行数とファイル数が食い違う場合は WARP の「整合性」セクションに
 *DB にあるがファイルが無い* / *ファイルはあるが DB に無い* として表示される。
 WARP はこれを**報告するだけ**で、削除は一切しない）。

---

## 差分が出た場合の切り分け

`diff` に差が出ても、まず WARP を疑う前に以下を確認する:

- **`assets.db-wal` / `assets.db-shm` だけが変わった** → SchemDepot 本体か他プラグインが書き込んだ。
  WARP は読み取り専用接続なので WAL に書けない。手順 2 の最中に誰かがアップロード/削除しなかったか確認する。
- **`assets.db` 本体または `.schem` が変わった** → 手順 2 の間にサーバー上で
  SchemDepot のコマンド（アップロード・削除）が実行されていないか、
  他の管理ツールが動いていないかを確認する。確実を期すならプレイヤーが誰もいない状態で再測定する。

再測定しても WARP を触っただけで差分が出るなら、それは**バグとして報告に値する**。
その場合は `logs/latest.log` の `SchemDepot` を含む行を添えて知らせてほしい。

---

## 併せて確認できること（任意）

WARP が SchemDepot の DB を読み取り専用でしか開いていないことは、
サーバー稼働中に OS 側からも見える:

```sh
# WARP/Paper のプロセスが assets.db をどう開いているか
lsof -p "$(pgrep -f paper)" 2>/dev/null | grep -i assets.db
```

WARP 側の接続は SQLite の読み取り専用オープンなので、
書き込み用の `-journal` / `-wal` をこのプロセスが新規作成することはない。

---

## 補足: コード側での保証

運用手順とは別に、リポジトリ側では以下が常時効いている。

- `SchemDepotReader` の接続は `SQLiteConfig.setReadOnly(true)` **かつ**
  接続直後に `PRAGMA query_only = ON` の二重防御。
  読み取り専用で開けなかった場合は読み書きへ切り替えず、`OPEN_FAILED` として機能を落とす。
- `SchemDepotReadOnlyTest` が
  `INSERT` / `UPDATE` / `DELETE` / `DROP` / `CREATE` / `ALTER` / `VACUUM` /
  `executeUpdate` / `Files.delete` / `Files.move` / `Files.write` / `Files.copy` /
  `Files.newOutputStream` / `setReadOnly(false)` の出現を
  `schemdepot` パッケージ全体に対して静的に検査し、1 つでもあればビルドを落とす。
- 同テストが、実際に DB を読ませた前後で DB ファイルと `.schem` の SHA-256 が
  一致することもテストコード内で検証している（本手順の自動化版）。
- `/api/schemdepot/*` は `GET` のみ。`POST`/`PUT`/`DELETE` のルートは存在しない。
