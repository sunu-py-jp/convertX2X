# Movie → Audio 利用・運用ガイド

動画の最初の音声トラックからAACを抽出し、`audio.m4a` として返すAzure Functionsです。動画を直接送る `POST /api/convert`、許可したHTTPS URLから取得する `POST /api/convert-url`、抽出結果を指定したBlobへ保存する `POST /api/convert-to-blob` を提供します。さらに非同期HTTPの `/api/jobs`・`/api/jobs-url` と、外部システムからの直接Queue依頼に対応します。Node.jsのFunctions v4モデルで実装し、すべて共通のFFmpeg抽出処理を利用します。

既定のcopyモードではAACを再エンコードせずにコピーするため、ビットレート・サンプリングレート・チャンネル数を変更しません。返すのはAAC音声を格納したM4Aコンテナー（`audio/mp4`）です。動画コンテナーから取り出す際にコンテナーや付随情報は変わるため、入力ファイルと出力ファイル全体のバイト一致を意味するものではありません。

入力動画はストリームで一時ディスクへ保存し、抽出を完了したM4AをHTTPストリームで返します。FFmpegの `-movflags +faststart` でM4Aの再生情報（`moov`）を音声データより前に配置します。抽出途中から音声を送り出す方式ではありません。返却後の再生・保存・Azure Speechなど他サービスへの送信は利用側が行います。この機能は文字起こしや他サービスへの自動送信を行いません。

[HTML APIリファレンス](../../../docs/APIDocs/movie2audio/index.html) / [開発者向け実装ガイド](../../../docs/movie2audio.md)

## 実行方式を選ぶ

| 目的 | 入口 | 成功時の応答 | 追加設定 |
| --- | --- | --- | --- |
| 動画ファイルから音声をその場で受け取る | `POST /api/convert` | `200`、M4Aストリーム | なし |
| 動画URLから音声をその場で受け取る | `POST /api/convert-url` | `200`、M4Aストリーム | 入力URLの許可ホスト |
| 呼び出しごとのSASで指定Blobへ保存する | `POST /api/convert-to-blob` | 保存完了後に `201`、結果JSON | 出力SASの許可ホスト。URL入力なら入力側も許可 |
| ファイルを受け付け、Queueで処理する | `POST /api/jobs` | `202`、ジョブID・状態URL | 制御用Storage接続 |
| URLからファイルを受け付け、Queueで処理する | `POST /api/jobs-url` | `202`、ジョブID・状態URL | 制御用Storage接続・入力URLの許可ホスト |
| 外部システムから直接依頼する | Queue `movie2audio-jobs` | 指定したBlobへM4A保存・状態更新 | 制御用Storage接続、必要なら追加の入出力接続 |

非同期HTTPと直接Queueは、共通の `GET /api/jobs/{id}` で状態を確認し、成功後に `GET /api/jobs/{id}/result` から取得できます。同期SAS保存の `output.sasUrl` と、非同期の登録済みStorageを選ぶ `output.storage` は別の契約です。ジョブAPI・QueueへSASを渡すことはできません。

## 対応する入力と出力

| 項目 | 動作 |
| --- | --- |
| 入力 | MP4・MOV、Matroska、AVI、MPEG-TS、FLV系の動画コンテナー。中身をFFprobeで確認 |
| 必須 | 動画ストリームと音声トラック。copyでは最初の音声がAACであること |
| 音声の選択 | 最初の音声トラックのみ。後続のAACトラックを探して選び直すことはしない |
| 非AAC音声 | copyでは422。transcodeで対応形式を明示的に変換 |
| 出力 | `audio.m4a`、`Content-Type: audio/mp4`、音声1トラック |
| 付随情報 | 動画・字幕・データトラック、入力メタデータ・チャプターを引き継がない |
| 呼び出し | 同期HTTP、ジョブIDを返す非同期HTTP、入出力Blobを参照する直接Queue。同期SAS保存も利用可能 |

同梱FFmpegは音声抽出に必要な機能だけを有効にしています。一般的なFFmpegコマンドで扱えるすべての形式に対応するわけではありません。HLS/DASHのプレイリスト、WebページのURL、DRMコンテンツは対象外です。動画の長さやエンコード条件による一律の処理時間は保証しません。

## ローカルで起動する

必要なものはNode.js 22または24、npm、Python 3.10以上、Azure Functions Core Tools v4です。`.nvmrc` は24を指定します。`npm start` はPythonの起動スクリプトを呼びます。JDK・Mavenは不要です。FFmpegの別途インストールや起動時ダウンロードは不要です。同梱バイナリの対応環境はLinux x64とmacOS Apple Siliconです。

```bash
cd functions/movie2audio
npm start
```

