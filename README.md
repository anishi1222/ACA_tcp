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
├── app-b/           コンテナーアプリ B (Dockerfile: EXPOSE 8080)
└── infra/           Azure Container Apps へデプロイする Bicep (main.bicep、main.bicepparam、modules/)
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

## Azure へデプロイする (Bicep)

`infra/` の Bicep で、A と B を別々の VNET 上の Container Apps 環境にデプロイします。サブスクリプション スコープのテンプレートで、リソース グループも作成します。

> **実環境へのデプロイと疎通確認は、まだ行っていません。** 確認済みなのは、`az bicep build` (警告なし)、`az deployment sub validate`、`az deployment sub what-if` (エラーなし) までです。Microsoft Learn に記載がなく、実環境での確認が必要な点は「[実環境で確認が必要な点](#実環境で確認が必要な点)」にまとめています。

### 構成

```text
Internet
   |  HTTPS
   v
+--- VNET B (10.20.0.0/16) ------------------------------------------+
|  snet-b  (10.20.0.0/24)                                            |
|    ACA environment B (external)                                    |
|      app-b : HTTP 8080                                             |
|         |                                                          |
|         |  TCP 18192 to the app-a FQDN                             |
|         v                                                          |
|  snet-pe (10.20.1.0/24)                                            |
|    Private Endpoint   <-- Private DNS Zone (linked to VNET B)      |
+----------|---------------------------------------------------------+
           |  Private Link
           v
+--- VNET A (10.10.0.0/16) ------------------------------------------+
|  snet-a  (10.10.0.0/24)                                            |
|    ACA environment A (internal, public network access disabled)    |
|      app-a : TCP 18192                                             |
+--------------------------------------------------------------------+

Logs: both environments -> diagnostic settings -> Log Analytics workspace (Azure Monitor)
```

| | app-a | app-b |
| --- | --- | --- |
| VNET / サブネット | `10.10.0.0/16` / `10.10.0.0/24` | `10.20.0.0/16` / `10.20.0.0/24` (Private Endpoint 用に `10.20.1.0/24` も作成) |
| Container Apps 環境 | 内部 (VNET 内からのみ到達可能)。Public network access は無効 | 外部 (インターネットから到達可能) |
| Ingress | TCP `18192` (VNET 内のみ) | HTTP `8080` (インターネットから HTTPS で到達) |
| スケール | 最小 1 / 最大 1 (スケール ルールなし) | 最小 1 / 最大 1 (スケール ルールなし) |
| イメージ | `docker.io/anishi/acatcp:a_v1.0` | `docker.io/anishi/acatcp:b_v1.0` |
| 環境変数 | なし | `TCP_SERVER_HOST` = A の FQDN (`app-a.<環境 A の既定ドメイン>`) |

B は、A の FQDN を Private Endpoint 経由で名前解決し、A の `18192` へ TCP 接続します。Private DNS Zone は B の VNET にリンクしています。

### 作成されるリソース

`<suffix>` は `nameSuffix` です (既定はランダムな 6 文字)。名前はすべてパラメーターで変更できます。

| リソース | 名前 (既定) | 備考 |
| --- | --- | --- |
| リソース グループ | `rg-acatcp-<suffix>` | 以下のリソースをすべて格納します |
| Log Analytics ワークスペース | `log-acatcp-<suffix>` | ログの保存先 (Azure Monitor)。保持期間は 30 日 |
| VNET A とサブネット | `vnet-a-<suffix>` / `snet-a-<suffix>` | サブネットは `Microsoft.App/environments` に委任します |
| VNET B とサブネット | `vnet-b-<suffix>` / `snet-b-<suffix>`、`snet-pe-<suffix>` | `snet-pe` は Private Endpoint 用で、委任しません |
| Container Apps 環境 A / B | `cae-a-<suffix>` / `cae-b-<suffix>` | ワークロード プロファイル環境 (Consumption)。ログの出力先は Azure Monitor |
| コンテナー アプリ | `app-a` / `app-b` | 0.5 vCPU / 1 GiB |
| Private Endpoint | `pe-app-a-<suffix>` | B の VNET から環境 A へ接続します (group ID `managedEnvironments`) |
| Private DNS Zone | `privatelink.<リージョン>.azurecontainerapps.io` | B の VNET にリンクし、DNS ゾーン グループで A レコードを自動登録します |
| 診断設定 | `send-to-log-analytics` (環境 A / B) | 全ログ (`allLogs`) を Log Analytics ワークスペースへ送ります |

### 前提

- Azure CLI と Bicep (`az bicep install`)。Azure CLI 2.90.0 / Bicep 0.49.2 で確認しています。
- サブスクリプションに、リソース グループとリソースを作成できる権限 (サブスクリプション スコープのデプロイのため)。
- リソース プロバイダー `Microsoft.App`、`Microsoft.Network`、`Microsoft.OperationalInsights`、`Microsoft.Insights` の登録 (`az provider register --namespace <名前>`)。
- Docker Hub 上の `docker.io/anishi/acatcp:a_v1.0` と `b_v1.0`。両タグが存在し、どちらも `linux/amd64` であることを Docker Hub の API で確認しました。作り直す場合は、次のようにビルドして push します (Apple Silicon の Mac では `--platform linux/amd64` が必要です)。

```bash
podman build --platform linux/amd64 -t docker.io/anishi/acatcp:a_v1.0 app-a
podman build --platform linux/amd64 -t docker.io/anishi/acatcp:b_v1.0 app-b
podman login docker.io
podman push docker.io/anishi/acatcp:a_v1.0
podman push docker.io/anishi/acatcp:b_v1.0
```

### デプロイ

```bash
# 既定: 名前はランダム。リージョンは --location と同じ
az deployment sub create --name acatcp --location japaneast --template-file infra/main.bicep

# リージョンと名前を指定する場合
az deployment sub create --name acatcp --location japaneast --template-file infra/main.bicep \
  --parameters location=japaneast resourceGroupName=rg-acatcp \
               appAVnetName=vnet-a appASubnetName=snet-a appBVnetName=vnet-b appBSubnetName=snet-b

# パラメーター ファイルを使う場合 (infra/main.bicepparam。--template-file は不要です)
az deployment sub create --name acatcp --location japaneast --parameters infra/main.bicepparam
```

`--location` はデプロイの管理情報の保存先です。`location` パラメーターを省略すると、リソースのリージョンにも使われます。

デプロイが終わったら、出力から B の URL を取り出して呼び出します。すべて想定どおりに動けば、次のような応答になります (実環境では未確認です)。

```bash
az deployment sub show --name acatcp --query properties.outputs
curl "$(az deployment sub show --name acatcp --query properties.outputs.appBUrl.value -o tsv)"
# Acknowledged Hello 18192, how are you?. This message is sent from 18192!
```

デプロイ前の確認 (`what-if`) は、`nameSuffix` を固定して実行します (理由は次の節を参照)。

```bash
az deployment sub what-if --name acatcp --location japaneast --template-file infra/main.bicep --parameters nameSuffix=abc123
```

不要になったら、リソース グループごと削除します。

```bash
az group delete --name "$(az deployment sub show --name acatcp --query properties.outputs.resourceGroupName.value -o tsv)"
```

### パラメーター

すべて省略できます。名前の既定値は、`nameSuffix` を付けたものです。

| パラメーター | 既定値 | 説明 |
| --- | --- | --- |
| `location` | `--location` の値 | リソースのリージョン |
| `nameSuffix` | ランダムな 6 文字 (`newGuid()` から生成) | 既定の名前に付ける文字列 (3〜12 文字) |
| `resourceGroupName` | `rg-acatcp-<nameSuffix>` | リソース グループ名 |
| `appAVnetName` | `vnet-a-<nameSuffix>` | app-a の VNET 名 |
| `appASubnetName` | `snet-a-<nameSuffix>` | app-a の環境用サブネット名 |
| `appBVnetName` | `vnet-b-<nameSuffix>` | app-b の VNET 名 |
| `appBSubnetName` | `snet-b-<nameSuffix>` | app-b の環境用サブネット名 |
| `appBPrivateEndpointSubnetName` | `snet-pe-<nameSuffix>` | Private Endpoint 用サブネット名 (B の VNET 内) |
| `appAVnetAddressPrefix` | `10.10.0.0/16` | app-a の VNET のアドレス空間。サブネットは先頭の `/24` を使います |
| `appBVnetAddressPrefix` | `10.20.0.0/16` | app-b の VNET のアドレス空間 (A の VNET と重ならないこと)。環境用サブネットは先頭の `/24`、Private Endpoint 用は 2 番目の `/24` |
| `appAImage` / `appBImage` | `docker.io/anishi/acatcp:a_v1.0` / `:b_v1.0` | コンテナー イメージ |
| `tags` | `{}` | すべてのリソースに付けるタグ |

- **名前をランダムにしている理由**: Log Analytics ワークスペースは、削除してから 14 日間は同じ名前を使えません (論理削除)。名前が固定だと、リソース グループの削除直後に、同じ名前で再デプロイできなくなるためです。
- **再デプロイ**: 既定のままだと、デプロイのたびに別の名前で新しい一式が作られます。同じリソースを更新するときは、前回の出力 `nameSuffix` (または各名前のパラメーター) を指定してください。
- **`what-if`**: `newGuid()` から生成した名前のリソースは、`what-if` で評価できません (`Unsupported`)。`nameSuffix` か各名前を指定したときに、内容を確認できます。

### ログ (Azure Monitor)

- 両方の環境で、ログの出力先 (`appLogsConfiguration.destination`) を `azure-monitor` にしています。環境ごとに診断設定 `send-to-log-analytics` を作成し、全ログ (`allLogs`) を、同じリソース グループ内の Log Analytics ワークスペース (`log-acatcp-<suffix>`) へ送ります。
- アプリの標準出力 / 標準エラーは `ContainerAppConsoleLogs`、システム ログは `ContainerAppSystemLogs` テーブルに入ります。ワークスペースの **ログ** で、たとえば次のクエリを実行します。

```kusto
ContainerAppConsoleLogs
| where ContainerAppName in ("app-a", "app-b")
| project TimeGenerated, ContainerAppName, Log
| order by TimeGenerated desc
```

- 診断設定を作ってからログが届くまで、最大 90 分かかることがあります。テーブルは、最初のログが届いたときに作られます ([Diagnostic settings in Azure Monitor](https://learn.microsoft.com/azure/azure-monitor/platform/diagnostic-settings))。

### 実環境で確認が必要な点

次の点は Microsoft Learn に記載がなく、実環境での PoC (または、サポート リクエストでの確認) が必要です。

1. **Private Endpoint 経由で、TCP `18192` が通るか**

   Learn の Private Endpoint の手順 ([Use a private endpoint with an Azure Container Apps environment](https://learn.microsoft.com/azure/container-apps/how-to-use-private-endpoint)) は、HTTP のアプリを例にしています。HTTP 以外の TCP ingress を、Private Endpoint 経由で受けられるかは明記されていません。B の VNET 内の VM などから `nc -vz app-a.<環境 A の既定ドメイン> 18192` を実行するか、`curl "https://<B の FQDN>/api/tcp?port=18192"` が 200 を返すかで確認します。通らない場合は、2 つの VNET のピアリングへ切り替えます。このとき Private DNS Zone は、環境 A の既定ドメインと同名のゾーンに、環境 A の静的 IP を指す `*` の A レコードを置く形になります。

2. **名前解決**

   Learn の手順では、`app-a.<既定ドメイン>` が公開 DNS の CNAME で `<既定ドメインの先頭ラベル>.privatelink.<リージョン>.azurecontainerapps.io` に解決され、Private DNS Zone の A レコード (Private Endpoint の IP) に行き着きます。B の VNET から `nslookup app-a.<環境 A の既定ドメイン>` を実行し、Private Endpoint の IP (`10.20.1.x`) が返るか確認してください。

   Learn の手順は、DNS ゾーン グループではなく A レコードを手動で追加しています。この Bicep は、DNS ゾーン グループによる A レコードの自動登録に任せていますが、期待どおりに登録されるかは未確認です。登録されていない場合は、次のように追加します (`<RG>` と `<suffix>` は読み替えてください)。

   ```bash
   PE_IP=$(az network private-endpoint show --resource-group <RG> --name pe-app-a-<suffix> \
     --query 'customDnsConfigs[0].ipAddresses[0]' --output tsv)
   RECORD=$(az containerapp env show --resource-group <RG> --name cae-a-<suffix> \
     --query properties.defaultDomain --output tsv | sed 's/\..*//')
   az network private-dns record-set a add-record --resource-group <RG> \
     --zone-name privatelink.<リージョン>.azurecontainerapps.io \
     --record-set-name "$RECORD" --ipv4-address "$PE_IP"
   ```

3. **A がインターネットから到達できないこと**

   A の ingress は、別の環境にいる B から呼ぶために `external: true` にしています (`false` だと同じ環境内からしか呼べません)。環境 A は内部環境のため、到達できるのは VNET 内 (Private Endpoint 経由を含む) のはずです。インターネット側から接続できないことも、あわせて確認してください。

### 補足

- **A が公開するのは `18192` だけ**です (仕様どおり)。B に `port=28192` や `38192` を指定すると、A に到達できず 502 / 504 になります。これらも使う場合は、`infra/modules/container-app.bicep` の ingress に `additionalPortMappings` を追加します (書式は、後述の `app-a.yaml` を参照)。
- **Private Endpoint 用サブネット**: 委任 (`Microsoft.App/environments`) 済みのサブネットには Private Endpoint を置けないため、B の VNET には、環境用とは別に `/24` のサブネットをもう 1 つ作っています。
- **追加で作られるリソースと費用**: ACA は、環境ごとに管理用のリソース グループ (名前は `ME_` で始まります) を作り、Standard のロード バランサー (外部環境は ingress 用のパブリック IP も) を置きます。egress 用の Standard の静的パブリック IP も含めて、Container Apps の料金とは別に課金されます ([Managed resources](https://learn.microsoft.com/azure/container-apps/custom-virtual-networks#managed-resources))。Private Endpoint にも追加料金があります。管理用のリソース グループは変更しないでください。
- **Docker Hub の匿名プル**: 回数制限があります。デプロイが失敗したり、リビジョンが起動しなかったりするときは、プル エラーでないかを確認してください。
- **NSG は作成していません**。必要に応じて追加してください。

## 参考: Azure CLI で 1 つの環境にデプロイする

上の Bicep の構成とは別に、A と B を同じ Container Apps 環境に置く場合の、`az containerapp` による手順の目安です。Microsoft Learn の記載に基づくもので、**ACA 環境へのデプロイ自体は、このリポジトリでは未検証です** (ローカルの Podman で、コンテナー同士の通信までを確認しています)。

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
| `az bicep build` (`infra/main.bicep`、各モジュール) と `az bicep build-params` (`main.bicepparam`) | エラー・警告なし |
| `az deployment sub validate` (`infra/main.bicep`) | 成功 |
| `az deployment sub what-if` (`nameSuffix` 固定) | エラーなし。作成されるリソースはすべて `Create` |
| Docker Hub の `anishi/acatcp` | `a_v1.0` / `b_v1.0` の両タグが存在し、どちらも `linux/amd64` |
| Bicep の実デプロイ、Private Endpoint 経由の TCP 疎通 | 未実施 (「実環境で確認が必要な点」を参照) |
| ACA 環境へのデプロイ (`az containerapp`) | 未実施 |

## 補足

- **応答の `?.`**: B が送る `Hello 18192, how are you?` は `?` で終わるため、指定のテンプレート `Acknowledged {メッセージ}. ...` をそのまま適用すると、`...how are you? This message...` になります。仕様どおりの出力で、補正はしていません。
- **改行が必要**: A は改行 (LF) で 1 メッセージの終わりを判断します。改行を送らずに接続したままにすると、応答せず 60 秒の無通信タイムアウトで切断されます (接続を閉じた場合は、最後の改行なしの行も 1 メッセージとして扱います)。`nc` の実装によっては、標準入力の終了後も接続を保持するため、上記の例では `-w 2` を付けています。
- **B が接続できるポート**: B は 1〜65535 の任意のポートを受け付けます (接続先ホストは `TCP_SERVER_HOST` で固定)。インターネットに公開する場合は、A のポート (`18192` / `28192` / `38192`) だけに制限することを検討してください。
- **暗号化**: A と B の間は平文の TCP です。
- **ログ**: 標準出力に 1 イベント 1 行で出力します。
