using './main.bicep'

// az deployment sub create 用のサンプルです。
//   az deployment sub create --location japaneast --template-file infra/main.bicep --parameters infra/main.sample.bicepparam
// azd (azd up / azd provision) は infra/main.parameters.json を使います。このファイルは参照されません。
// (infra/main.bicepparam という名前にすると azd が main.parameters.json より優先して読むため、別名にしています。)

// デプロイ先リージョン。省略すると az deployment sub create の --location と同じになります。
param location = 'japaneast'

// 以下は省略可能です。省略した名前には、サブスクリプション・環境名・リージョンから決まる
// ランダムに見える固定の文字列 (nameSuffix) が付くため、同じ入力で再実行すると同じリソースを更新します。
// 別の名前でもう一組作りたいときは、environmentName か nameSuffix を変えてください。
// param environmentName = 'acatcp'
// param nameSuffix = 'abc123'
// param resourceGroupName = 'rg-acatcp'
// param appAVnetName = 'vnet-a'
// param appASubnetName = 'snet-a'
// param appBVnetName = 'vnet-b'
// param appBSubnetName = 'snet-b'
// param appBPrivateEndpointSubnetName = 'snet-pe'
// param containerRegistryName = 'acrexample0001'

// コンテナー イメージは ACR のものだけを使います (Docker Hub は使いません)。
// 初回 (イメージ未指定) は、ACR が空なので仮のイメージで起動します。ACR にビルドして push してから、
// 次の 2 つに ACR 内の「リポジトリ:タグ」を指定して再デプロイすると、ACR のイメージに置き換わります。
//   az acr build -r <ACR 名> -t app-a:v1 ./app-a   (app-b も同様)
// param appAImage = 'app-a:v1'
// param appBImage = 'app-b:v1'
// イメージを指定せずに再デプロイするときは、現在のイメージを維持するため次を true にします。
// param appAExists = true
// param appBExists = true
