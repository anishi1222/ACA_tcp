using './main.bicep'

// デプロイ先リージョン。省略すると az deployment sub create の --location と同じになります。
param location = 'japaneast'

// 以下は省略可能です。省略した名前には、ランダムな文字列 (nameSuffix) が付きます。
// 同じリソースを更新デプロイするときは nameSuffix を固定してください (what-if もこのときだけ有効です)。
// param nameSuffix = 'abc123'
// param resourceGroupName = 'rg-acatcp'
// param appAVnetName = 'vnet-a'
// param appASubnetName = 'snet-a'
// param appBVnetName = 'vnet-b'
// param appBSubnetName = 'snet-b'
// param appBPrivateEndpointSubnetName = 'snet-pe'
