# excel2img 直接Queue連携

入力をBlobへ保存してから、`excel2img-jobs` QueueへUTF-8 JSONをBase64で1回エンコードして送ります。
ファイル本体・SAS・接続文字列はメッセージへ入れません。

デコード後のJSONは最大48KiBです。Java SDKでは `QueueMessageEncoding.BASE64` を設定し、
JSON文字列をそのまま `sendMessage` へ渡します。`host.json` の `messageEncoding: "base64"` により、
Functionsホストがデコードしてワーカーへ渡します。自分でBase64化した文字列をSDKで再度エンコードしないでください。

```json
{
  "version": 1,
  "jobId": "5b179260-9994-4df6-9d34-cf9638c55431",
  "input": {
    "storage": "default",
    "container": "incoming",
    "blobName": "excel/sample.xlsx"
  },
  "output": {
    "storage": "default",
    "container": "outgoing",
    "prefix": "rendered"
  },
  "filename": "sample.xlsx",
  "options": {
    "format": "png",
    "sheet": "業務フロー",
    "range": "A1:L30"
  }
}
```

`options` は省略可能です。既定はPNG、全表示シート、印刷範囲優先です。
`sheet` と `range` も任意ですが `range` には `sheet` が必要です。
同じjobIdで異なる入力や描画オプションを送ると競合となります。
再試行は同じ依頼内容で送信してください。

成功時の例：

```text
rendered/{jobId}/results/{attemptId}/pages/page-001.png
rendered/{jobId}/results/{attemptId}/pages/page-002.png
rendered/{jobId}/results/{attemptId}/report.json
rendered/{jobId}/results/{attemptId}/manifest.json
```

`format=pdf` のときはPNG群の代わりに `document.pdf` を保存します。
manifestには成果物パス・MIME型・サイズ・ETag・SHA-256を記録します。
全成果物が保存されてから結果を確定し、未完了のファイルを成功扱いにしません。
一部範囲を見送った結果もジョブ自体は成功します。`report.json` の `outcome` と見送り理由を確認してください。

`version: 2` では `input.expectedETag`、最大16件の文字列 `metadata`、
登録済み結果Queueの `notification.queue` を追加できます。
通知は最低1回配送なので、受信側でも `eventId` による重複処理を防いでください。

Storageは `default` または事前登録した別名だけを指定します。

- 入力：`CONVERSION_INPUT_STORAGE_<ALIAS>` または `__blobServiceUri`
- 出力：`CONVERSION_OUTPUT_STORAGE_<ALIAS>` または `__blobServiceUri`
- 結果Queue：`CONVERSION_RESULT_QUEUE_<ALIAS>__queueName` と接続設定

制御用Storageは `CONVERSION_STORAGE_CONNECTION_STRING`、またはManaged Identity用の
`CONVERSION_STORAGE__blobServiceUri` と `CONVERSION_STORAGE__queueServiceUri` の両方で設定します。
同じ接続で接続文字列とManaged Identity設定を混在させません。Queueトリガー用の派生設定も必要です。
Managed Identity利用時は対象StorageのBlob/Queueデータ権限が必要です。
`CONVERSION_CREATE_RESOURCES=false` では必要なコンテナー・Queueを事前作成し、アプリによる作成を省略します。
この場合、リソース作成のための権限は不要ですが、入力・状態・結果・通知に必要な読み書き権限は必要です。
詳細は [利用ガイド](usage.md) と [共通の配置手順](../../../docs/development.md) を参照してください。
