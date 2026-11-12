// コンテナー アプリ (ワークロード プロファイル環境の Consumption で実行)。

type ingressSettings = {
  @description('true: 環境の外から到達可能 (内部環境では VNET 内から)。false: 同じ環境内のアプリからのみ。')
  external: bool

  @description('Ingress のプロトコル。')
  transport: 'http' | 'tcp'

  @description('コンテナーがリッスンするポート。')
  targetPort: int

  @description('TCP で公開するポート。transport が tcp のときのみ有効で、省略すると targetPort と同じ値になります。')
  exposedPort: int?

  @description('HTTP (非 HTTPS) でのアクセスを許可するか。transport が http のときのみ有効で、省略すると false (HTTPS へリダイレクト) です。')
  allowInsecure: bool?
}

type environmentVariable = {
  name: string
  value: string
}

@description('コンテナー アプリ名。環境内で一意であればよく、FQDN の先頭になります。')
param name string

@description('デプロイ先リージョン。')
param location string

@description('配置先の Container Apps 環境のリソース ID。')
param environmentId string

@description('コンテナー イメージ。')
param image string

param ingress ingressSettings

param environmentVariables environmentVariable[] = []

@description('vCPU 数。メモリとの組み合わせに制約があります (0.5 vCPU のとき 1Gi)。')
param cpu string = '0.5'

param memory string = '1Gi'

@description('レプリカ数の下限。')
param minReplicas int = 1

@description('レプリカ数の上限。')
param maxReplicas int = 1

param tags object = {}

// exposedPort は TCP のとき、allowInsecure は HTTP のときだけ送ります。
// 該当しない側を null で送ると、Container Apps の事前検証 (what-if / validate) がスキーマ エラーにするためです。
var transportSpecificIngress = ingress.transport == 'tcp'
  ? { exposedPort: ingress.?exposedPort ?? ingress.targetPort }
  : { allowInsecure: ingress.?allowInsecure ?? false }

resource app 'Microsoft.App/containerApps@2026-01-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    environmentId: environmentId
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: union(
        {
          external: ingress.external
          transport: ingress.transport
          targetPort: ingress.targetPort
          traffic: [
            {
              latestRevision: true
              weight: 100
            }
          ]
        },
        transportSpecificIngress
      )
    }
    template: {
      containers: [
        {
          name: name
          image: image
          resources: {
            cpu: json(cpu)
            memory: memory
          }
          env: environmentVariables
        }
      ]
      // 負荷に応じたスケールはせず、レプリカ数を固定します。
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
      }
    }
  }
}

output id string = app.id
output name string = app.name
output fqdn string = app.properties.configuration.ingress.fqdn
