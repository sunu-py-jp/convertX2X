# office2pdf 直接Queue連携

外部システムは入力ファイルをBlobへ保存し、`office2pdf-jobs` QueueへUTF-8 JSONを
送信できます。ファイル本体や資格情報はメッセージへ入れません。

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

成功時は次のように、試行IDで分離した場所へ保存します。

```text
converted/{jobId}/results/{attemptId}/document.pdf
converted/{jobId}/results/{attemptId}/report.json
converted/{jobId}/results/{attemptId}/manifest.json
```

`version: 2` では `input.expectedETag`、最大16件の文字列`metadata`、登録済み
結果Queueへの `notification.queue` を追加できます。通知は最低1回配送で、
`eventId` は `{jobId}:{succeeded|failed}` の安定値です。

Storageは `default` または事前登録した別名だけを指定できます。

- 入力: `CONVERSION_INPUT_STORAGE_<ALIAS>` または `__blobServiceUri`
- 出力: `CONVERSION_OUTPUT_STORAGE_<ALIAS>` または `__blobServiceUri`
- 結果Queue: `CONVERSION_RESULT_QUEUE_<ALIAS>__queueName` と接続設定

接続文字列をQueue JSONへ含めません。Managed Identity利用時は各Storageへ必要な
Blob/Queueデータ権限を付与します。
