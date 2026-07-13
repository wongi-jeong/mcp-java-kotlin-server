# MCP PDF 検索サーバー (Java/Kotlin)

PDF をアップロードすると自動で埋め込みを行い、**意味ベースのベクトル検索**を提供する MCP(Model Context Protocol)サーバーです。
ファイルの入口は MinIO(S3 互換)に一本化されており、バケットに PDF を置くだけで Webhook 経由で自動的にインデックスされます。

> 🇰🇷 한국어版: [README.md](README.md) ・ 本番デプロイ(ドメイン+HTTPS): [DEPLOYMENT.md](DEPLOYMENT.md)

## 構成

| サービス | 役割 |
|---|---|
| **mcp-server** (Kotlin/Ktor) | MCP サーバー。`http://localhost:3001/mcp`(Streamable HTTP)。ツール: `pdf_search`, `echo`, `add` |
| **minio** (S3 互換) | PDF ストレージ。`pdfs` バケットへの追加/削除時に Webhook で mcp-server にイベント送信 |
| **ollama** | 埋め込み生成(`nomic-embed-text`、768次元) |
| **pgvector** (PostgreSQL) | 埋め込みベクトルの保存・検索(HNSW インデックス) |
| **caddy** | (本番専用)実ドメインの TLS リバースプロキシ。ローカルでは未使用 |

**インデックスの流れ**: `pdfs` バケットに PDF をアップロード → MinIO Webhook → mcp-server が認証情報でダウンロード → チャンク分割 → Ollama で埋め込み → pgvector に保存。削除するとベクトルも自動でクリーンアップされます。(クライアントから直接呼ぶ保存ツールはありません。)

## 前提条件

- **Docker** + **Docker Compose v2**(`docker compose`)
- 空きディスク(埋め込みモデル約300MB + イメージ)/ 推奨 RAM 4GB 以上(Ollama は CPU 推論)
- ローカルで使用するポート: `3001`(MCP)、`9000`/`9001`(MinIO、ループバック)、`5433`(Postgres)

## クイックスタート(ローカル、ドメイン不要)

```bash
bash scripts/quickstart.sh
```

このスクリプトが自動で: `.env` 生成(ランダム認証情報)→ ビルド・起動 → サンプル PDF アップロード → スモークテストまで実行します。
(初回はモデルのダウンロードで数分かかる場合があります。)

完了後:

- **MCP エンドポイント**: `http://localhost:3001/mcp`
- **MinIO コンソール**: `http://localhost:9001`(ID `minioadmin` / PW は `.env` の `MINIO_ROOT_PASSWORD`)

### 手動で行う場合

```bash
cp .env.example .env      # 値を編集(本番では必ずパスワードを変更)
docker compose up -d --build pgvector ollama minio minio-init mcp-server
```

ローカルでは `docker-compose.override.yml` が自動マージされ、MinIO を `localhost` に公開し caddy は起動しません。

## 使い方

### 1) PDF を追加

MinIO コンソール(`http://localhost:9001`)で `pdfs` バケットに PDF をドラッグ&ドロップすると自動インデックスされます。
スコープ付きアカウントを使う場合は `.env` の `pdf-uploader` キーでログイン。`mc` や S3 SDK でのアップロードも可能です。

### 2) 検索(MCP ツール)

MCP クライアント(例: Claude デスクトップ/Cowork コネクター)を `http://localhost:3001/mcp` に接続すると、以下のツールが公開されます。

| ツール | パラメータ | 説明 |
|---|---|---|
| `pdf_search` | `query`(必須)、`top_k`(既定 5)、`min_score`(既定 0.6) | ハイブリッド検索(ベクトル類似度 + キーワード)。`min_score` 未満の純粋なベクトル結果は除外(キーワード一致は保持) |
| `echo` | `message` | 入力をそのまま返す(接続確認用) |
| `add` | `a`, `b` | 2数の和(接続確認用) |

> ヒント: ドキュメントが日本語なら**日本語で質問**すると精度が最も高くなります。結果が少なければ `min_score` を 0.4〜0.5 に下げ、精度重視なら 0.65〜0.7 に上げてください。

## スモークテスト

```bash
bash scripts/smoke_test.sh
```

`pdf_chunks` テーブルに埋め込みチャンクが生成されたか(=アップロード→Webhook→埋め込みのパイプライン動作)を確認し、ドキュメント別のチャンク数を出力します。

## 本番デプロイ

実ドメイン + 自動 HTTPS(Caddy)+ セキュリティハードニングは [DEPLOYMENT.md](DEPLOYMENT.md) を参照してください。ローカルオーバーライドなしで本番用にのみ起動するには:

```bash
docker compose -f docker-compose.yml up -d --build
```

## トラブルシューティング

- **`password authentication failed for user "postgres"`**
  `.env` の `DB_PASSWORD` を変更したが `pgvector` ボリュームが旧パスワードで初期化済みの場合です。Postgres はボリューム初回作成時のみパスワードを設定します。データ保持: `docker compose exec pgvector psql -U postgres -c "ALTER USER postgres PASSWORD '新しい値';"` の後 `docker compose restart mcp-server`。初期化してよければ `docker compose down && docker volume rm <プロジェクト>_pgvector_data`。
- **MCP クライアントで `min_score` など新パラメータが効かない**
  再ビルド後、クライアントが旧ツールスキーマをキャッシュしている場合です。**クライアントを再接続**すると新スキーマを再取得します。
- **最初の検索がタイムアウト**
  Ollama モデルのコールドスタートです。少し待って再試行すれば正常に応答します。
- **チャットで `pdf_search` が呼ばれない**
  「保存された PDF/マニュアルから〜を探して」のように**ドキュメント検索の意図を明示**してください。
- **MinIO コンソールに接続できない(localhost:9001)**
  ローカルでは `docker-compose.override.yml` があることでポートが公開されます。`docker compose port minio 9001` で確認。

## リポジトリ構成

```
docker-compose.yml            # スタック全体(本番基準)
docker-compose.override.yml   # ローカル専用オーバーライド(localhost 公開、caddy 未起動)
.env.example                  # 環境変数テンプレート(秘密値は .env、gitignored)
caddy/Caddyfile               # (本番)リバースプロキシ設定
minio/provision.sh            # バケット・ポリシー・サービスアカウントの初期化(1回)
scripts/quickstart.sh         # ワンコマンドのローカル実行
scripts/smoke_test.sh         # パイプラインのスモークテスト
java-server/                  # 埋め込み・保存・検索ロジック(Java)
kotlin-server/                # MCP サーバー・ツール・Webhook(Kotlin)
pdfs/                         # サンプル PDF
DEPLOYMENT.md                 # 本番デプロイ・セキュリティハードニングガイド
```
