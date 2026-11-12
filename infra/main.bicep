// app-a (TCP / プライベート) と app-b (HTTP / パブリック) を、別々の VNET 上の Container Apps 環境にデプロイします。
//
//   app-b の VNET (10.20.0.0/16)                       app-a の VNET (10.10.0.0/16)
//   ┌───────────────────────────────┐                  ┌─────────────────────────────┐
//   │ Container Apps 環境 B (外部)  │                  │ Container Apps 環境 A       │
//   │   app-b : HTTP 8080 (公開)    │                  │ (内部 / Public access 無効) │
//   │ Private Endpoint ─────────────┼── Private Link ─▶│   app-a : TCP 18192         │
//   │ Private DNS Zone (リンク済み) │                  └─────────────────────────────┘
//   └───────────────────────────────┘
//   ログ: 両環境 → 診断設定 → Log Analytics ワークスペース (Azure Monitor)
targetScope = 'subscription'

@description('環境名。azd では AZURE_ENV_NAME が渡され、名前の既定値の生成とリソース グループのタグ (azd-env-name) に使います。')
@minLength(1)
@maxLength(64)
param environmentName string = 'acatcp'

@description('デプロイ先リージョン。未指定のときは、デプロイ時に指定した --location と同じになります (azd では AZURE_LOCATION)。')
@metadata({
  azd: {
    type: 'location'
  }
})
param location string = deployment().location

@description('既定の名前に付ける文字列 (小文字英数字 3〜12 文字)。サブスクリプション ID・環境名・リージョンから作る、ランダムに見える固定値です。同じ入力なら毎回同じ値になるため、再実行しても同じリソースを更新します。')
@minLength(3)
@maxLength(12)
param nameSuffix string = take(uniqueString(subscription().id, environmentName, location), 6)

@description('リソース グループ名。未指定のときは rg-acatcp-<nameSuffix>。')
param resourceGroupName string = 'rg-acatcp-${nameSuffix}'

@description('app-a の VNET 名。未指定のときは vnet-a-<nameSuffix>。')
param appAVnetName string = 'vnet-a-${nameSuffix}'

@description('app-a の Container Apps 環境用サブネット名。未指定のときは snet-a-<nameSuffix>。')
param appASubnetName string = 'snet-a-${nameSuffix}'

@description('app-b の VNET 名。未指定のときは vnet-b-<nameSuffix>。')
param appBVnetName string = 'vnet-b-${nameSuffix}'

@description('app-b の Container Apps 環境用サブネット名。未指定のときは snet-b-<nameSuffix>。')
param appBSubnetName string = 'snet-b-${nameSuffix}'

@description('app-a 向け Private Endpoint を置くサブネット名 (app-b の VNET 内)。未指定のときは snet-pe-<nameSuffix>。')
param appBPrivateEndpointSubnetName string = 'snet-pe-${nameSuffix}'

@description('app-a の VNET のアドレス空間 (/16)。サブネットはこの範囲から /24 を切り出します。')
param appAVnetAddressPrefix string = '10.10.0.0/16'

@description('app-b の VNET のアドレス空間 (/16)。app-a の VNET と重ならないようにします。')
param appBVnetAddressPrefix string = '10.20.0.0/16'

@description('Azure Container Registry 名 (5〜50 文字の英小文字と数字。Azure 全体で一意)。未指定のときは acr<ハッシュ>。')
@minLength(5)
@maxLength(50)
param containerRegistryName string = 'acr${uniqueString(subscription().id, resourceGroupName)}'

@description('ACR にまだイメージが無い初回のプロビジョニングだけで使う仮のイメージ (Docker Hub ではなく、Microsoft Container Registry の公開イメージ)。ACR は同じデプロイで作るため、この時点ではイメージを置けません。azd deploy でビルドして ACR に push したイメージに置き換わり、再プロビジョニングでは ACR のイメージを維持します。')
param bootstrapImage string = 'mcr.microsoft.com/azuredocs/containerapps-helloworld:latest'

@description('app-a のイメージ。ACR 内のものだけを指定できます。「リポジトリ:タグ」(例: app-a:v1) を指定すると、ACR のログイン サーバーを自動で前に付けます (完全な参照でも構いません)。ACR 以外のレジストリのイメージは pull できません。未指定のときは、appAExists が true なら現在のイメージを維持し、false なら bootstrapImage を使います。azd では azd deploy がイメージを設定するため、指定は不要です。')
param appAImage string = ''

