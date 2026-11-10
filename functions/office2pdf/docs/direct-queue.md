# office2pdf 直接Queue連携

外部システムは入力ファイルをBlobへ保存し、`office2pdf-jobs` QueueへUTF-8 JSONを
Base64で1回だけエンコードして送信します。ファイル本体や資格情報はメッセージへ入れません。

```json
{
  "version": 1,
  "jobId": "5b179260-9994-4df6-9d34-cf9638c55431",
  "input": {
    "storage": "default",
    "container": "incoming",
    "blobName": "office/input.xlsx"
  },
  "output": {
    "storage": "default",
    "container": "outgoing",
    "prefix": "converted"
  },
  "filename": "input.xlsx"
}
```

JSON本体はUTF-8で48 KiB以下です。ホストの `messageEncoding` は `base64` です。
Java SDKなら `QueueClientBuilder.messageEncoding(QueueMessageEncoding.BASE64)` を設定し、
`sendMessage` にはJSON文字列をそのまま渡します。手動でBase64化した文字列をSDKでも
エンコードすると二重になるため、どちらか一方で1回だけ適用してください。

成功時は次のように、試行IDで分離した場所へ保存します。

```text
converted/{jobId}/results/{attemptId}/document.pdf
converted/{jobId}/results/{attemptId}/report.json
converted/{jobId}/results/{attemptId}/manifest.json
```

`version: 2` では `input.expectedETag`、最大16件の文字列`metadata`、登録済み
結果Queueへの `notification.queue` を追加できます。通知は最低1回配送で、
`eventId` は `{jobId}:{succeeded|failed}` の安定値です。
結果通知もBase64を1回適用したJSONです。受信側は1回デコードし、SDKが自動で
デコードする場合は手動で重ねてデコードしません。`eventId` で重複を除外してください。

Storageは `default` または事前登録した別名だけを指定できます。

- 入力: `CONVERSION_INPUT_STORAGE_<ALIAS>` または `CONVERSION_INPUT_STORAGE_<ALIAS>__blobServiceUri`
- 出力: `CONVERSION_OUTPUT_STORAGE_<ALIAS>` または `CONVERSION_OUTPUT_STORAGE_<ALIAS>__blobServiceUri`
- 結果Queue: `CONVERSION_RESULT_QUEUE_<ALIAS>__queueName` と接続設定

接続文字列をQueue JSONへ含めません。Managed Identity利用時は各Storageへ必要な
Blob/Queueデータ権限を付与します。

## 通知先と保持期間の設定

例えば `notification.queue: "completed"` を使う場合は、次をアプリ設定へ登録します。

```text
CONVERSION_RESULT_QUEUE_COMPLETED__queueName=conversion-results
CONVERSION_RESULT_QUEUE_COMPLETED__queueServiceUri=https://RESULTS.queue.core.windows.net
```

ユーザー割り当てManaged Identityには任意の `CONVERSION_RESULT_QUEUE_COMPLETED__clientId`
を追加します。接続文字列を使う場合は、URI・clientIdの代わりに
`CONVERSION_RESULT_QUEUE_COMPLETED__connectionString` を設定します。通知先に変換依頼Queueや
poison Queueは使えません。送信待ちは永続化し、通知失敗時は通知だけを再送します。

| 設定 | 既定 | 用途 |
| --- | --- | --- |
| `CONVERSION_RESULT_RETENTION_DAYS` | `0` | 自動清掃無効。正の整数で所有成果物などの保持日数を指定 |
| `CONVERSION_STATE_RETENTION_DAYS` | `0` | 状態の自動削除無効。有効化時は結果保持も有効にし、それより長く設定 |

結果通知先または保持を設定すると5分ごとのメンテナンス処理を使います。配置補助を使わず
直接設定する場合は `AzureWebJobs.MaintainConversions.Disabled=false`、どちらも未設定なら
`true` を設定します。外部システムが用意した入力原本は清掃対象に含みません。
清掃済みの結果取得は `410 JOB_RESULT_EXPIRED` です。状態を削除すると同じjobIdの
重複判定も終了します。

権限・ホスト接続・通知先登録は[共通の配置・運用手順](../../../docs/development.md#8-managed-identity閉域storage結果通知)、
清掃対象・期限の起点・旧成果物の扱いは[保持期間](../../../docs/development.md#保持期間)を参照してください。
