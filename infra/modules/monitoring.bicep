// Azure Monitor のログの保存先となる Log Analytics ワークスペース。
@description('ワークスペース名 (4〜63 文字)。')
param name string

@description('デプロイ先リージョン。')
param location string

@description('ログの保持日数。')
@minValue(30)
@maxValue(730)
param retentionInDays int = 30

param tags object = {}

resource workspace 'Microsoft.OperationalInsights/workspaces@2025-02-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: retentionInDays
  }
}

output id string = workspace.id
output name string = workspace.name
