# HTTPを経由せずQueueから変換する

入力OfficeファイルをBlobへアップロードし、制御用Storageの `office2md-jobs` Queueに次のJSONを送ります。HTTPによるジョブ登録や、状態Blobの事前作成は不要です。ワーカーが状態を作り、同期HTTPと同じ変換処理を呼びます。

```json
{
  "version": 1,
  "jobId": "fc2e587d-9369-4a90-8b03-815589d5d4a3",
  "input": {
    "container": "excel-inputs",
    "blobName": "requests/fc2e587d-9369-4a90-8b03-815589d5d4a3/sample.xlsx"
  },
  "output": {
    "container": "excel-results",
    "prefix": "markdown"
  },
  "filename": "sample.xlsx"
}
```

[同一Storage用サンプル](../examples/queue-request.json) と [別Storage用サンプル](../examples/queue-request-cross-account.json) を用意しています。新しい変換ごとにUUIDと入力Blobパスを変えてください。

## プロトコル

| 項目 | 内容 |
| --- | --- |
| `version` | 整数 `1` または `2`。以下の基本例はversion 1 |
| `jobId` | 通常のハイフン付きUUID。新しい変換では新しいIDを使う |
| `input.container` / `input.blobName` | アップロード済みOfficeファイルのBlob参照 |
| `input.storage` | 入力用Storageの登録名。省略・nullは `default` |
| `output.container` | 成果物の保存先コンテナー。存在しなければ非公開で作成 |
| `output.prefix` | 保存先の接頭辞。省略・null・空文字はコンテナー直下 |
| `output.storage` | 出力用Storageの登録名。省略・nullは `default` |
| `filename` | 任意のファイル名。省略時は入力Blobの末尾名。`.xlsx` / `.xls` / `.docx` / `.pptx` が必要 |

UTF-8 JSON本体は48 KiB以下です。未知のフィールド、重複キー、文字列から数値への暗黙変換は受け付けません。`options`、`output.mode`、接続文字列、SAS、任意のURLは契約にありません。Blobのパスに `.` / `..` の区間や制御文字は指定できません。

QueueメッセージはBase64を1回だけ適用します。Java SDKなら `QueueClientBuilder.messageEncoding(QueueMessageEncoding.BASE64)` を設定し、`sendMessage` にはJSON文字列をそのまま渡します。自分でBase64化した文字列をさらにSDKでエンコードしないでください。

同じID・正規化後に同じリクエストの重複配送は、完了状態を維持します。同じIDで入力・保存先などを変更しても元のジョブを上書きしません。失敗済みのジョブを新たに変換したい場合も新しいIDを使います。投入後に同じ入力Blobを置き換えないでください。

## Storageの登録

Queueと状態Blobは、常に `CONVERSION_STORAGE_CONNECTION_STRING` が指す制御用Storageを使います。`default` は入力・出力ともこのStorageです。別Storageを使う場合は、Functionsの実行環境に次の設定を追加します。

| 環境変数 | JSONの指定 | 用途 |
| --- | --- | --- |
| `CONVERSION_INPUT_STORAGE_SOURCE` | `input.storage: "source"` | 入力Blobの読み取り |
| `CONVERSION_OUTPUT_STORAGE_ARCHIVE` | `output.storage: "archive"` | 成果物の保存・取得 |

JSON内の登録名は `[a-z][a-z0-9_]{0,31}` に従う小文字で、`default` は予約済みです。環境変数の接尾辞は大文字にします。入力・出力の登録は別々で、出力用だけに登録したStorageを入力として使うことはできません。未知の登録名は拒否します。登録する接続文字列は、その用途に必要な権限を持つものを管理者側で用意します。アカウントキーを使う接続文字列では、登録名を分けるだけでStorage自体の権限が制限されるわけではありません。

ワーカーは入力を読み取り、出力Blobを作成します。出力コンテナーの自動作成は `CONVERSION_CREATE_RESOURCES` に従います。HTTPからの成果物取得とZIP作成にも出力Blobの読み取り権限が必要です。以下の送信サンプルは入力コンテナーとBlobを作成するため、送信側には入力の書き込み権限も必要です。接続文字列をQueueメッセージやソースコードに含めず、環境変数などで安全に渡してください。

## Javaから送る

[QueueProducer.java](../examples/QueueProducer.java) はこのモジュールの公開リクエスト型でJSONを検証し、入力を上書きせずにアップロードしてQueueへ送ります。事前に必要な接続設定を実行環境へ渡してください。送信側は出力Storageの資格情報を必要としません。

