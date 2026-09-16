# Movie2Audioを直接Queueから実行する

動画ファイルをBlobへ保存し、`movie2audio-jobs` Queueへ入出力の参照をJSONで送ります。HTTP受付やPlaygroundを経由せず、同じAAC抽出処理を利用できます。既定の成果物は `audio.m4a` です。version 2では `audio.wav` も選択でき、ZIPにはしません。

## 接続設定と役割

Functions側で `CONVERSION_STORAGE_CONNECTION_STRING` を設定すると、非同期HTTPとQueueワーカーを有効にします。Queueと状態コンテナーは、この制御用Storageの `movie2audio-jobs` です。`AzureWebJobsStorage` はFunctionsホスト用の別設定です。制御用設定が未設定・空値の場合、Queueトリガーを登録しません。

入力と出力は既定の制御用Storageを使うか、次のように登録を分けます。

| Functions側の環境変数 | Queue JSONの参照 | 役割 |
| --- | --- | --- |
| `CONVERSION_STORAGE_CONNECTION_STRING` | `storage: "default"` または省略 | 制御と既定の入出力 |
| `CONVERSION_INPUT_STORAGE_SOURCE` | `input.storage: "source"` | 追加の入力アカウント |
| `CONVERSION_OUTPUT_STORAGE_ARCHIVE` | `output.storage: "archive"` | 追加の出力アカウント |

登録名は `[a-z][a-z0-9_]{0,31}`、環境変数の接尾辞は大文字です。`default` は予約名です。入力用の登録を出力用には流用できません。登録はアカウント単位で、コンテナーの許可リストではありません。任意URL・SAS・接続文字列をQueue JSONで渡す欄はありません。SASを都度指定する場合は同期の [`POST /api/convert-to-blob`](../../../docs/APIDocs/movie2audio/storage.html) を使います。

送信側には入力Blobを書き込む権限とQueueへ追加する権限が必要です。Functions側には入力の読み取り、制御用のQueue・状態更新、出力の保存・コンテナー作成が必要です。HTTP結果APIを使う場合は、Functions側の出力接続に読み取り権限も必要です。登録名の分離だけでStorage接続の実権限は狭まらないので、実際の接続設定にも必要な権限を与えてください。

## 依頼する

1. 新しいUUIDを決め、動画をそのジョブ専用の入力Blob名で保存します。
2. [`examples/queue-request.json`](../examples/queue-request.json) の `jobId` と入力・出力先を書き換えます。別Storageの例は [`queue-request-cross-account.json`](../examples/queue-request-cross-account.json) です。
3. UTF-8 JSONをBase64で1回だけエンコードし、`movie2audio-jobs` Queueへ送ります。

```json
{
  "version": 1,
  "jobId": "f6603efc-2fa8-4466-8d09-6d7c3ed94c18",
  "input": {
    "storage": "default",
    "container": "videos-incoming",
    "blobName": "incoming/f6603efc-2fa8-4466-8d09-6d7c3ed94c18/meeting.mp4"
  },
  "output": {
    "storage": "default",
    "container": "audio-created",
    "prefix": "exports/meetings"
  },
  "filename": "meeting.mp4"
}
```

必須項目は整数の `version: 1`、UUIDの `jobId`、`input.container`・`input.blobName`、`output.container` です。`storage` の省略時は `default`、`prefix` の省略時は空です。`filename` は任意の表示名で、出力名やFFmpegの引数には使いません。version 1では `options` を受け付けません。version 2の音声オプションは末尾を参照してください。`output.mode` は両バージョンとも使用しません。

Base64デコード後のJSONは正しいUTF-8で最大48KiBです。重複キー、未知のキー、末尾の追加JSON、不正な型を拒否します。コンテナー名は3〜63文字の小文字英数字・単独ハイフンで先頭末尾は英数字です。Blob名とprefixには制御文字や `.` / `..` のパス区間などを指定できません。ファイル本体をJSONへ含めないでください。

同梱の [`QueueProducer.mjs`](../examples/QueueProducer.mjs) で動画アップロードとQueue送信をまとめて行えます。例のJSONはジョブごとに新しいUUID・一意な入力Blob名へ変更してください。機能ディレクトリで依存をインストールし、接続設定を環境変数に置いて実行します。

```bash
node examples/QueueProducer.mjs ./meeting.mp4 examples/queue-request.json
```

入力は上書きせずに保存します。アップロード済みの同じ入力に対してQueue送信だけを再試行する場合は、同じ依頼ファイルを使います。

```bash
node examples/QueueProducer.mjs --queue-only examples/queue-request.json
```

この送信スクリプトはFunctionsと同名の制御用・入力用接続設定を使います。送信側では入力へ書き込める資格情報、Functions側では必要に応じて読み取り専用の資格情報を設定できます。既存入力の上書きや送信失敗後のBlob削除は行いません。接続設定はJSONへ書かず、送信元の環境変数で管理します。

