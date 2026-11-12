// Container Apps 環境 (app-a 側) に対する Private Endpoint と、名前解決用の Private DNS Zone。
// Private Endpoint と DNS ゾーンの VNET リンクは、呼び出し元 (app-b) の VNET に置きます。

@description('Private Endpoint の名前。')
param name string

@description('デプロイ先リージョン (Private Endpoint を置く VNET と同じリージョン)。')
param location string

@description('接続先 (app-a の Container Apps 環境) のリソース ID。')
param targetEnvironmentId string

@description('Private Endpoint を配置するサブネットのリソース ID。Microsoft.App/environments に委任していないサブネットを指定します。')
param subnetId string

@description('Private DNS Zone をリンクする VNET (app-b の VNET) のリソース ID。')
param linkedVnetId string

param tags object = {}

// 形式は privatelink.<リージョン名>.azurecontainerapps.io です (例: privatelink.japaneast.azurecontainerapps.io)。
var privateDnsZoneName = 'privatelink.${toLower(replace(location, ' ', ''))}.azurecontainerapps.io'

resource privateEndpoint 'Microsoft.Network/privateEndpoints@2025-05-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    subnet: {
      id: subnetId
    }
    privateLinkServiceConnections: [
      {
        name: name
        properties: {
          privateLinkServiceId: targetEnvironmentId
          groupIds: [
            'managedEnvironments'
          ]
        }
      }
    ]
  }
}

resource privateDnsZone 'Microsoft.Network/privateDnsZones@2024-06-01' = {
  name: privateDnsZoneName
  location: 'global'
  tags: tags
}

resource vnetLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = {
  parent: privateDnsZone
  name: 'link-to-app-b-vnet'
  location: 'global'
  tags: tags
  properties: {
    registrationEnabled: false
    virtualNetwork: {
      id: linkedVnetId
    }
  }
}

// ゾーン グループにより、Private Endpoint の IP アドレスの A レコードが DNS ゾーンに自動登録されます。
resource dnsZoneGroup 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2025-05-01' = {
  parent: privateEndpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [
      {
        name: 'azurecontainerapps'
        properties: {
          privateDnsZoneId: privateDnsZone.id
        }
      }
    ]
  }
}

output privateEndpointId string = privateEndpoint.id
output privateDnsZoneName string = privateDnsZone.name
