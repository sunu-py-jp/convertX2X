# Queue から直接変換する

HTTP API を使わず、外部のアプリから Azure Storage Queue `conversion-jobs` に JSON を送れます。送信側が元ファイルを Blob に保存してからメッセージを登録します。状態の初期登録と画像変換はワーカーが行います。

## メッセージ

同じ Storage アカウント内で処理する例は [`examples/queue-request.json`](../examples/queue-request.json) です。
ページごとの画像を ZIP にせず保存する例は [`examples/queue-request-images.json`](../examples/queue-request-images.json) です。

```json
{
  "version": 1,
  "jobId": "f6603efc-2fa8-4466-8d09-6d7c3ed94c18",
  "input": {
    "container": "documents-incoming",
    "blobName": "incoming/f6603efc-2fa8-4466-8d09-6d7c3ed94c18/presentation.pptx"
  },
  "output": {
    "container": "images-created",
    "prefix": "exports/slides"
  },
  "filename": "presentation.pptx"
}
```

| フィールド | 内容 |
| --- | --- |
| `version` | `1`。 |
| `jobId` | 依頼ごとの UUID。別の変換には新しい UUID を使います。 |
| `input.container` / `input.blobName` | アップロード済みの入力 Blob のコンテナー名とパス。 |
| `output.container` / `output.prefix` | 出力先のコンテナー名とプレフィックス。`prefix` は省略または `null` なら空文字。未作成の出力コンテナーはワーカーが非公開で作成します。 |
| `output.mode` | `zip` または `images`。省略または `null` なら `zip`。`images` はページごとの画像 Blob と一覧の `manifest.json` を保存します。 |
| `filename` | 任意の表示用ファイル名。省略時は入力 Blob の末尾の名前を使用します。 |
| `options` | 任意。省略時は元のページ寸法を 96 dpi で、全ページを PNG に画像化します。まとめ方は `output.mode` に従います。 |
| `options.format` | `png` または `jpeg`。省略または `null` なら `png`。 |
| `options.width` | 任意の正の整数。横幅を px で指定し、縦横比を保ちます。省略または `null` なら 96 dpi。 |
| `options.page` | 任意の正の整数。1 始まりのページ番号。指定時はその 1 枚、省略または `null` なら全ページ。`zip` モードでも指定時は従来通り画像 1 枚を返します。 |
| `input.storage` / `output.storage` | 任意の登録済み Storage エイリアス。省略または `null` なら `default`。以下の別アカウント設定を参照。 |

元ファイルは `.ppt`、`.pptx`、`.pdf` に対応します。ファイルサイズ・ページ数・画像の画素数などの上限は HTTP 経由と共通です。

JSON は UTF-8 で最大 48 KiB。未定義のフィールド、同じキーの重複、不正な型は拒否します。`options` を使わない場合はフィールド自体を省略してください。

## Java から送る

[`examples/QueueProducer.java`](../examples/QueueProducer.java) は、入力ファイルをアップロードしてから JSON を Queue に送る実行例です。認証情報は環境変数からだけ取得します。

1. Functions を `./mvnw package` でビルドします。実行例は生成された Azure SDK と Jackson の JAR を利用します。
2. 送信側プロセスの `CONVERSION_STORAGE_CONNECTION_STRING` に、Functions と同じ制御用 Storage アカウントの接続設定を用意します。
3. 新規依頼では JSON の **`jobId` と `input.blobName` の両方**を変更します。サンプルの入力パスに含まれる UUID も新しい `jobId` に置き換え、入力・出力のコンテナーを指定してください。
4. 次を実行します。成功時にジョブ ID を表示します。

```bash
java --class-path 'target/azure-functions/slide2image-local/lib/*' \
  examples/QueueProducer.java ./presentation.pptx examples/queue-request.json
```

この実行例は既存の入力 Blob を上書きしません。入力アップロード済みで Queue 送信だけを再試行する場合は、同じ JSON を `queue.sendMessage(json)` で再送します。

