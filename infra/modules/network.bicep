// VNET (/16) と、Container Apps 環境用のサブネット (/24)。
// privateEndpointSubnetName を指定した場合は、Private Endpoint 用のサブネット (/24) も作成します。

@description('VNET 名。')
param vnetName string

@description('デプロイ先リージョン。')
param location string

@description('VNET のアドレス空間 (/16)。')
param vnetAddressPrefix string

@description('Container Apps 環境用サブネットの名前。')
param containerAppsSubnetName string

@description('Container Apps 環境用サブネットのアドレス範囲 (/24)。')
param containerAppsSubnetPrefix string

@description('Private Endpoint 用サブネットの名前。空のときは作成しません。')
param privateEndpointSubnetName string = ''

@description('Private Endpoint 用サブネットのアドレス範囲 (/24)。')
param privateEndpointSubnetPrefix string = ''

param tags object = {}

var containerAppsSubnet = {
  name: containerAppsSubnetName
  properties: {
    addressPrefix: containerAppsSubnetPrefix
    // ワークロード プロファイル環境のサブネットは Microsoft.App/environments への委任が必要です。
    delegations: [
      {
        name: 'Microsoft.App.environments'
        properties: {
          serviceName: 'Microsoft.App/environments'
        }
      }
    ]
  }
}

// 委任済みのサブネットには Private Endpoint を置けないため、別サブネットにします。
var privateEndpointSubnets = empty(privateEndpointSubnetName)
  ? []
  : [
      {
        name: privateEndpointSubnetName
        properties: {
          addressPrefix: privateEndpointSubnetPrefix
        }
      }
    ]

resource vnet 'Microsoft.Network/virtualNetworks@2025-05-01' = {
  name: vnetName
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [
        vnetAddressPrefix
      ]
    }
    subnets: concat([containerAppsSubnet], privateEndpointSubnets)
  }
}

output vnetId string = vnet.id
output vnetName string = vnet.name
output containerAppsSubnetId string = '${vnet.id}/subnets/${containerAppsSubnetName}'
output privateEndpointSubnetId string = empty(privateEndpointSubnetName)
  ? ''
  : '${vnet.id}/subnets/${privateEndpointSubnetName}'