[Playground](http://localhost:7073/api/playground)で動画のアップロード、許可済みURLからの抽出を試せます。URL入力は環境変数の許可ホスト設定がある場合だけ有効です。Storage接続を設定すると「キューで実行」も選べ、受付・状態確認・音声の取得を順に試せます。画面は3秒おきに確認し、最大15分で待機を終了します。受付済みのジョブは継続します。Playgroundは同梱のHTML・CSS・JavaScriptで、フロントエンドビルドは不要です。

`npm start` は依存パッケージのインストールとSDK互換修正を行い、未作成なら `local.settings.example.json` から `local.settings.json` を作成して起動します。設定は `local.settings.json` の `Values` またはシェルの環境変数へ置き、変更後に再起動してください。シェルの環境変数を優先します。秘密情報を含む `local.settings.json` はGit管理しません。

`python3 scripts/run_local.py` でも起動できます。依存インストール済みなら `--skip-build` で省略できます。既定ポートは7073で、`--port 7083` のように変更できます。ソース変更後もホストを再起動します。

## ファイルを送る

```bash
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @sample.mp4 \
  'http://localhost:7073/api/convert' \
  --output audio.m4a
```

ボディは動画の生バイト列です。`multipart/*` と `application/json`は `415 RAW_BODY_REQUIRED` です。ファイルには `application/octet-stream`、URLのJSONには後述の `/convert-url` を使ってください。元のファイル名、出力形式、ビットレート、抽出範囲の指定は不要です。出力名は常に `audio.m4a` です。

Azure上ではFunction認証が必要です。実行するシェルの環境変数 `FUNCTIONS_HOST_KEY` にアプリのホストキーを設定し、ヘッダーで渡します。

```bash
curl --fail-with-body \
  -H "x-functions-key: $FUNCTIONS_HOST_KEY" \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @sample.mp4 \
  'https://YOUR_FUNCTION_APP.azurewebsites.net/api/convert' \
  --output audio.m4a
```

ローカルのCore Toolsは通常、Functionキー認証を強制しません。ホスト側の認証エラーは以下のアプリ独自JSON形式と異なる場合があります。

## URLを送る

`CONVERSION_URL_ALLOWED_HOSTS` に、取得を許可するホスト名をカンマ区切りで設定します。

```text
CONVERSION_URL_ALLOWED_HOSTS=media.example.com,exampleaccount.blob.core.windows.net
```

サブドメインを含めた完全一致です。`*.example.com` のようなワイルドカード、IPアドレス、スキームやパス付きの値は指定できません。未設定・空値では `POST /api/convert-url` を無効にします。

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  --data-binary '{"url":"https://media.example.com/videos/sample.mp4"}' \
  'http://localhost:7073/api/convert-url' \
  --output audio.m4a
```

JSONは空でない文字列の `url` だけを持つオブジェクトです。余分なフィールド、重複キー、複数のJSON値を拒否します。JSON全体は最大16KiB、URL文字列は最大8192文字です。Azureでは上の例に `x-functions-key` ヘッダーを加えます。

URLはHTTPS・443番ポートの直接ファイルURLに限ります。URL内のユーザー名・パスワード、フラグメント、リダイレクト、圧縮されたHTTPレスポンスは受け付けません。認証用Cookieや任意のHTTPヘッダーを追加する機能はありません。Blob SASのような署名付きクエリは利用できますが、そのURL自体を秘密情報として扱ってください。

許可ホストでもDNSの結果に内部・ループバック・リンクローカル・予約済みIPアドレスなどが含まれる場合は拒否します。検証済みのIPへ接続し、プロキシ・Cookie・自動リトライは使いません。FFmpegへURLを渡さず、上限付きで一時ファイルへ取得した後に抽出します。

## 抽出した音声をBlobへ保存する

`POST /api/convert-to-blob` は、動画と出力先SAS URLを受け付け、抽出したM4AをBlobへ保存してから `201 Created` のJSONを返します。Function認証が必要です。Queueへ登録して後から実行する方式ではありません。

管理者は出力先のAzure Blobホストを環境変数へ設定します。未設定・空値では `503 OUTPUT_STORAGE_DISABLED` です。

```text
CONVERSION_OUTPUT_ALLOWED_HOSTS=exampleaccount.blob.core.windows.net
```

Azure公開クラウドの `<account>.blob.core.windows.net` を完全一致で許可します。入力の `CONVERSION_URL_ALLOWED_HOSTS` とは独立した設定です。URLから動画を取得する場合は入力側も許可し、動画を直接アップロードする場合は出力側の許可だけで利用できます。

URL入力では、次のJSONを `storage-request.json` に保存します。`sasUrl` は発行済みの完全なSAS URLへ置き換えます。以下の有効期限・署名は説明用のダミーです。

```json
{
  "input": {
    "url": "https://media.example.com/videos/sample.mp4"
  },
  "output": {
    "sasUrl": "https://exampleaccount.blob.core.windows.net/results/jobs/job-123/audio.m4a?sv=2023-11-03&sr=b&sp=c&spr=https&se=EXPIRY_UTC&sig=SIGNATURE"
  }
}
```

```bash
curl --fail-with-body \
  -H "x-functions-key: $FUNCTIONS_HOST_KEY" \
  -H 'Content-Type: application/json' \
  --data-binary @storage-request.json \
  'https://YOUR_FUNCTION_APP.azurewebsites.net/api/convert-to-blob' \
  --output stored-result.json
```

動画を直接送る場合は、`storage-request.json` の内容を `{"output":{"sasUrl":"発行済みのSAS URL"}}` にして、動画とJSONをmultipartで送ります。

```bash
curl --fail-with-body \
  -H "x-functions-key: $FUNCTIONS_HOST_KEY" \
  --form 'request=@storage-request.json;type=application/json' \
  --form 'file=@sample.mp4;type=application/octet-stream' \
  'https://YOUR_FUNCTION_APP.azurewebsites.net/api/convert-to-blob' \
  --output stored-result.json
```

パートはJSONファイルの `request` と動画ファイルの `file` の各1つです。両方を `@` で送信し、ファイル名のないテキストフィールドは使いません。Content-Typeと境界文字列はcurlに生成させます。余分なパート・フィールド・重複キーは拒否します。SASをコマンドラインやFunctionのURLクエリに埋め込まず、JSONファイルをボディへ読み込ませます。JSONファイルは秘密情報として扱い、リポジトリやログに含めないでください。

JSON入力全体は最大32KiBです。multipartのJSONファイルは最大16KiB、本文全体は入力動画上限 + 64KiBで制限します。100MiBちょうどの動画をmultipartで送るには、Functionsホストの `FUNCTIONS_REQUEST_BODY_SIZE_LIMIT` も `104923136`（100MiB + 64KiB）以上に設定します。ファイルそのものの上限は変わりません。

SASは単一Blob専用（`sr=b`）で、権限は新規作成の `c` を推奨します。`w` と `cw` も許可しますが、読み取り・一覧・削除を含む広い権限は拒否します。HTTPS限定（`spr=https`）、`sv=2019-12-12` 以降、未来の `se` と `sig` が必須です。可能なら短時間のユーザー委任SASを発行してください。接続文字列、アカウントキー、任意のHTTPヘッダーは受け付けません。[SASの公式資料](https://learn.microsoft.com/en-us/azure/storage/common/storage-sas-overview)

```json
{
  "status": "succeeded",
  "output": {
    "blobUrl": "https://exampleaccount.blob.core.windows.net/results/jobs/job-123/audio.m4a",
    "bytes": 9323,
    "contentType": "audio/mp4",
    "etag": "\"0x8DD123456789ABC\""
  },
  "audio": {
    "codec": "aac",
    "mode": "copy"
  }
}
```

Blob名はSAS URLのパスそのものです。この例は `results` コンテナーの `jobs/job-123/audio.m4a` に保存し、自動でフォルダー名・ジョブIDを追加しません。コンテナーは既存のものを指定してください。`blobUrl` はSASを除いた識別用URLで、読み取り権限は付与しません。有効なETagをStorageから取得できない場合、`etag` は省略します。

`If-None-Match: *` で既存Blobへの上書きを禁止し、競合は `409 OUTPUT_BLOB_EXISTS` です。呼び出し側で一意な保存名を用意します。通信切断・タイムアウト後は保存済みの可能性があるため、アプリは自動再試行やBlob削除を行いません。利用側で結果を確認してから次の操作を決めてください。

保存先もHTTPS・443・公開IPの検証とDNS検証済みIPへの接続を使い、プロキシ・リダイレクトを利用しません。Private Endpointやカスタムドメインは対象外です。完成したM4Aを一時ディスクからストリームでアップロードし、保存完了まで同じ実行枠と処理期限を保持します。詳細な入力形式・エラーは [Blob保存API資料](../../../docs/APIDocs/movie2audio/storage.html) を参照してください。

## 非同期機能を有効にする

`CONVERSION_STORAGE_CONNECTION_STRING` に制御用Azure Storageの接続文字列を設定すると、非同期HTTPと直接Queueを有効にします。接続文字列もMI用の制御Storage設定もない場合、ジョブAPIは `503 ASYNC_DISABLED` となり、Queueトリガーも登録しません。同期HTTPは引き続き利用できます。

AzureではFunctionsホスト用の `AzureWebJobsStorage` も設定します。これは非同期の有効化とは別の設定です。ローカル起動スクリプトは、ホスト用接続が空なら制御用接続で補完します。Azuriteを標準ポートで起動済みなら、次の設定で試せます。

```bash
CONVERSION_STORAGE_CONNECTION_STRING='UseDevelopmentStorage=true' \
python3 scripts/run_local.py --skip-build
```

制御用StorageにはQueueと状態用コンテナーを作成します。どちらも名前は `movie2audio-jobs` です。HTTP受付の入力と出力も既定では同じStorageに保存します。直接Queueだけ、サーバーに登録した別Storageの入出力を選べます。

| Functions側の環境変数 | Queue JSONの参照 | 役割 |
| --- | --- | --- |
| `CONVERSION_STORAGE_CONNECTION_STRING` | `storage: "default"` または省略 | 制御用と既定の入出力 |
| `CONVERSION_INPUT_STORAGE_SOURCE` | `input.storage: "source"` | 追加の入力用Storage |
| `CONVERSION_OUTPUT_STORAGE_ARCHIVE` | `output.storage: "archive"` | 追加の出力用Storage |

登録名は `[a-z][a-z0-9_]{0,31}`、環境変数の接尾辞は大文字です。`default` は予約名です。入力用と出力用は独立した登録で、入力用の登録名をそのまま出力に使うことはできません。登録はアカウント単位で、コンテナーの許可リストではありません。URL／SAS用のホスト許可設定は、この登録済みStorage接続には適用しません。

接続情報とQueueへの投入権限は信頼できるシステムだけに付与してください。Functions側には入力の読み取り、制御用のQueue・状態更新、出力の保存・コンテナー作成が必要です。HTTP結果APIも使う場合、出力用接続には読み取り権限も必要です。登録名を分けるだけで接続の実権限は狭まりません。

## 非同期HTTPで依頼する

`POST /api/jobs` は動画の生バイト列を受け付けます。`/convert` と同じくmultipartやJSONは使いません。

```bash
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @sample.mp4 \
  'http://localhost:7073/api/jobs?filename=sample.mp4' \
  --output accepted.json
```

URLから受け付ける場合は `POST /api/jobs-url` へJSONを送ります。入力URLの許可ホスト設定が必要で、同期URL入力と同じ形式・サイズ・公開IP検証を使います。

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  --data-binary '{"url":"https://media.example.com/videos/sample.mp4"}' \
  'http://localhost:7073/api/jobs-url?filename=sample.mp4' \
  --output accepted.json
```

AzureではすべてのジョブAPIに `x-functions-key` を渡します。入力表示名は `filename` クエリ、次に `x-file-name` ヘッダーを使い、未指定なら `video.bin` です。クエリを使う日本語名はURLエンコードしてください。表示名のパス成分を除き、保存先やFFmpegの引数には使いません。

入力の取得・Blob保存・Queue登録を完了してから `202 Accepted` を返します。`/jobs-url` のURLダウンロードも受付中に行い、Queueには取得済みBlobの参照だけを残します。`202` は受付完了で、動画とAAC音声の検証・抽出はワーカーで行います。

```json
{
  "job": {
    "id": "f6603efc-2fa8-4466-8d09-6d7c3ed94c18",
    "status": "queued",
    "filename": "sample.mp4",
    "createdAt": "2026-09-16T00:00:00.000Z",
    "updatedAt": "2026-09-16T00:00:00.000Z",
    "sizeBytes": null,
    "errorCode": null,
    "errorMessage": null
  },
  "statusUrl": "/api/jobs/f6603efc-2fa8-4466-8d09-6d7c3ed94c18"
}
```

応答には `Location: /api/jobs/{id}` と `Retry-After: 3` も付きます。`statusUrl` はFunction Appの同じオリジンからの相対URLです。3秒程度の間隔で確認します。

```bash
JOB_ID=$(python3 -c 'import json; print(json.load(open("accepted.json"))["job"]["id"])')
curl --fail-with-body "http://localhost:7073/api/jobs/$JOB_ID"
```

状態は `queued` → `running` → `succeeded` または `failed` です。状態APIが200でも変換成功とは限りません。成功時は次のように `resultUrl` が追加され、`sizeBytes` に音声のバイト数が入ります。

```json
{
  "job": {
    "id": "f6603efc-2fa8-4466-8d09-6d7c3ed94c18",
    "status": "succeeded",
    "filename": "sample.mp4",
    "createdAt": "2026-09-16T00:00:00.000Z",
    "updatedAt": "2026-09-16T00:00:02.000Z",
    "sizeBytes": 9323,
    "errorCode": null,
    "errorMessage": null
  },
  "statusUrl": "/api/jobs/f6603efc-2fa8-4466-8d09-6d7c3ed94c18",
  "resultUrl": "/api/jobs/f6603efc-2fa8-4466-8d09-6d7c3ed94c18/result"
}
```

`succeeded` を確認してから取得します。成功前や失敗したジョブの結果APIは `409 JOB_NOT_READY` です。

```bash
curl --fail-with-body \
  "http://localhost:7073/api/jobs/$JOB_ID/result" \
  --output audio.m4a
```

`failed` の場合は `job.errorCode`・`job.errorMessage` を確認します。公開APIは内部のStorage参照や接続情報を返しません。Functionキーは利用者別のジョブアクセス制御ではありません。

HTTPの新規受付は呼び出しごとにUUIDを発行します。受付応答の通信切断などで同じHTTPリクエストを再送すると、別ジョブになる可能性があります。送信側でジョブIDを固定して再送を管理する場合は、次の直接Queue方式を使ってください。

[非同期HTTP・レスポンスの詳細](../../../docs/APIDocs/movie2audio/jobs.html)

## 外部システムからQueueへ直接依頼する

入力動画をBlobへ保存し、制御用Storageの `movie2audio-jobs` Queueへ次のJSONを送ります。HTTP受付・Playgroundを経由せず同じ抽出処理を利用できます。例は登録済みの別Storageを選ぶ形式です。同じStorageなら `storage` を省略するか `default` を指定します。

```json
{
  "version": 1,
  "jobId": "f6603efc-2fa8-4466-8d09-6d7c3ed94c18",
  "input": {
    "storage": "source",
    "container": "videos-incoming",
    "blobName": "incoming/f6603efc-2fa8-4466-8d09-6d7c3ed94c18/meeting.mp4"
  },
  "output": {
    "storage": "archive",
    "container": "audio-created",
    "prefix": "exports/meetings"
  },
  "filename": "meeting.mp4"
}
```

必須項目は整数の `version: 1`、UUIDの `jobId`、`input.container`・`input.blobName`、`output.container` です。`prefix` の省略時は空で、`filename` は任意の表示名です。ファイル本体・URL・SAS・キー・接続文字列をJSONへ含めません。未知のキー、重複キー、不正な型も拒否します。

デコード後48KiB以下のUTF-8 JSONを、**Base64で1回だけ**エンコードして送ります。Node.js SDKを使う同梱例は送信前に明示的にエンコードします。別SDKで自動エンコードを有効にした場合は、さらに手動エンコードをしないでください。

同梱の [QueueProducer.mjs](../examples/QueueProducer.mjs) は動画保存とQueue送信をまとめて実行します。[基本の依頼JSON](../examples/queue-request.json) または [別Storageの依頼JSON](../examples/queue-request-cross-account.json) をコピーし、ジョブごとに新しいUUIDと一意な入力Blob名へ変更してください。送信側の接続設定はFunctionsと同名の環境変数から読みます。`local.settings.json` は読みません。

```bash
node examples/QueueProducer.mjs ./sample.mp4 examples/queue-request.json
# 同じ入力を保存済みなら、同じ依頼のQueue送信だけを再試行
node examples/QueueProducer.mjs --queue-only examples/queue-request.json
```

直接Queueでも `GET /api/jobs/{jobId}` と `/result` を使えます。状態はワーカーが作るため、投入直後は404になる場合があります。有効なJSONやUUIDとして解析できない依頼には状態を作れません。

[直接Queueの開発者手順・独自Producerのコード](../docs/direct-queue.md) / [QueueのAPI仕様](../../../docs/APIDocs/movie2audio/queue.html)

## ジョブの保存先・再試行・並列処理

| 保存するもの | Storage・コンテナー | Blob名 |
| --- | --- | --- |
| HTTP受付の入力 | 制御用Storage、`movie2audio-jobs` | `{jobId}/input` |
| HTTP・直接Queue共通の状態 | 制御用Storage、`movie2audio-jobs` | `{jobId}/status.json` |
| HTTP受付の結果 | 制御用Storage、`movie2audio-jobs` | `{jobId}/results/{attemptUUID}/audio.m4a` |
| 直接Queueの入力 | `input.storage`・`input.container` | 指定した `input.blobName` |
| 直接Queueの結果 | `output.storage`・`output.container` | `{prefix}/{jobId}/results/{attemptUUID}/audio.m4a` |

`prefix` が空なら先頭の `{prefix}/` は付きません。出力はM4A 1つで、ZIPにはしません。試行ごとのUUIDはワーカーが発行し、成功した試行の結果だけを公開します。Storageから直接読む場合は、成功状態の `result.storage`・`container`・`blobName` を使い、保存パスを推測しないでください。

同じID・同じ正規化済み依頼の成功／失敗済みジョブは再変換しません。異なる依頼で既存ジョブを上書きすることもありません。新しい変換には新しいUUIDを使い、入力Blobは処理完了まで変更しないでください。更新されるBlobリースで排他制御し、条件付き書き込みと試行別の保存先を使います。ホスト停止などで抽出が複数回走る場合はあります。

不正動画、選択モードで未対応の音声、サイズ超過などは `failed` にします。一時的なStorage障害や実行競合は再試行し、10回の試行上限後は `movie2audio-jobs-poison` で失敗状態にします。不正JSONなど、解析できない依頼にはpoison処理でも状態を作れません。

プロセス内は同期HTTP・非同期HTTP受付・Queue抽出・結果取得が1件の実行枠を共有します。Queueは `batchSize=1`、`newBatchThreshold=0`、失敗時の `visibilityTimeout=1分`、動的同時実行は無効です。水平スケール時は別インスタンスで並列に処理でき、厳密なFIFO順序は保証しません。

既定180秒の期限は受付・Queueの各試行・結果取得ごとに適用します。Queueで待つ時間は含みません。Blobの自動削除は既定で無効です。保持設定を有効にすると、所有する入力コピー・成果物・試行出力を清掃します。作業用のローカル一時ファイルは処理後に削除します。

## レスポンスとエラー

`/convert`、`/convert-url`、成功ジョブの `/jobs/{id}/result` は同じ音声レスポンスです。`/convert-to-blob` は201の保存結果JSON、非同期HTTP受付は202のジョブJSONを返します。

```http
HTTP/1.1 200 OK
Content-Type: audio/mp4
Content-Disposition: attachment; filename="audio.m4a"
X-Audio-Codec: aac
X-Audio-Mode: copy
X-Content-Type-Options: nosniff
Cache-Control: no-store

<M4Aのバイト列>
```

レスポンスは完成済みの音声ファイル（M4A/WAV）をディスクから順に送信します。アプリは `Content-Length` を付けません。クライアントではステータスの確認に加え、レスポンスの読み取りが正常終了したことを確認してください。出力ファイルの読み出し中にエラー・期限超過が起きた場合はストリームを中断し、JSONエラーへの差し替えは行いません。転送中のキャンセルは、アプリへ伝搬した時点で処理します。

送信開始前のアプリ由来のエラーは `{"error":{"code":"...","message":"..."}}` です。コードごとのHTTPステータスは[設定・エラー一覧](../../../docs/APIDocs/movie2audio/settings.html#errors)を参照してください。503には `Retry-After: 3` を付けます。ただし、無効なURL機能やFFmpeg実行環境の問題は設定・実行環境を修正する必要があり、再試行だけでは解消しません。

`GET /api/capabilities` と `GET /api/playground/config` は、URL機能の有効状態、`asyncEnabled`、出力形式、適用中の上限などを匿名で返します。ホストの許可リストや秘密情報は返しません。Playgroundと画面アセットも匿名です。変換APIの認証とは別です。

## 設定と処理上限

| 環境変数 | 既定値 | 用途 |
| --- | --- | --- |
| `CONVERSION_MAX_INPUT_BYTES` | `104857600`（100MiB） | アップロード・URL取得共通の入力上限 |
| `CONVERSION_MAX_OUTPUT_BYTES` | `104857600`（100MiB） | M4A出力上限 |
| `CONVERSION_TIMEOUT_SECONDS` | `180` | 同期処理、非同期受付、Queue各試行、結果取得の各期限。1〜210秒。Queue待機時間は別 |
| `CONVERSION_URL_ALLOWED_HOSTS` | 空 | URL入力を有効にする完全一致ホスト名。最大32件、設定全体8192文字 |
| `CONVERSION_OUTPUT_ALLOWED_HOSTS` | 空 | `/convert-to-blob` を有効にするAzure Blob完全一致ホスト名。最大32件、設定全体8192文字。非同期のStorage登録とは別 |
| `CONVERSION_STORAGE_CONNECTION_STRING` | 空 | 設定すると非同期HTTP・直接Queueを有効化。制御と既定の入出力Storage |
| `CONVERSION_INPUT_STORAGE_{NAME}` | なし | 追加の入力用Storage接続 |
| `CONVERSION_OUTPUT_STORAGE_{NAME}` | なし | 追加の出力用Storage接続。結果APIを使う場合は読み取り権限も必要 |
| `AzureWebJobsStorage` | 配置環境で指定 | Functionsホスト用Storage。非同期の有効化とは別 |
| `FUNCTIONS_WORKER_RUNTIME` | `node` | Azure FunctionsのNode.jsワーカー |

数値設定は空なら既定値を使います。不正な値を黙って補正せず、起動時に拒否します。バイト上限は正の整数で、`2147483639` 以下です。アプリの上限を引き上げても、Azureホストやフロントのリクエスト上限が自動で変わるわけではありません。

アップロードを100MiB超へ拡大する場合、ホスト側の `FUNCTIONS_REQUEST_BODY_SIZE_LIMIT`（既定 `104857600` バイト）も合わせて設定します。ホストが先に拒否した応答は本アプリのJSON形式ではありません。ホスト設定の既定値は [Microsoft Learn](https://learn.microsoft.com/en-us/azure/azure-functions/node-http-stream#enable-streams) を参照してください。Node.jsのHTTPストリーミングはアプリ起動時に有効にしています。

入力・出力の作業ファイルはOSの一時ディレクトリへ置きます。入力動画を上限付きストリームで書き込み、検証・抽出を終えたM4Aを読み取りストリームで返すため、アプリが入力・出力ファイル全体をメモリーへ載せることはありません。ストリームのバッファとFFmpeg・FFprobe自身はインスタンスのメモリーを使います。出力ファイルの読み取りストリームが閉じたときに作業ファイルを削除します。正常終了に加え、エラーや伝搬したキャンセル、読み出し中の期限超過も同じ後片付けを通します。

Node.jsプロセスごとに実行枠は1件です。同期は音声応答のファイル読み取りストリームが閉じるまで、同期SAS保存はBlob保存が完了するまで、非同期HTTP受付はQueue登録まで、Queue処理は結果保存・状態更新まで、結果APIはファイル読み取りストリームが閉じるまで保持します。状態確認APIはこの枠を使いません。HTTP実行が競合した場合は `503 CONVERSION_BUSY`、Queue側は再試行となります。既定のホスト設定もHTTP同時実行1件・待機込み8件です。これはアプリ全体で1件に固定する設定ではなく、複数インスタンスや複数ワーカープロセスでは並列に動作します。

アプリ内の既定期限180秒は、入力受信からHTTP応答へのファイル読み出しまでを対象にします。その完了はクライアントの受信完了を意味せず、SDK・ホストのバッファに残るデータの転送は同じタイマーの対象外です。出力ストリームの開始前にクライアントが切断しても、URL取得や抽出が即座に停止するとは限りません。継続する処理は期限で制限します。`host.json` の `functionTimeout` は4分で、HTTP接続の維持時間を保証する設定ではありません。Azure上の4GB環境での性能・配置結果はローカルの検証結果と分けて確認してください。

## ビルド・テスト・同梱バイナリ

機能ディレクトリで実行します。

```bash
npm ci
npm test
npm run package
```

`@azure/functions` は4.16.2に固定し、インストール・起動・パッケージ作成時にHTTPストリーム用の互換修正を適用・確認します。SDKの送信量制御と切断通知の伝搬、実際のHTTPヘッダーの扱いを補正するものです。バージョンやファイルのSHA-256が想定外なら処理を停止します。依存を更新する場合は[開発者ガイド](../../../docs/movie2audio.md#sdk互換修正の保守)を参照してください。

ローカルホストを起動した状態では、実際のHTTP受付・音声抽出・エラーを確認できます。自作動画fixtureのAACパケットを抽出前後で比較し、再エンコードされていないことも検査します。

```bash
python3 scripts/test_http_e2e.py
# 入力を100MiBちょうどに拡張したMP4で、HTTP受付の境界も確認
python3 scripts/test_http_e2e.py --large-input
```

URLの成功ケースも確認する場合は、サーバー側で対象ホストを許可したうえで、テスト実行側の環境変数 `MOVIE_TEST_VIDEO_URL` に直接動画URLを設定します。Azureの変換APIを検証する場合は `--base https://YOUR_FUNCTION_APP.azurewebsites.net/api` と、環境変数 `FUNCTIONS_HOST_KEY` を使います。テストはURLやキーを結果に出力しません。100MiB境界テストは小さな動画へMP4の空き領域を追加したもので、長時間動画の処理性能を測るものではありません。

非同期の実連携は、標準ポート10000/10001/10002の専用Azuriteと、制御用接続に `UseDevelopmentStorage=true` を設定した専用のローカルFunctionsを起動して確認します。通常のPlaygroundとは別に7074で起動する例です。

```bash
# Azuriteは別ターミナルで起動済みとします。
CONVERSION_STORAGE_CONNECTION_STRING='UseDevelopmentStorage=true' \
python3 scripts/run_local.py --skip-build --port 7074
```

別ターミナルで、同じ機能ディレクトリから実行します。非同期E2Eの接続先は既定で `http://localhost:7074/api/` です。異なるポートなら `MOVIE_TEST_BASE_URL` に指定してください。

```bash
MOVIE_TEST_AZURITE=1 npm test
node scripts/test_async_e2e.mjs
```

HTTP受付、直接Queue、同梱送信例、実FFmpeg、重複、失敗、poison、M4A取得を検証します。テスト自身が作ったコンテナーとジョブBlobを終了時に削除します。非同期E2Eはローカル限定で、Azuriteを含む単体テストは `MOVIE_TEST_AZURITE=1` がないとスキップします。不正Queueメッセージの短時間テストには専用ホストの再試行設定と `MOVIE_TEST_FAST_RETRY=1` を使います。[起動・実行手順](../../../docs/development.md#movie--aacの実変換)を参照してください。

配布物は `dist/` に作成します。実行用の `src/`、`resources/`、本番用 `node_modules/`、設定、FFmpegのライセンス・ソース・ビルドスクリプトを含み、ローカルの秘密設定やテストは含めません。`npm run package` はAzureへの公開を行いません。

非同期機能を配置する場合は制御用Storage接続と、必要な入力・出力接続も設定します。登録した接続情報とQueue投入権限は信頼できるシステムに限定し、Blobの保持期間を決めてください。

配置先には、この機能専用のLinux x64 / Node.js 24 / Functions v4のFunction Appを用意し、`FUNCTIONS_WORKER_RUNTIME=node` と機能の環境変数を設定します。ホスト用Storageなど、Azure Functionsが要求する設定は配置先で用意してください。同期の `/convert` と `/convert-url` は変換用Blob・Queueの接続設定が不要です。`/convert-to-blob` には出力先ホストの許可設定と呼び出しごとのSASを用意します。非同期のジョブ・結果APIと直接Queueには制御用Storage接続が必要です。他の変換機能は独立したJava Function Appのままです。既存のJava版Movie2Audioを配置している場合は、Node.js用のランタイムと配布物へ切り替えます。

既存の配置先へ公開する場合は、配布物のディレクトリから実行します。

```bash
cd dist
func azure functionapp publish YOUR_FUNCTION_APP --no-build
```

FFmpeg 9.0.1とFFprobeを公式ソースからビルドし、`resources/ffmpeg/` に同梱します。Linux x64はmuslによる静的ビルド、macOS Apple Siliconはローカル用です。実行時はSHA-256を照合し、所有者のみアクセス可能な一時ディレクトリへ展開します。ネットワークプロトコルはビルド時に無効にしています。

再ビルドする場合は、Cコンパイラー・make・Docker・Pythonを用意して実行します。両プラットフォームをまとめて作る場合はApple Silicon Macが必要です。

```bash
python3 scripts/build_ffmpeg.py --target all
```

ビルド対象・取得元・ライセンス・対応するソース一式は [third-party/ffmpeg/README.md](../third-party/ffmpeg/README.md) を参照してください。アプリのコードは [MIT](../LICENSE)、同梱FFmpegは同梱するLGPLの条件が適用されます。FFmpegのソースアーカイブ・著作権表示・ライセンス文・ビルドスクリプトを再配布時も保持してください。

## Managed Identity・結果通知・保持期間

接続文字列に加えて、制御用Storageの `CONVERSION_STORAGE__blobServiceUri`・`CONVERSION_STORAGE__queueServiceUri` と任意の `__clientId` でManaged Identityを使えます。入力・出力の登録先にも `CONVERSION_INPUT_STORAGE_<ALIAS>__blobServiceUri` / `CONVERSION_OUTPUT_STORAGE_<ALIAS>__blobServiceUri` を使えます。同じ登録で接続文字列とMIを混在させません。

`CONVERSION_CREATE_RESOURCES=false` はコンテナー・Queueの自動作成を省略します。必要なリソースは配置前に用意してください。結果Queueは事前登録し、version 2の `notification.queue` で選択します。通知の送信待ちは永続化し、重複通知をeventIdで識別できます。

`CONVERSION_RESULT_RETENTION_DAYS`・`CONVERSION_STATE_RETENTION_DAYS` は既定0（自動削除無効）です。状態保持を有効にする場合は、結果保持も有効にし、それより長く設定します。期限切れ結果は清掃後に `410 JOB_RESULT_EXPIRED` になります。未完了のHTTP受付が残った場合も、保持設定に従って期限切れとして処理します。通常のQueue待ち・実行中のジョブを期限切れ成果物として削除しません。

結果通知先または保持を設定すると5分ごとのメンテナンス実行が発生します。未設定では定期処理は無効です。ホスト認証・RBAC・閉域DNS・保持と重複判定の関係・利用側の版照合は[共通の配置・運用手順](../../../docs/development.md#8-managed-identity閉域storage結果通知)、JSONの拡張は[直接Queueのversion 2](direct-queue.md#version-2入力版付加情報結果通知)を参照してください。

## 音声形式を選ぶ

省略時は従来どおりAACの無劣化コピーです。同期HTTP、URL入力、SAS保存、非同期HTTPの受付で、次のクエリを指定できます。JSON本文・multipartの構造は変えません。

| クエリ | 値・既定 |
| --- | --- |
| `audioMode` | `copy`（既定） / `transcode` |
| `audioFormat` | `m4a`（既定） / `wav`。WAVはtranscodeのみ |
| `sampleRate` | 任意。8000 / 11025 / 12000 / 16000 / 22050 / 24000 / 32000 / 44100 / 48000 / 64000 / 88200 / 96000 |
| `channels` | 任意。1〜8。省略時は元のチャンネル数を維持 |

copyではsampleRate・channelsを指定できません。transcodeではAAC、Opus、MP3、一般的なPCMを読み取り、M4A/AACまたはWAV/16bit PCMを生成します。M4Aへの再エンコードは音質が変わります。WAVは非圧縮で、16kHz・16bit・モノラルでも1時間約115MBとなり、既定100MiBの出力上限を超えます。出力サイズ・期限は非同期にも適用されます。

```sh
curl --fail-with-body \
  'http://localhost:7073/api/convert?audioMode=transcode&audioFormat=wav&sampleRate=16000&channels=1' \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @video.mkv --output audio.wav
```

直接Queueではversion 2の `options` に `{ "mode": "transcode", "format": "wav", "sampleRate": 16000, "channels": 1 }` を指定します。結果ダウンロードは保存された形式を返し、取得時のクエリで再変換しません。M4Aは `audio/mp4`・`audio.m4a`、WAVは `audio/wav`・`audio.wav`、`X-Audio-Mode` はcopyまたはtranscodeです。

最初の音声トラックを選ぶ規則は共通です。音声トラックが存在しない場合の `NO_AUDIO_STREAM` は、トラック内の音が無音であることとは区別します。任意のFFmpeg引数、音量調整、文字起こし、ライブ変換は提供しません。処理済みの音声ファイルを読み取りストリームで返します。
