// app-a (TCP / プライベート) と app-b (HTTP / パブリック) を、別々の VNET 上の Container Apps 環境にデプロイします。
//
//   app-b の VNET (10.20.0.0/16)                       app-a の VNET (10.10.0.0/16)
//   ┌───────────────────────────────┐                  ┌───────────────────────────┐
//   │ Container Apps 環境 B (外部)  │                  │ Container Apps 環境 A     │
//   │   app-b : HTTP 8080 (公開)    │                  │ (内部 / Public access 無効)│
//   │ Private Endpoint ─────────────┼── Private Link ─▶│   app-a : TCP 18192       │
//   │ Private DNS Zone (リンク済み) │                  └───────────────────────────┘
//   └───────────────────────────────┘
//   ログ: 両環境 → 診断設定 → Log Analytics ワークスペース (Azure Monitor)
targetScope = 'subscription'

@description('デプロイ先リージョン。未指定のときは、デプロイ時に指定した --location と同じになります。')
param location string = deployment().location

@description('既定の名前に付けるランダムな文字列 (小文字英数字 3〜12 文字)。同じ名前で更新デプロイするときは、前回と同じ値を指定します。')
@minLength(3)
@maxLength(12)
param nameSuffix string = take(newGuid(), 6)

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

@description('app-a のコンテナー イメージ。')
param appAImage string = 'docker.io/anishi/acatcp:a_v1.0'

@description('app-b のコンテナー イメージ。')
param appBImage string = 'docker.io/anishi/acatcp:b_v1.0'

@description('すべてのリソースに付けるタグ。')
param tags object = {}

var appATcpPort = 18192
var appBHttpPort = 8080

// nameSuffix の既定値は newGuid() (ランダム) のため、リンターの警告を抑止しています。
// 再デプロイで同じリソースを更新するときは、nameSuffix か各名前のパラメーターを指定してください。
resource rg 'Microsoft.Resources/resourceGroups@2025-04-01' = {
  #disable-next-line use-stable-resource-identifiers
  name: resourceGroupName
  location: location
  tags: tags
}

// ログの保存先 (Azure Monitor)。
module monitoring 'modules/monitoring.bicep' = {
  scope: rg
  name: 'monitoring'
  params: {
    name: 'log-acatcp-${nameSuffix}'
    location: location
    tags: tags
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
    tags: tags
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
    tags: tags
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
    tags: tags
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
    tags: tags
  }
}

// app-b の環境からは別の環境にいるため、ingress は external: true にします (false だと同一環境内からしか呼べません)。
// 環境 A が内部環境なので、実際に到達できるのは VNET 内 (Private Endpoint 経由を含む) だけです。
module appA 'modules/container-app.bicep' = {
  scope: rg
  name: 'app-a'
  params: {
    name: 'app-a'
    location: location
    environmentId: environmentA.outputs.id
    image: appAImage
    ingress: {
      external: true
      transport: 'tcp'
      targetPort: appATcpPort
      exposedPort: appATcpPort
    }
    tags: tags
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
    tags: tags
  }
}

module appB 'modules/container-app.bicep' = {
  scope: rg
  name: 'app-b'
  params: {
    name: 'app-b'
    location: location
    environmentId: environmentB.outputs.id
    image: appBImage
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
    tags: tags
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