Queue クライアントには **`QueueMessageEncoding.BASE64`** を設定します。`host.json` の `messageEncoding: "base64"` と組み合わせるためです。JSON を事前に Base64 化せず、SDK に 1 回だけエンコードさせてください。

## 状態と結果の取得

Queue と状態 Blob は、常に `CONVERSION_STORAGE_CONNECTION_STRING` の Storage アカウントに置かれます。外部の送信側が事前に状態ファイルを作る必要はありません。

状態はコンテナー `conversion-jobs` の **`{jobId}/status.json`** に保存されます。Queue 登録直後はまだ存在しないことがあるため、初回の `404` は未作成として待って再取得します。取得・ポーリングには Blob SDK を利用できます。

直接 Queue に登録したジョブも、`GET /api/jobs/{jobId}` で状態、`GET /api/jobs/{jobId}/result` で成功結果を取得できます。Azure 上で HTTP API を利用するときは `x-functions-key` に Host キーを渡します。これは Storage の接続文字列・キーとは別の認証です。状態 API は Blob の内部保存先を返さず、成功時に結果取得 API の `resultUrl` を返します。

状態 JSON の `job.status` は `queued`、`running`、`succeeded`、`failed` のいずれかです。`failed` の場合は `job.errorCode` と `job.errorMessage` を確認します。`zip` モードで全ページの変換が成功すると、状態 Blob の `result` には次の情報が入ります。

```json
{
  "storage": "default",
  "container": "images-created",
  "blobName": "exports/slides/{jobId}/results/{attemptUUID}/presentation.zip",
  "contentType": "application/zip",
  "filename": "presentation.zip",
  "pageCount": 2
}
```

上のパスは構造の例です。**ダウンロードには必ず実際の `result.storage`、`result.container`、`result.blobName` を使います。** 再試行ごとに異なるパスへ書き込むため、出力先を推測しないでください。状態ファイルには `job`、`result` に加えて、正規化された依頼 `request` も保存されます。

同一の `jobId` と同じ内容の再送は重複依頼として扱います。`succeeded`・`failed` のどちらも変換を繰り返しません。失敗後に条件を直してやり直す場合も、新しい `jobId` と入力 Blob パスで依頼してください。同じ `jobId` に異なる入力・出力・変換設定・表示用ファイル名を送っても、既存のジョブや結果は上書きしません。処理が終わるまで入力 Blob の内容を変更・削除しないでください。

JSON や UUID 自体が不正なメッセージには状態ファイルを作れません。長時間状態が見つからない場合は、ワーカーの稼働状態と Functions のログも確認してください。

## ページごとの画像を保存する

`output.mode: "images"` を指定すると、ZIP を作らず、変換した画像を 1 枚ずつ Blob に保存します。PNG なら `page-0001.png`、JPEG なら `page-0001.jpeg` の名前です。`options.page: 2` のようにページを限定した場合は、元の番号を保った `page-0002.png` など 1 枚だけを保存します。

全画像の保存後に、同じ処理試行のディレクトリへ `manifest.json` を保存してジョブを成功にします。

```text
{output.prefix}/{jobId}/results/{attemptUUID}/
  page-0001.png
  page-0002.png
  manifest.json
```

状態 Blob の `result` は ZIP ではなく、この manifest を指します。`contentType` は `application/json`、`filename` は `manifest.json`、`pageCount` は実際に保存した画像の枚数です。manifest の構造は次のとおりです。以下はページ 2 のみを保存した場合の例です。

```json
{
  "version": 1,
  "jobId": "{jobId}",
  "mode": "images",
  "pageCount": 1,
  "images": [
    {
      "page": 2,
      "storage": "default",
      "container": "images-created",
      "blobName": "exports/pages/{jobId}/results/{attemptUUID}/page-0002.png",
      "contentType": "image/png",
      "filename": "page-0002.png",
      "sizeBytes": 12345
    }
  ]
}
```