@description('app-b のイメージ。ACR 内のものだけを指定できます。「リポジトリ:タグ」(例: app-b:v1) を指定すると、ACR のログイン サーバーを自動で前に付けます (完全な参照でも構いません)。ACR 以外のレジストリのイメージは pull できません。未指定のときは、appBExists が true なら現在のイメージを維持し、false なら bootstrapImage を使います。azd では azd deploy がイメージを設定するため、指定は不要です。')
param appBImage string = ''

@description('app-a が既にデプロイ済みか。true で appAImage が未指定のときは、現在のイメージを維持します (azd が自動設定。azd を使わないときは、イメージ指定を省く 2 回目以降に true にします)。')
param appAExists bool = false

@description('app-b が既にデプロイ済みか。true で appBImage が未指定のときは、現在のイメージを維持します (azd が自動設定。azd を使わないときは、イメージ指定を省く 2 回目以降に true にします)。')
param appBExists bool = false

@description('すべてのリソースに付けるタグ。azd-env-name は自動で追加されます。')
param tags object = {}

// コンテナー アプリ名。azure.yaml のサービス名 (app-a / app-b) と、タグ azd-service-name の値にもなります。
var appAName = 'app-a'
var appBName = 'app-b'
var appATcpPort = 18192
var appBHttpPort = 8080

// azd は azd-env-name でリソース グループを、azd-service-name でデプロイ先のコンテナー アプリを見つけます。
var allTags = union(tags, { 'azd-env-name': environmentName })

// 指定されたイメージを ACR 内の参照にそろえます。ログイン サーバーが付いていなければ付けるため、ACR 以外のイメージにはなりません。
func acrImageRef(loginServer string, image string) string =>
  startsWith(image, '${loginServer}/') ? image : '${loginServer}/${image}'

resource rg 'Microsoft.Resources/resourceGroups@2025-04-01' = {
  name: resourceGroupName
  location: location
  tags: allTags
}

// ログの保存先 (Azure Monitor)。
module monitoring 'modules/monitoring.bicep' = {
  scope: rg
  name: 'monitoring'
  params: {
    name: 'log-acatcp-${nameSuffix}'
    location: location
    tags: allTags
  }
}

// コンテナー アプリが実行するイメージの置き場 (ACR) と、イメージを pull するマネージド ID。
// イメージは azd deploy (ACR Tasks でのビルド) や az acr build で ACR に置き、アプリは ACR から pull します。
module registry 'modules/registry.bicep' = {
  scope: rg
  name: 'registry'
  params: {
    name: containerRegistryName
    identityName: 'id-acatcp-${nameSuffix}'
    location: location
    tags: allTags
  }
}

module networkA 'modules/network.bicep' = {
  scope: rg
  name: 'network-app-a'
  params: {
    vnetName: appAVnetName
    location: location
    vnetAddressPrefix: appAVnetAddressPrefix
    containerAppsSubnetName: appASubnetName
    containerAppsSubnetPrefix: cidrSubnet(appAVnetAddressPrefix, 24, 0)
    tags: allTags
  }
}

module networkB 'modules/network.bicep' = {
  scope: rg
  name: 'network-app-b'
  params: {
    vnetName: appBVnetName
    location: location
    vnetAddressPrefix: appBVnetAddressPrefix
    containerAppsSubnetName: appBSubnetName
    containerAppsSubnetPrefix: cidrSubnet(appBVnetAddressPrefix, 24, 0)
    privateEndpointSubnetName: appBPrivateEndpointSubnetName
    privateEndpointSubnetPrefix: cidrSubnet(appBVnetAddressPrefix, 24, 1)
    tags: allTags
  }
}

// app-a の環境: 内部環境 (VNET 内からのみ到達可能)。Private Endpoint を使うため Public access は無効にします。
module environmentA 'modules/environment.bicep' = {
  scope: rg
  name: 'environment-app-a'
  params: {
    name: 'cae-a-${nameSuffix}'
    location: location
    infrastructureSubnetId: networkA.outputs.containerAppsSubnetId
    internal: true
    publicNetworkAccess: 'Disabled'
    logAnalyticsWorkspaceId: monitoring.outputs.id
    tags: allTags
  }
}

