# md2pdf 直接Queue連携

外部システムは入力ファイルをBlobへ保存し、UTF-8 JSONをBase64で1回エンコードして
`md2pdf-jobs` Queueへ送信できます。入力はUTF-8のMarkdown、または画像を同梱したZIPです。
ファイル本体や資格情報はメッセージへ入れません。

```json
{
  "version": 1,
  "jobId": "5b179260-9994-4df6-9d34-cf9638c55431",
  "input": {
    "storage": "default",
    "container": "incoming",
    "blobName": "markdown/input.zip"
  },
  "output": {
    "storage": "default",
    "container": "outgoing",
    "prefix": "converted"
  },
  "filename": "input.zip"
}
```

## 送信時のエンコード

上のJSONはBase64デコード後の本文です。UTF-8で最大48KiBに収め、Base64を1回だけ
適用して送ります。ファイル本体をBase64化してJSONに入れる形式ではありません。

Java SDKの `QueueMessageEncoding.BASE64` など自動エンコードを有効にした場合は、
未エンコードのJSON文字列をSDKへ渡します。自動処理と手動処理を二重に適用しないでください。
Node.jsの `@azure/storage-queue` では次のように明示的にエンコードします。
この例を使う送信元プロジェクトには同パッケージをインストールしておきます。

```javascript
import { QueueClient } from '@azure/storage-queue';
import { readFile } from 'node:fs/promises';

const queue = new QueueClient(process.env.PRODUCER_QUEUE_CONNECTION_STRING, 'md2pdf-jobs');
const json = await readFile('queue-request.json', 'utf8');
await queue.sendMessage(Buffer.from(json, 'utf8').toString('base64'));
```

この例は依頼JSONを `queue-request.json` に保存し、既存Queueへ送ります。送信側の
`PRODUCER_QUEUE_CONNECTION_STRING` には対象Queueへ追加できる資格情報を設定します。
FunctionsホストがBase64をデコードしてからワーカーへ渡します。

## 成果物とversion 2

成功時は次のように、試行IDで分離した場所へ保存します。

```text
converted/{jobId}/results/{attemptId}/document.pdf
converted/{jobId}/results/{attemptId}/report.json
converted/{jobId}/results/{attemptId}/manifest.json
```

`version: 2` では `input.expectedETag`、最大16件の文字列`metadata`、登録済み
結果Queueへの `notification.queue` を追加できます。通知は最低1回配送で、
`eventId` は `{jobId}:{succeeded|failed}` の安定値です。通知JSONもBase64が1回適用されています。

## Storageとホスト設定

非同期は制御用Storageの接続文字列 `CONVERSION_STORAGE_CONNECTION_STRING`、
またはMI用の `CONVERSION_STORAGE__blobServiceUri` と `CONVERSION_STORAGE__queueServiceUri`
の両方がある場合に有効です。どちらも未設定なら非同期は無効です。同じ登録で
接続文字列とMI設定を混在させません。

Queueトリガー用の `CONVERSION_QUEUE_CONNECTION_STRING` またはそのMI設定と、
有効・無効を切り替える設定はホスト起動前に用意します。ローカル起動スクリプトと
`scripts/configure_azure_async.py` が制御用設定から導出します。ホスト用Storageは別設定です。
詳細は[配置手順](usage.md#azureへの配置)と
[共通のMI・権限・通知設定](../../../docs/development.md#8-managed-identity閉域storage結果通知)を参照してください。

Storageは `default` または事前登録した別名だけを指定できます。

- 入力: `CONVERSION_INPUT_STORAGE_<ALIAS>` または `__blobServiceUri`
- 出力: `CONVERSION_OUTPUT_STORAGE_<ALIAS>` または `__blobServiceUri`
- 結果Queue: `CONVERSION_RESULT_QUEUE_<ALIAS>__queueName` と接続設定

接続文字列をQueue JSONへ含めません。Managed Identity利用時は各Storageへ必要な
Blob/Queueデータ権限を付与します。