独自の送信側へ組み込む場合、次はNode.jsの送信例です。送信元プロジェクトには `@azure/storage-blob` と `@azure/storage-queue` が必要です。UUIDは実行ごとに新しくし、接続情報は環境変数から読みます。`PRODUCER_INPUT_CONNECTION_STRING` は送信側で動画をアップロードする接続、`PRODUCER_CONTROL_CONNECTION_STRING` は送信側でQueueに追加する接続です。Functions側と同じアカウントを指す必要がありますが、資格情報と必要権限は送信側専用にできます。

```javascript
import { randomUUID } from 'node:crypto';
import { BlobServiceClient } from '@azure/storage-blob';
import { QueueClient } from '@azure/storage-queue';

const jobId = randomUUID();
const inputContainer = 'videos-incoming';
const blobName = `incoming/${jobId}/meeting.mp4`;
const blobs = BlobServiceClient.fromConnectionString(
  process.env.PRODUCER_INPUT_CONNECTION_STRING
);
// この例ではコンテナーを事前に作成しておく。
await blobs.getContainerClient(inputContainer).getBlockBlobClient(blobName)
  .uploadFile('meeting.mp4', { conditions: { ifNoneMatch: '*' } });

const request = {
  version: 1,
  jobId,
  input: { storage: 'default', container: inputContainer, blobName },
  output: { storage: 'default', container: 'audio-created', prefix: 'exports/meetings' },
  filename: 'meeting.mp4'
};
const queue = new QueueClient(
  process.env.PRODUCER_CONTROL_CONNECTION_STRING,
  'movie2audio-jobs'
);
// Queueも事前に作成するか、Functions側の初期化後に利用する。
await queue.sendMessage(Buffer.from(JSON.stringify(request), 'utf8').toString('base64'));
console.log(jobId); // 接続情報やメッセージ本文は出力しない。
```

Node.js SDKはこの例のように送信前にBase64化します。JavaなどのSDKでBase64自動エンコードを有効にした場合は、JSON文字列をそのまま渡します。SDK設定と手動処理で二重にエンコードしないでください。FunctionsホストはBase64をデコードしてワーカーへ渡します。

## 状態と結果を取得する

直接Queue投入後はワーカーが状態を作成します。初期状態Blobを送信側が事前に作る必要はありません。状態作成前は `GET /api/jobs/{jobId}` が404になる場合があります。

状態は制御用Storageの `movie2audio-jobs/{jobId}/status.json` に置き、`job`・正規化済みの `request`・成功した試行の `result` を記録します。状態APIは `job` と取得用URLだけを返し、内部のStorage参照は返しません。Function認証で次を呼び出せます。

```bash
curl --fail-with-body \
  -H "x-functions-key: $FUNCTIONS_HOST_KEY" \
  "https://YOUR_FUNCTION_APP.azurewebsites.net/api/jobs/$JOB_ID"
```

`job.status` が `succeeded` になると `resultUrl` が返ります。そのAPIを同じキーで呼び、M4Aを取得できます。`failed` なら `job.errorCode`・`job.errorMessage` を確認します。HTTP状態応答が200でも、変換成功とは限りません。

```bash
curl --fail-with-body \
  -H "x-functions-key: $FUNCTIONS_HOST_KEY" \
  "https://YOUR_FUNCTION_APP.azurewebsites.net/api/jobs/$JOB_ID/result" \
  --output audio.m4a
```

Storageから直接読む場合、成功状態の `result.storage`・`container`・`blobName` を使います。出力先の構造は次の通りです。prefixが空なら先頭の `{prefix}/` は付きません。

```text
{output.prefix}/{jobId}/results/{attemptUUID}/audio.m4a
```

`attemptUUID` はワーカーが試行ごとに作ります。成功した試行の音声だけを `result` で公開します。`result` は保存先に加え、`contentType`、`filename`、`sizeBytes`、`etag` を持ちます。既定は `audio/mp4`・`audio.m4a`、version 2でWAVを指定した場合は `audio/wav`・`audio.wav` です。パスを推測して未完了の試行を取得せず、成功状態の記録を使ってください。

## 重複・再試行・運用

同じID・同じ正規化済み依頼の終端状態（成功／失敗）は再変換しません。異なる依頼で同じIDを使っても既存の状態・結果を上書きしません。新しい変換や条件変更には新しいUUIDを使います。入力Blobもジョブごとに一意とし、処理が終わるまで上書き・削除しないでください。

更新されるBlobリースで同じジョブを排他制御し、試行ごとの保存パスと条件付き書き込みを使います。途中でホストが停止した場合などは再試行できますが、変換自体が常に1回しか走らないことは保証しません。公開される成功結果の一貫性と、処理回数の保証は別です。

