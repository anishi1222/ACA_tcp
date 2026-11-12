// Container Apps 環境 (ワークロード プロファイル / Consumption)。
// ログは Azure Monitor に出力し、診断設定で Log Analytics ワークスペースへ送ります。

@description('環境名 (2〜32 文字、小文字・数字・ハイフン)。')
param name string

@description('デプロイ先リージョン。')
param location string

@description('環境を統合するサブネットのリソース ID (Microsoft.App/environments に委任済み)。')
param infrastructureSubnetId string

@description('true のとき内部環境 (内部ロード バランサー、VNET 内からのみ到達可能)。')
param internal bool

@description('環境へのパブリック ネットワーク アクセス。Private Endpoint を使う環境では Disabled が必須です。')
@allowed([
  'Enabled'
  'Disabled'
])
param publicNetworkAccess string

@description('診断設定の送信先 Log Analytics ワークスペースのリソース ID。')
param logAnalyticsWorkspaceId string

param tags object = {}

resource environment 'Microsoft.App/managedEnvironments@2026-01-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    // destination を azure-monitor にすると、ログの出力先は診断設定で決まります。
    appLogsConfiguration: {
      destination: 'azure-monitor'
    }
    vnetConfiguration: {
      internal: internal
      infrastructureSubnetId: infrastructureSubnetId
    }
    publicNetworkAccess: publicNetworkAccess
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    zoneRedundant: false
  }
}

resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'send-to-log-analytics'
  scope: environment
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    // ContainerAppConsoleLogs / ContainerAppSystemLogs
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
  }
}

output id string = environment.id
output name string = environment.name
output defaultDomain string = environment.properties.defaultDomain
