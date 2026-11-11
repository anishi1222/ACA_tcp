# ACA_tcp

Java 25 で作成した 2 つのコンテナーアプリです。Azure Container Apps (ACA) での利用を想定していますが、Podman / Docker だけでも動作します。

| | コンテナーアプリ A (`app-a/`) | コンテナーアプリ B (`app-b/`) |
| --- | --- | --- |
| 役割 | 受け取ったメッセージに自分のポート番号を付けて echo back する TCP サーバー | クエリパラメーターで指定したポートの A へ TCP 接続してメッセージを送り、その応答を返す HTTP API |
| 待ち受け | TCP `18192` / `28192` / `38192` | HTTP `8080` (コンテキストパス `/api/tcp`) |
| 設定 | なし (ポートは固定) | 環境変数 `TCP_SERVER_HOST` (A のホスト名) |

```text
client --HTTP GET /api/tcp?port=N--> app B (:8080) --TCP "Hello N, how are you?"--> app A (:N)
       <------- 200 + reply --------             <-- "Acknowledged ... sent from N!" --
```

実行時の外部ライブラリはありません (JDK 標準ライブラリのみ。`jdk.httpserver` と仮想スレッドを使用)。JUnit はテストでのみ使用します。

## ディレクトリ構成

```text
.
├── pom.xml          親 POM (app-a / app-b をまとめてビルド)
├── app-a/           コンテナーアプリ A (Dockerfile: EXPOSE 18192 28192 38192)
└── app-b/           コンテナーアプリ B (Dockerfile: EXPOSE 8080)
```

## 仕様

### コンテナーアプリ A

- TCP `18192`、`28192`、`38192` で待ち受けます (固定)。3 ポートすべてのバインドに成功してから受け付けを開始し、1 つでも失敗した場合は起動エラーで終了します。
- `Dockerfile` で上記 3 ポートを `EXPOSE` しています。
- 各ポートでメッセージを受け取ると、次の文字列を返します。`{ポート番号}` は、メッセージを受け取った A 側のリスニングポートです。

  `Acknowledged {リクエストメッセージ}. This message is sent from {ポート番号}!`

- プロトコルは、UTF-8、改行 (LF。CRLF も可) 区切りで 1 行 = 1 メッセージです。応答も 1 行 (LF 終端) です。1 接続で複数のメッセージを送れます。1 行は最大 8 KiB で、無通信が 60 秒続くと切断します。

```console
$ echo "Hello" | nc -w 2 localhost 28192
Acknowledged Hello. This message is sent from 28192!
```

### コンテナーアプリ B

- HTTP `8080` で待ち受け、コンテキストパスは `/api/tcp` です (`/api/tcp/` も可)。
- `GET /api/tcp?port={ポート番号}` を受けると、A の `{ポート番号}` へ TCP 接続して `Hello {ポート番号}, how are you?` を送信し、A の応答を本文 (`text/plain; charset=utf-8`) として返します。
- 送信先ホスト名は環境変数で指定します。TCP 接続のタイムアウトは、接続 3 秒、応答待ち 5 秒です。

| 環境変数 | 既定値 | 説明 |
| --- | --- | --- |
| `TCP_SERVER_HOST` | `localhost` | A のホスト名または IP アドレス (未設定または空白のみの場合は既定値) |

| HTTP ステータス | 条件 |
| --- | --- |
| 200 | A から応答を受信した |
| 400 | `port` が未指定、または 1〜65535 の整数ではない |
| 404 | `/api/tcp` (または `/api/tcp/`) 以外のパス |
| 405 | GET 以外のメソッド (`Allow: GET`) |
| 502 | A に接続できない、名前解決できない、応答を得られないまま切断された |
| 504 | A への接続または応答待ちがタイムアウトした |
| 500 | 想定外のエラー |

## 前提

- JDK 25
- Maven (3.10.0 で確認)
- Podman または Docker (コンテナーで動かす場合)

## ビルドとテスト

既定の `java` が 25 でない場合は、`JAVA_HOME` に JDK 25 を指定します。

```bash
export JAVA_HOME=/path/to/jdk-25
mvn -B -ntp clean verify
```

`app-a/target/app.jar` と `app-b/target/app.jar` が生成されます。テストでは、A の echo 動作 (3 ポート、複数行、CRLF、接続だけして切断するクライアントなど) と、B のクエリ検証、ステータス変換、TCP クライアントを検証します。