不正動画、選択モードで未対応の音声、サイズ上限超過などは失敗として記録します。一時的なStorage障害・実行競合は再試行します。設定は `maxDequeueCount=10`、失敗時の `visibilityTimeout=1分`。上限を超えたメッセージは `movie2audio-jobs-poison` で失敗状態にします。不正JSONやUUIDなど、そもそも有効な依頼として解析できないものは状態Blobを作れません。継続する404の場合はQueueとpoison、ワーカーの状態を確認します。

`batchSize=1`、`newBatchThreshold=0`、動的同時実行を無効にし、Node.jsプロセス内の実行枠は同期HTTPと共有します。水平スケールすると別インスタンスで並列に処理できます。Queueは厳密なFIFOではありません。既定180秒の期限はQueueの各試行に適用し、Queueで待つ時間は含みません。

既定ではBlobを自動削除しません。保持設定を有効にすると、アプリが所有する入力コピー・成果物・試行出力を清掃します。外部原本は対象外です。作業用のローカル一時ファイルは処理後に削除します。新規コンテナーは非公開で作成し、既存コンテナーの公開設定は変更しません。

詳細は[非同期HTTP](../../../docs/APIDocs/movie2audio/jobs.html)と[Queue API資料](../../../docs/APIDocs/movie2audio/queue.html)を参照してください。

## Version 2：入力版・付加情報・結果通知

`version: 1` の既存依頼は引き続き使用できます。以下の拡張は `version: 2` を指定します。未知のキー・重複キー・型違いは引き続き拒否し、新しいフィールドをversion 1へ混ぜることはできません。

- `input.expectedETag`：任意。原本のETagを引用符も含めて指定し、不一致は `INPUT_VERSION_MISMATCH`。省略時も実際に読み取ったETagを記録します。
- `metadata`：任意の文字列マップ。最大16項目、キー64文字・値512文字・全体8KiB以下。IDやrevisionの引き継ぎに使い、秘密情報は含めません。
- `notification.queue`：任意。管理者が登録した結果Queueのエイリアス。未登録先は拒否します。

```json
{
  "version": 2,
  "jobId": "b6812181-8d03-4cb0-841a-225798074cf9",
  "input": {
    "storage": "source",
    "container": "documents-incoming",
    "blobName": "originals/video.mkv",
    "expectedETag": "\"0x8EXAMPLE\""
  },
  "output": {
    "storage": "archive",
    "container": "converted-results",
    "prefix": "exports"
  },
  "metadata": {
    "documentId": "document-123",
    "revision": "7"
  },
  "notification": {
    "queue": "completed"
  },
  "options": {
    "mode": "transcode",
    "format": "wav",
    "sampleRate": 16000,
    "channels": 1
  }
}
```

Movieのversion 2では `options` に音声形式を指定できます。既定は `mode: "copy"`・`format: "m4a"`。`transcode` ではM4A/AACまたはWAV/16bit PCMを選べます。`sampleRate` と `channels` は省略時に元の値を維持します。copyではレート・チャンネル変更を指定できません。WAVの成果物は `audio.wav`・`audio/wav` です。

`source`・`archive`・`completed` は事前登録が必要です。ETagは例をそのまま使わず、実際の原本から取得します。入力版が一致しても、その結果を最新として採用できるかは利用側で現在のrevisionと照合してください。

結果通知はversion 1のイベントで、`eventId`、`jobId`、`status`、`input`（実際の`eTag`）、`metadata`、`result`、`error`、`completedAt`を持ちます。失敗の `error` は固定の `code` と `retryable: false` を返します。ここでfalseはジョブが終端状態である意味です。一時障害の内部再試行中とは区別し、再実行が必要なら新しいjobIdを使います。

通知送信待ちは保存し、失敗時は通知だけを再送します。通知は重複し得るため、受信側でeventIdを照合します。イベントのJSONにはBase64を1回適用します。不正JSONなどjobId・通知先を確定できない依頼には通知できません。

成果物にはサイズ・SHA-256と実際の入力版を記録します。ETagは内容のハッシュではありません。再試行は別の保存先を使い、全出力が揃ってから成功状態を確定します。保存先を推測したり、Blob一覧から途中成果物を拾ったりしないでください。

Managed Identity、結果Queueの登録、リソース事前作成、保持期間は[共通の配置・運用手順](../../../docs/development.md#8-managed-identity閉域storage結果通知)を参照してください。保持機能は既定で無効です。有効時は所有成果物のみを清掃し、状態の保持を終了すると同じjobIdの重複判定も終了します。期限を過ぎて清掃済みの結果取得は `410 JOB_RESULT_EXPIRED` です。
