// 既存のコンテナー アプリが使っているイメージを返します。
// ACR に置いたイメージを、再プロビジョニング (azd provision) で仮イメージ (bootstrapImage) へ戻さないために使います。

@description('コンテナー アプリが既に存在するか。azd が SERVICE_<サービス名>_RESOURCE_EXISTS に設定します。')
param exists bool

@description('コンテナー アプリ名。')
param name string

resource existingApp 'Microsoft.App/containerApps@2026-01-01' existing = if (exists) {
  name: name
}

output image string = exists ? existingApp!.properties.template.containers[0].image : ''