// app-b の環境: 外部環境 (インターネットから到達可能)。
module environmentB 'modules/environment.bicep' = {
  scope: rg
  name: 'environment-app-b'
  params: {
    name: 'cae-b-${nameSuffix}'
    location: location
    infrastructureSubnetId: networkB.outputs.containerAppsSubnetId
    internal: false
    publicNetworkAccess: 'Enabled'
    logAnalyticsWorkspaceId: monitoring.outputs.id
    tags: allTags
  }
}

// ACR に置いたイメージを、再プロビジョニングで仮イメージ (bootstrapImage) へ戻さないよう、既存アプリのイメージを引き継ぎます。
module appAImageState 'modules/existing-container-image.bicep' = {
  scope: rg
  name: 'app-a-image-state'
  params: {
    exists: appAExists
    name: appAName
  }
}

module appBImageState 'modules/existing-container-image.bicep' = {
  scope: rg
  name: 'app-b-image-state'
  params: {
    exists: appBExists
    name: appBName
  }
}

// app-b の環境からは別の環境にいるため、ingress は external: true にします (false だと同一環境内からしか呼べません)。
// 環境 A が内部環境なので、実際に到達できるのは VNET 内 (Private Endpoint 経由を含む) だけです。
// イメージの決まり方: appAImage の指定 (ACR 内) > デプロイ済みのイメージ (appAExists) > 初回用の仮イメージ (bootstrapImage)。
module appA 'modules/container-app.bicep' = {
  scope: rg
  name: 'app-a'
  params: {
    name: appAName
    location: location
    environmentId: environmentA.outputs.id
    image: !empty(appAImage)
      ? acrImageRef(registry.outputs.loginServer, appAImage)
      : (appAExists ? appAImageState.outputs.image : bootstrapImage)
    registryServer: registry.outputs.loginServer
    registryIdentityId: registry.outputs.identityId
    ingress: {
      external: true
      transport: 'tcp'
      targetPort: appATcpPort
      exposedPort: appATcpPort
    }
    tags: union(allTags, { 'azd-service-name': appAName })
  }
}

// app-b の VNET から app-a の環境へ接続する Private Endpoint と、その名前解決用の Private DNS Zone。
module privateEndpoint 'modules/private-endpoint.bicep' = {
  scope: rg
  name: 'private-endpoint-app-a'
  params: {
    name: 'pe-app-a-${nameSuffix}'
    location: location
    targetEnvironmentId: environmentA.outputs.id
    subnetId: networkB.outputs.privateEndpointSubnetId
    linkedVnetId: networkB.outputs.vnetId
    tags: allTags
  }
}

module appB 'modules/container-app.bicep' = {
  scope: rg
  name: 'app-b'
  params: {
    name: appBName
    location: location
    environmentId: environmentB.outputs.id
    image: !empty(appBImage)
      ? acrImageRef(registry.outputs.loginServer, appBImage)
      : (appBExists ? appBImageState.outputs.image : bootstrapImage)
    registryServer: registry.outputs.loginServer
    registryIdentityId: registry.outputs.identityId
    ingress: {
      external: true
      transport: 'http'
      targetPort: appBHttpPort
      allowInsecure: false
    }
    environmentVariables: [
      {
        name: 'TCP_SERVER_HOST'
        value: appA.outputs.fqdn
      }
    ]
    tags: union(allTags, { 'azd-service-name': appBName })
  }
  // 名前解決の準備ができてから app-b を起動します。
  dependsOn: [
    privateEndpoint
  ]
}

output resourceGroupName string = rg.name
output nameSuffix string = nameSuffix
output appAFqdn string = appA.outputs.fqdn
output appBFqdn string = appB.outputs.fqdn
output appBUrl string = 'https://${appB.outputs.fqdn}/api/tcp?port=${appATcpPort}'
output logAnalyticsWorkspaceName string = monitoring.outputs.name
output privateDnsZoneName string = privateEndpoint.outputs.privateDnsZoneName
output containerRegistryName string = registry.outputs.name

// azd が使う出力 (.azure/<環境名>/.env に保存されます)。
output AZURE_RESOURCE_GROUP string = rg.name
output AZURE_CONTAINER_REGISTRY_ENDPOINT string = registry.outputs.loginServer