```sh
# functions/office2md でビルドしてから実行
./mvnw package
java --class-path 'target/azure-functions/office2md-local/*:target/azure-functions/office2md-local/lib/*' \
  examples/QueueProducer.java sample.xlsx examples/queue-request.json
```

これはmacOS/Linuxのクラスパス指定です。Windowsではクラスパスの区切りを `;` にします。成功時はジョブIDだけを表示します。入力アップロード後のQueue送信が失敗した場合、入力Blobは残ります。新しいファイルを上書きせず、元の入力と同じJSONでQueue投入を再試行してください。

## 状態と成果物を読む

制御用StorageのBlobコンテナー `office2md-jobs` に `{jobId}/status.json` を保存します。状態レコードは次の形です。

```text
job:     id, status, filename, createdAt, updatedAt,
         sectionCount, warningCount, errorCode, errorMessage
request: 正規化した投入リクエスト
result:  storage, container, sectionCount, warningCount,
         artifacts: [{path, blobName, contentType, sizeBytes, eTag}, ...]
```

`job.status` が `succeeded` になったら、`result.artifacts` の `blobName` を使って各成果物を取得します。`result.storage` はサーバーに登録した出力Storage名です。状態・成果物の取得にはBlobの認証が必要です。HTTPの状態APIはこの内部レコードをそのまま返さず、ジョブ情報と取得用URLだけを公開します。

実際の保存先は `{output.prefix}/{jobId}/results/{attemptId}/document.md`、同じ場所の `report.json` と `images/...` です。`attemptId` は実行時に生成するUUIDで、成果物ごとのパスは状態レコードから取得してください。全ファイルの保存が済んだ試行だけを成功状態に公開します。途中で失敗したBlobや別の試行を列挙して結果に混ぜないでください。ZIPは保存せず、必要ならHTTPの `/api/jobs/{id}/archive` で生成します。

HTTPの `/result`、`/report`、`/images/{assetName}` も利用できます。`/api/jobs/{id}` が返すURLに `x-functions-key` ヘッダーを付けてアクセスします。StorageのキーとFunction Appのホストキーは別のものです。

非一時的な変換エラーは `failed` になります。一時的な処理エラーはQueueの設定に従って再試行され、上限後は `office2md-jobs-poison` に移ります。構文が壊れたメッセージなど、ジョブを特定できない入力では状態レコードを作れません。入力・状態・完了成果物・失敗した試行のBlobについて、保持期間と清掃方法を運用側で決めてください。

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
    "blobName": "originals/book.xlsx",
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
  }
}
```

Officeの成果物にはMarkdown・report・画像一式と、それらの参照・サイズ・SHA-256を持つManifestがあります。通知には大量の画像一覧を埋め込まず、確定した成果物の参照を返します。

`source`・`archive`・`completed` は事前登録が必要です。ETagは例をそのまま使わず、実際の原本から取得します。入力版が一致しても、その結果を最新として採用できるかは利用側で現在のrevisionと照合してください。

結果通知はversion 1のイベントで、`eventId`、`jobId`、`status`、`input`（実際の`eTag`）、`metadata`、`result`、`error`、`completedAt`を持ちます。失敗の `error` は固定の `code` と `retryable: false` を返します。ここでfalseはジョブが終端状態である意味です。一時障害の内部再試行中とは区別し、再実行が必要なら新しいjobIdを使います。

通知送信待ちは保存し、失敗時は通知だけを再送します。通知は重複し得るため、受信側でeventIdを照合します。イベントのJSONにはBase64を1回適用します。不正JSONなどjobId・通知先を確定できない依頼には通知できません。

成果物にはサイズ・SHA-256と実際の入力版を記録します。ETagは内容のハッシュではありません。再試行は別の保存先を使い、全出力が揃ってから成功状態を確定します。保存先を推測したり、Blob一覧から途中成果物を拾ったりしないでください。

Managed Identity、結果Queueの登録、リソース事前作成、保持期間は[共通の配置・運用手順](../../../docs/development.md#8-managed-identity閉域storage結果通知)を参照してください。保持機能は既定で無効です。有効時は所有成果物のみを清掃し、状態の保持を終了すると同じjobIdの重複判定も終了します。期限を過ぎて清掃済みの結果取得は `410 JOB_RESULT_EXPIRED` です。