## ローカルで実行する (jar)

ターミナルを 2 つ使います。

```bash
# 1) コンテナーアプリ A
java -jar app-a/target/app.jar

# 2) コンテナーアプリ B (A と同じマシンなので TCP_SERVER_HOST は localhost)
TCP_SERVER_HOST=localhost java -jar app-b/target/app.jar
```

```console
$ curl "http://localhost:8080/api/tcp?port=18192"
Acknowledged Hello 18192, how are you?. This message is sent from 18192!
```

## コンテナーで実行する (Podman / Docker)

Podman で検証しています。Docker では `podman` を `docker` に読み替えてください。

```bash
# イメージをビルド (Dockerfile 内のビルドではテストを実行しないため、テストは上記の mvn verify で行います)
podman build -t aca-tcp-a:local app-a
podman build -t aca-tcp-b:local app-b

# コンテナー同士が名前で通信できるよう、ネットワークを作成して起動
podman network create aca-tcp-net
podman run -d --name app-a --network aca-tcp-net aca-tcp-a:local
podman run -d --name app-b --network aca-tcp-net -e TCP_SERVER_HOST=app-a -p 8080:8080 aca-tcp-b:local

# 呼び出し
curl "http://localhost:8080/api/tcp?port=18192"
curl "http://localhost:8080/api/tcp?port=28192"
curl "http://localhost:8080/api/tcp?port=38192"

# 後片付け
podman rm -f app-a app-b && podman network rm aca-tcp-net
```

実行結果です。

```console
$ curl "http://localhost:8080/api/tcp?port=28192"
Acknowledged Hello 28192, how are you?. This message is sent from 28192!

$ curl -i "http://localhost:8080/api/tcp?port=abc"
HTTP/1.1 400 Bad Request
...
Query parameter 'port' is required and must be an integer from 1 to 65535

$ curl "http://localhost:8080/api/tcp?port=12345"
Bad Gateway: cannot talk to app-a:12345 (ConnectException: Connection refused (connect failed))
```

イメージはマルチステージビルド (`maven:3-eclipse-temurin-25` でビルドし、`eclipse-temurin:25-jre` で実行) で、非 root (UID 10001) で動作します。

## Azure Container Apps へデプロイする

Microsoft Learn の記載に基づく手順の目安です。**ACA 環境へのデプロイ自体は、このリポジトリでは未検証です** (ローカルの Podman で、コンテナー同士の通信までを確認しています)。

### 押さえておく点

