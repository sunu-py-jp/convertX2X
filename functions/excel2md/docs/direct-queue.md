# HTTPを経由せずQueueから変換する

入力ExcelをBlobへアップロードし、制御用Storageの `excel2md-jobs` Queueに次のJSONを送ります。HTTPによるジョブ登録や、状態Blobの事前作成は不要です。ワーカーが状態を作り、同期HTTPと同じ変換処理を呼びます。

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
| `version` | 整数 `1` |
| `jobId` | 通常のハイフン付きUUID。新しい変換では新しいIDを使う |
| `input.container` / `input.blobName` | アップロード済みExcelのBlob参照 |
| `input.storage` | 入力用Storageの登録名。省略・nullは `default` |
| `output.container` | 成果物の保存先コンテナー。存在しなければ非公開で作成 |
| `output.prefix` | 保存先の接頭辞。省略・null・空文字はコンテナー直下 |
| `output.storage` | 出力用Storageの登録名。省略・nullは `default` |
| `filename` | 任意のファイル名。省略時は入力Blobの末尾名。`.xlsx` / `.xls` が必要 |

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

ワーカーは入力を読み取り、出力コンテナー・Blobを作成します。HTTPからの成果物取得とZIP作成にも出力Blobの読み取り権限が必要です。以下の送信サンプルは入力コンテナーとBlobを作成するため、送信側には入力の書き込み権限も必要です。接続文字列をQueueメッセージやソースコードに含めず、環境変数などで安全に渡してください。

## Javaから送る

[QueueProducer.java](../examples/QueueProducer.java) はこのモジュールの公開リクエスト型でJSONを検証し、入力を上書きせずにアップロードしてQueueへ送ります。事前に必要な接続設定を実行環境へ渡してください。送信側は出力Storageの資格情報を必要としません。

```sh
# functions/excel2md でビルドしてから実行
./mvnw package
java --class-path 'target/azure-functions/excel2md-local/*:target/azure-functions/excel2md-local/lib/*' \
  examples/QueueProducer.java sample.xlsx examples/queue-request.json
```

これはmacOS/Linuxのクラスパス指定です。Windowsではクラスパスの区切りを `;` にします。成功時はジョブIDだけを表示します。入力アップロード後のQueue送信が失敗した場合、入力Blobは残ります。新しいファイルを上書きせず、元の入力と同じJSONでQueue投入を再試行してください。

## 状態と成果物を読む

制御用StorageのBlobコンテナー `excel2md-jobs` に `{jobId}/status.json` を保存します。状態レコードは次の形です。

```text
job:     id, status, filename, createdAt, updatedAt,
         sheetCount, warningCount, errorCode, errorMessage
request: 正規化した投入リクエスト
result:  storage, container, sheetCount, warningCount,
         artifacts: [{path, blobName, contentType, sizeBytes, eTag}, ...]
```

`job.status` が `succeeded` になったら、`result.artifacts` の `blobName` を使って各成果物を取得します。`result.storage` はサーバーに登録した出力Storage名です。状態・成果物の取得にはBlobの認証が必要です。HTTPの状態APIはこの内部レコードをそのまま返さず、ジョブ情報と取得用URLだけを公開します。

実際の保存先は `{output.prefix}/{jobId}/results/{attemptId}/document.md`、同じ場所の `report.json` と `images/...` です。`attemptId` は実行時に生成するUUIDで、成果物ごとのパスは状態レコードから取得してください。全ファイルの保存が済んだ試行だけを成功状態に公開します。途中で失敗したBlobや別の試行を列挙して結果に混ぜないでください。ZIPは保存せず、必要ならHTTPの `/api/jobs/{id}/archive` で生成します。

HTTPの `/result`、`/report`、`/images/{assetName}` も利用できます。`/api/jobs/{id}` が返すURLに `x-functions-key` ヘッダーを付けてアクセスします。StorageのキーとFunction Appのホストキーは別のものです。

非一時的な変換エラーは `failed` になります。一時的な処理エラーはQueueの設定に従って再試行され、上限後は `excel2md-jobs-poison` に移ります。構文が壊れたメッセージなど、ジョブを特定できない入力では状態レコードを作れません。入力・状態・完了成果物・失敗した試行のBlobについて、保持期間と清掃方法を運用側で決めてください。