`sizeBytes` は各画像 Blob の実際のバイト数です。画像の取得には、manifest の各項目の `storage`、`container`、`blobName` を使います。`storage` のエイリアスは `output.storage` と同じです。入力・出力の別アカウント設定とも組み合わせられます。

**`GET /api/jobs/{jobId}/result` でも同じ manifest JSON を返します。** HTTP の認証はこれまで通りで、状態 API 自体は内部保存先を公開しません。個別画像のダウンロードは、manifest の参照先へ読み取り権限のある Blob SDK などで行います。

画像の合計サイズと manifest の合計は `CONVERSION_MAX_OUTPUT_BYTES` の上限内で処理します。途中で失敗した試行の画像が残る場合がありますが、成功したジョブの結果としては公開されません。再試行は別の `attemptUUID` に保存します。コンテナー一覧から結果を推測せず、`succeeded` の状態が指す manifest を使用してください。

## 別の Storage アカウントを使う

入力元・出力先として使うアカウントは、Functions の環境変数で事前登録します。例えば入力を `source`、出力を `archive` とすると次の設定になります。

| Functions の環境変数 | 用途 |
| --- | --- |
| `CONVERSION_STORAGE_CONNECTION_STRING` | 制御用アカウント。Queue と状態 Blob、`default` の入力・出力。 |
| `CONVERSION_INPUT_STORAGE_SOURCE` | `input.storage: "source"` の入力 Blob を読むための接続設定。 |
| `CONVERSION_OUTPUT_STORAGE_ARCHIVE` | `output.storage: "archive"` に結果を保存するための接続設定。 |

メッセージ例は [`examples/queue-request-cross-account.json`](../examples/queue-request-cross-account.json) です。入力と出力の登録は別々です。入力として登録したエイリアスを、出力側でも自動的に使えるわけではありません。未登録のエイリアスは拒否されます。

JSON のエイリアスは小文字で、`[a-z][a-z0-9_]{0,31}` の形式です。環境変数では対応する部分を大文字にします。`default` は制御用アカウントを指す予約名です。

メッセージには **エイリアスと Blob の名前だけ**を渡します。接続文字列、アカウントキー、SAS、URL は埋め込みません。送信側の Java 実行例にも `CONVERSION_INPUT_STORAGE_SOURCE` を設定すると、そのアカウントへ元ファイルをアップロードします。送信側は入力を書き込める認証情報が必要ですが、Functions の入力接続は読み取りに必要な権限で構成できます。結果を読む側にも、出力先を読み取れる認証情報を別途用意してください。

登録はアカウント単位で、コンテナー単位の許可リストではありません。Queueへの送信権限は、登録済み入力からの変換と登録済み出力への保存を許可するシステムに限定してください。接続に与える権限でアクセス範囲を制限し、既存の出力コンテナーは運用側で非公開に設定します。既存コンテナーの公開設定をワーカーが変更することはありません。

現在のワーカーは出力保存時に `createIfNotExists()` を呼びます。出力接続には Blob の書き込みに加えてコンテナー作成操作を許可する権限が必要です。HTTP 経由で結果を取得する場合は、Functions の出力接続にも読み取り権限が必要です。Java の送信例も入力コンテナーと Queue の作成操作を行います。[コンテナー作成の認証・権限](https://learn.microsoft.com/en-us/rest/api/storageservices/create-container#authorization)を参照してください。

## ローカルで検証する

```bash
./mvnw package
python3 scripts/test_async_e2e.py
```

この検証は専用の Azurite と Functions を起動し、既存の HTTP→Queue 経路に加えて、直接 Queue への JSON 登録、状態 Blob のポーリング、指定出力先の ZIP と画像寸法、同一依頼の再送、別アカウント間の入力・出力を確認します。`images` モードでは 3 形式の入力、JPEG の単ページ指定、別アカウントの保存先、manifest と個別画像の整合性、ZIP が作られないこと、重複配信で画像が増えないこと、HTTP からの manifest 取得も検証します。接続設定は隔離したテスト用の環境変数と権限を制限した一時設定ファイルだけで扱います。