| 項目 | 内容 |
| --- | --- |
| イメージ | Linux の `linux/amd64` イメージが必要です ([Containers in Azure Container Apps](https://learn.microsoft.com/azure/container-apps/containers#limitations))。Apple Silicon の Mac では、`--platform linux/amd64` を付けてビルドするか、ACR タスク (`az acr build`) でビルドします |
| A の ingress | `transport: tcp` を使います。メインのポート (`targetPort`) に加えて、追加ポートを `additionalPortMappings` に指定します。追加ポートは 1 アプリあたり最大 5 つで、`36985` は予約済みのため使えません。`containerapp` CLI 拡張 (`az extension add -n containerapp`) が必要です ([Ingress overview](https://learn.microsoft.com/azure/container-apps/ingress-overview#additional-tcp-ports)) |
| A への到達 | TCP ingress のアプリには、同じ環境内の他のコンテナーアプリから「アプリ名 + 公開ポート (`exposedPort`)」で到達できます |
| B の設定 | `TCP_SERVER_HOST` に A のアプリ名を設定し、`port` クエリには A の `exposedPort` を指定します |
| VNet | 外部公開の TCP ingress は、VNet 統合された環境でのみサポートされます。A は環境内の B からだけ呼ばれるため `external: false` にしています。ポータル手順には「TCP ingress は VNet 構成の環境で利用可能」とも書かれているため、VNet 統合環境の利用を推奨します。VNet なしの環境で内部専用の TCP ingress が使えるかは、PoC での確認が必要です |

`exposedPort` と `targetPort` が異なると、B が送る `Hello {port}` の `{port}` は公開ポート、A の応答に含まれるポート番号はコンテナー側 (`targetPort`) のポートになります。以下の例では、どちらも同じ値にしています。

### イメージのビルドと登録

```bash
# ACR 上でビルド (既定で linux/amd64 のイメージになります)
az acr build --registry <ACR 名> --image aca-tcp-a:1.0 app-a
az acr build --registry <ACR 名> --image aca-tcp-b:1.0 app-b

# ローカルでビルドする場合 (Apple Silicon では amd64 のエミュレーションで動作するため時間がかかります)
podman build --platform linux/amd64 -t <ACR 名>.azurecr.io/aca-tcp-a:1.0 app-a
```

### アプリ A (`app-a.yaml`)

```yaml
name: app-a
type: Microsoft.App/containerApps
location: japaneast # 環境と同じリージョン
properties:
  managedEnvironmentId: <環境のリソース ID>
  configuration:
    activeRevisionsMode: Single
    # ACR からイメージを取得するための認証設定 (registries) は、環境に合わせて追加します
    ingress:
      external: false
      transport: tcp
      targetPort: 18192
      exposedPort: 18192
      additionalPortMappings:
      - targetPort: 28192
        exposedPort: 28192
        external: false
      - targetPort: 38192
        exposedPort: 38192
        external: false
  template:
    containers:
    - name: app-a
      image: <ACR 名>.azurecr.io/aca-tcp-a:1.0
      resources:
        cpu: 0.5
        memory: 1Gi
    scale:
      minReplicas: 1
      maxReplicas: 1
```

```bash
az extension add -n containerapp
az containerapp create --name app-a --resource-group <リソースグループ> --yaml app-a.yaml
```

### アプリ B

ACR のイメージ取得に必要な `--registry-server` や `--registry-identity` も、環境に合わせて指定します。

```bash
az containerapp create \
  --name app-b --resource-group <リソースグループ> --environment <環境名> \
  --image <ACR 名>.azurecr.io/aca-tcp-b:1.0 \
  --ingress external --transport http --target-port 8080 \
  --env-vars TCP_SERVER_HOST=app-a \
  --cpu 0.5 --memory 1Gi --min-replicas 1 --max-replicas 1
```

デプロイ後は、`https://<B の FQDN>/api/tcp?port=28192` を呼び出します。HTTP ingress は TLS を B の手前で終端するため、B 自体は平文の HTTP `8080` で動作します。

## 動作確認の状況

| 確認内容 | 結果 |
| --- | --- |
| `mvn -B -ntp clean verify` (JDK 25.0.5 / Maven 3.10.0) | 全テスト成功 |
| jar 同士のローカル連携 (A の 3 ポート) | 想定どおりの応答 |
| エラー系 (400 / 404 / 405 / 502) | 想定どおりのステータス |
| Podman のネットワーク上でのコンテナー間連携 (`TCP_SERVER_HOST=app-a`)、A 停止時の 504 | 想定どおり |
| `podman inspect` | A の ExposedPorts は 18192 / 28192 / 38192、B は 8080 |
| `SIGTERM` での停止 | 終了コード 143 で正常終了 (`podman stop` は約 0.4 秒) |
| `podman build --platform linux/amd64` (A) と起動 | Apple Silicon 上でビルド・起動とも成功 |
| ACA 環境へのデプロイ | 未実施 |

## 補足

- **応答の `?.`**: B が送る `Hello 18192, how are you?` は `?` で終わるため、指定のテンプレート `Acknowledged {メッセージ}. ...` をそのまま適用すると、`...how are you? This message...` になります。仕様どおりの出力で、補正はしていません。
- **改行が必要**: A は改行 (LF) で 1 メッセージの終わりを判断します。改行を送らずに接続したままにすると、応答せず 60 秒の無通信タイムアウトで切断されます (接続を閉じた場合は、最後の改行なしの行も 1 メッセージとして扱います)。`nc` の実装によっては、標準入力の終了後も接続を保持するため、上記の例では `-w 2` を付けています。
- **B が接続できるポート**: B は 1〜65535 の任意のポートを受け付けます (接続先ホストは `TCP_SERVER_HOST` で固定)。インターネットに公開する場合は、A のポート (`18192` / `28192` / `38192`) だけに制限することを検討してください。
- **暗号化**: A と B の間は平文の TCP です。
- **ログ**: 標準出力に 1 イベント 1 行で出力します。
