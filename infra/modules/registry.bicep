// コンテナー アプリが実行するイメージの置き場 (Azure Container Registry) と、アプリがイメージを pull するための
// ユーザー割り当てマネージド ID (AcrPull)。管理者ユーザーと匿名 pull は使いません。
// ID を先に作ってからアプリに割り当てるので、アプリとロール割り当ての間で循環参照になりません。

@description('レジストリ名 (5〜50 文字の英小文字と数字。Azure 全体で一意)。')
@minLength(5)
@maxLength(50)
param name string

@description('マネージド ID の名前。')
param identityName string

@description('デプロイ先リージョン。')
param location string

param tags object = {}

// 組み込みロール AcrPull。
var acrPullRoleDefinitionId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  '7f951dda-4ed3-4680-a7ca-43fe172d538d'
)

// azd のリモート ビルド (ACR Tasks) がパブリック エンドポイントを使うため、パブリック アクセスは有効にします。
resource registry 'Microsoft.ContainerRegistry/registries@2025-04-01' = {
  name: name
  location: location
  tags: tags
  sku: {
    name: 'Basic'
  }
  properties: {
    adminUserEnabled: false
    anonymousPullEnabled: false
    publicNetworkAccess: 'Enabled'
  }
}

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: identityName
  location: location
  tags: tags
}

resource acrPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, identity.id, acrPullRoleDefinitionId)
  properties: {
    roleDefinitionId: acrPullRoleDefinitionId
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

output name string = registry.name
output loginServer string = registry.properties.loginServer
output identityId string = identity.id
