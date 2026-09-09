# excel2md

Excel（`.xlsx` / `.xls`）からMarkdown・画像・変換情報を取り出す、Java 21のAzure Functionsアプリです。ブラウザ用の簡単なPlayground、同期HTTP、非同期Queueが同じ変換処理を使います。このディレクトリだけでビルド・起動・デプロイできます。

開発・保守向けの構成と変更箇所は [Excelの開発者ガイド](../../docs/excel2md.md)、環境構築・検証・デプロイは [共通の作業手順](../../docs/development.md) を参照してください。このREADMEはAPI・設定・変換ルールの利用手順です。

## ローカルで試す

Java 21 JDK、Python 3.10以上、Azure Functions Core Tools v4（`func`）を用意します。Mavenは同梱のWrapperを使います。

```sh
cd functions/excel2md
cp local.settings.example.json local.settings.json
python3 scripts/run_local.py
```

<http://localhost:7072/api/playground> を開き、Excelを選んで「Markdownに変換」を押します。Markdownのプレビュー・ソース・警告を確認し、ZIPを保存できます。画面用のNode.js、npm、CDNは不要です。既存のビルドを使う場合は `--skip-build`、ポート変更は `--port 7082` を指定します。

Storage接続を設定しなければ同期だけが有効です。非同期を使う場合は、実行環境の `CONVERSION_STORAGE_CONNECTION_STRING` または `local.settings.json` の同名設定に接続文字列を設定して起動します。Azuriteなら、先にBlob・Queueサービスを起動し、値を `UseDevelopmentStorage=true` にします。接続文字列やキーをリポジトリに保存しないでください。

起動スクリプトは接続設定からQueueバインド用の別名 `CONVERSION_QUEUE_CONNECTION_STRING` と `AzureWebJobs.ProcessConversion.Disabled` / `AzureWebJobs.PoisonConversion.Disabled` を生成します。Javaの設定だけでは、Javaの起動前にAzure Functionsホストが読み込むQueueリスナーを無効にできません。Storageなしのときも、無効化したバインドを読み込めるよう別名にはエミュレーター形式の値を入れます。ローカルの `AzureWebJobsStorage` が空なら、非同期用Storageをホスト用にも使います。

文章・罫線表・書式・数式キャッシュ・画像・基本図形・グループと、未対応時の警告をまとめて試す [包括テスト用Excel](samples/full-feature.xlsx) を用意しています。[サンプルの内容と再生成・実HTTP/Queue検証手順](samples/README.md) も参照してください。

## 出力と変換ルール

同期HTTPは常に `document.zip` を返します。画像がなくても `document.md` と `report.json` が入ります。

```text
document.md
report.json
images/image-0001.png     # 貼り付け画像。元がJPEGなら .jpg など
images/diagram-0001.png   # 対応する基本図形の簡易描画
```

Markdownからは `![画像](images/image-0001.png)` のような相対パスで参照します。ZIPを展開した後も、`document.md` と `images/` を同じ場所に置いて利用できます。

- 表示シートを元の順序で出力し、シート名をH1にします。本文からH2やタイトルを推測しません。非表示のシート・行・列は除外します。
- セルに直接設定された罫線から、外周と内部分割を確認できる矩形の表をMarkdown表にします。Excelの画面グリッド線、単独の囲み、下線だけでは表にしません。曖昧な範囲は本文として残し、警告を記録します。
- 検出した表の範囲とExcelテーブル定義が一致し、ヘッダー行が有効な場合だけ、その先頭行をヘッダーにします。それ以外は空のヘッダーを追加し、元の全行をデータとして残します。結合セルは左上の値を一度出力し、続きは空欄にします。
- 太字・リッチテキストの太字、HTTP/HTTPS/mailtoリンクを保持します。取消線が付いた文字は削除し、削除内容は変換情報にも残しません。セル内改行は保持し、Markdownの記号やHTMLはエスケープします。
- 数式は保存済みの計算結果を使い、再計算しません。表示形式を日本語ロケールで適用します。`HYPERLINK` の固定文字列リンクは扱いますが、動的なリンク先は計算しません。
- 貼り付けPNG/JPEGを取り出し、重複する画像データは共有します。元画像の切り抜き・回転・反転は再現しません。それ以外の画像形式は添付ファイルとして残し、未対応を記録します。画像・図形はアンカー位置に沿って並べ、表に重なるものは表の後に置きます。
- 長方形・角丸長方形・楕円・線・一部の矢印・テキストボックスをJava2Dで近似描画します。グラフ、SmartArt、WordArt、自由曲線、複雑な効果やExcelのレイアウト全体は再現しません。未対応の図形は警告を確認してください。

マクロを含むブック、マクロ有効形式・テンプレート、`.xlsb`、暗号化ファイルは対象外です。外部リンクの取得、外部画像のダウンロード、マクロの実行は行いません。図形内の文字も画像に含めます。Excelに保存されたフォント・サイズ・太字・斜体・文字色（RGB/テーマ色）・通常の下線・文字間隔、段落ごとの左右配置・行間・段落間隔・余白、上下配置・折り返し・縮小設定・文字の回転を反映します。取消線部分は描画前に除去します。利用可能な元フォントを優先し、必要に応じて同梱NotoのSans/Serif・Regular/Boldへ置き換えます。縦書きの組版、WordArt、箇条書き記号、文字だけを正立に保つ反転・回転、文字に合わせた図形枠の拡張などは近似または未対応として警告します。フォントの置換やExcelとは異なる組版処理により、完全に同じ字幅・改行位置を保証するものではありません。旧形式 `.xls` の数式では、保存済み計算結果が欠落しているかを確実に区別できない場合があります。

`report.json` は `specVersion: 1`、入力のファイル名・SHA-256、シート数、`warnings`、`information`、元の範囲と対応する `blocks`、出力画像のパス・種類・サイズ・SHA-256を持つ `assets` を含みます。画面に表示しきれない内容もファイルから確認できます。

[図形内文字の比較用Excel](samples/shape-text.xlsx) と [指定内容の一覧](samples/shape-text.md) で、文字色・3×3の上下左右配置・段落別配置・余白・折り返し・行間・縮小などを確認できます。

## 数式と外部参照

変換処理は、関数名によらずすべての数式を再計算しません。POIの `FormulaEvaluator` を使わず、[保存済み計算結果を読む設定](https://poi.apache.org/apidocs/dev/org/apache/poi/ss/usermodel/DataFormatter.html#setUseCachedValuesForFormulaCells-boolean-)で表示値を取り出します。`IMPORTRANGE`、`IMPORTXML`、`IMPORTHTML`、`IMPORTDATA`、`IMPORTFEED`、`WEBSERVICE` なども、未知の関数や入れ子の式も同じ扱いです。ブック内のURLや外部ブック参照を取得せず、欠落した値を外部取得で補完することもありません。

| 入力 | 出力時の扱い |
| --- | --- |
| 保存済み計算結果がある数式 | 保存済みの値を表示。古い値やエラーも更新しない |
| 計算結果がない `.xlsx` の数式 | エスケープした数式文字列を残し、`FORMULA_CACHE_MISSING` を記録 |
| セルの式全体が `IMAGE(...)` | 画像を取得せず、未対応の表示と `CELL_IMAGE_UNSUPPORTED` を記録 |
| 固定URLの `HYPERLINK` / セルや図形のリンク | 許可されたHTTP/HTTPS/mailtoリンクを保持。変換中はアクセスしない |
| 動的な `HYPERLINK` のリンク先 | 計算せず、表示文字と警告を残す |
| 外部画像や外部データ接続 | ダウンロード・更新しない。画像として取り出すのはブックに埋め込まれたデータのみ |

Playgroundも数式を実行せず、画像は変換結果に含まれるファイルだけを表示します。通常のリンクはユーザーがクリックしたときに開きます。同期HTTPと非同期Queueでこの方針は共通です。Queue・Blobへの通信は管理者が設定したStorage接続で行います。

元のExcelをExcelやGoogleスプレッドシートで開いた際の再計算・外部アクセスは、そのアプリ側の動作です。この変換処理は元ファイルの数式を削除・無効化するものではありません。

## HTTP API

既定のルート接頭辞は `/api` です。Playgroundと `/api/capabilities` は匿名で取得できます。それ以外はFunction認証で、Azure上では `x-functions-key` ヘッダーにFunction Appのホストキーを渡します。複数のFunctionにまたがる非同期処理には、アプリ全体で利用できるホストキーを使います。

| メソッド・パス | 内容 |
| --- | --- |
| `POST /api/convert?filename=sample.xlsx` | Excelの生バイト列を受け、ZIPを返す |
| `POST /api/jobs?filename=sample.xlsx` | 非同期を登録。202と `job` / `statusUrl`、`Retry-After` を返す |
| `GET /api/jobs/{id}` | `queued` → `running` → `succeeded` / `failed` を確認 |
| `GET /api/jobs/{id}/result` | 完成した `document.md` |
| `GET /api/jobs/{id}/report` | 完成した `report.json` |
| `GET /api/jobs/{id}/images/{assetName}` | 完成した結果に含まれる画像・添付ファイル1件 |
| `GET /api/jobs/{id}/archive` | 完成した成果物から、その場でZIPを生成 |
| `GET /api/capabilities` | `asyncEnabled`、対応拡張子、設定された11種類の上限 |

```sh
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @sample.xlsx \
  'http://localhost:7072/api/convert?filename=sample.xlsx' \
  -o document.zip
```

`multipart/form-data` やJSONではなく、`application/octet-stream` でファイル本体を送ります。ページや幅、変換ルールなどのオプションはありません。同期レスポンスには `X-Sheet-Count` と `X-Warning-Count` が付きます。失敗時は成功したZIPの代わりにHTTPエラーと `error.code` / `error.message` を返します。

非同期の成功状態には `resultUrl`、`reportUrl`、`archiveUrl`、`assetsBaseUrl` が加わります。`assetsBaseUrl` は `/api/jobs/{id}/images/` を指します。返されたURLを使い、各取得リクエストにも認証ヘッダーを付けます。Blobの保存場所や資格情報はHTTP状態APIに含めません。保存するのはMarkdown・変換情報・画像それぞれで、ZIPを常時保存しません。非同期が無効ならジョブAPIは503を返します。

PlaygroundはキーをURLやブラウザストレージに保存せず、結果URLの同一オリジンとジョブのパスを検証します。プレビューは生成されるMarkdownの基本構文に限定し、入力HTMLを実行せず、外部画像を取得しません。表示は先頭20万文字、ブロック・表セルの合計1万件、警告一覧は300件までです。取得・展開は合計100 MiB、Markdownと変換情報各20 MiB、添付1件20 MiB、添付最大1,200件を上限とし、サーバーの設定が小さければそちらを適用します。ZIPの展開には `DecompressionStream` の `deflate-raw` に対応したブラウザが必要です。「待機を停止」は画面の通信を止める操作で、サーバージョブを取り消しません。

## 非同期の設定と直接Queue投入

制御用StorageにQueue `excel2md-jobs` と同名の状態管理用Blobコンテナーを作ります。入力と成果物には、同じStorageまたは環境変数で登録した別Storageを指定できます。Queue JSONには接続文字列や任意URLを入れません。プロトコル、別Storageの設定、Java送信例は [直接Queue投入](docs/direct-queue.md) を参照してください。

既存のAzure Function Appへ設定を反映する場合は、環境変数から次のスクリプトを実行できます。接続設定が空なら非同期を無効にし、既存の無関係なApp Settingsは保持します。

```sh
python3 scripts/configure_azure_async.py --resource-group YOUR_RESOURCE_GROUP --name YOUR_FUNCTION_APP
```

このスクリプトは既存のアプリを設定するだけで、Azureリソースの作成やコードのデプロイは行いません。Azure側のホスト用 `AzureWebJobsStorage` は別途有効な設定を用意します。接続設定を追加・削除するときはQueue用の別名と無効化フラグも一緒に更新してください。失敗したジョブ、入力、一時的な試行の成果物を含むBlobには、用途に合わせて保持期間を設定してください。

## Azureへ配置する

既存のLinux・Java 21・Functions v4のFunction Appへ配置します。Flex Consumptionでは、まずインスタンスメモリーを4,096 MB、HTTPのインスタンス当たり同時実行数を1に設定して確認する構成を想定しています。設定コマンドは [MicrosoftのFlex Consumption手順](https://learn.microsoft.com/en-us/azure/azure-functions/flex-consumption-how-to#set-http-concurrency-limits) に沿っています。

```sh
az functionapp scale config set --resource-group YOUR_RESOURCE_GROUP --name YOUR_FUNCTION_APP --instance-memory 4096
az functionapp scale config set --resource-group YOUR_RESOURCE_GROUP --name YOUR_FUNCTION_APP --trigger-type http --trigger-settings perInstanceConcurrency=1
```

Azure側に有効なホスト用Storage設定と `JAVA_OPTS=-Djava.awt.headless=true` を用意し、前節の `configure_azure_async.py` で接続設定とQueueリスナーの有効・無効を反映します。既存の `JAVA_OPTS` がある場合は、その内容を保持してheadless設定を加えてください。別Storageの登録や上限の変更も、必要に応じてApp Settingsに設定します。

Azure CLIへログインした状態で、Mavenが生成した配布用ディレクトリをCore Toolsで配置します。ソースディレクトリから直接publishせず、JAR・依存ライブラリ・生成された `function.json` が揃ったディレクトリを使います。

```sh
# functions/excel2md で実行
./mvnw package
cd target/azure-functions/excel2md-local
func azure functionapp publish YOUR_FUNCTION_APP --no-build
```

`--no-build` はコンパイル済みの配布物を使う指定です。公開方法とオプションは [Core Toolsのpublish手順](https://learn.microsoft.com/en-us/azure/azure-functions/functions-run-local#publish-to-azure) を参照してください。`local.settings.json` の設定を自動転送するオプションは付けていません。配置後はアプリの `/api/playground` を開き、ホストキーを入力して小さなExcelから確認します。ここに記載したコマンドは配置手順であり、このリポジトリでAzureへのデプロイやAzure実機検証を実行済みという意味ではありません。

## 上限

以下は1回の変換に対する既定値です。環境変数で正の整数を指定できます。POIはブックをメモリーに読み込むため、同じファイルサイズでもセル・書式・図形数で時間やメモリー使用量が変わります。入力と成果物には一時ファイルを使いますが、HTTPのZIPレスポンスは上限付きのバイト配列としてメモリー上に生成し、ストリーミング配信はしません。

1つのJavaプロセスでは変換を1件ずつ実行します。Queueのホスト設定も `batchSize=1`・動的同時実行無効です。ただしスケールアウトした別インスタンスは並列に動き、アプリ全体の同時実行数1や全ジョブのFIFO順序を保証するものではありません。

| 環境変数 | 既定値 | 対象 |
| --- | ---: | --- |
| `CONVERSION_MAX_INPUT_BYTES` | 20,971,520 | 入力20 MiB |
| `CONVERSION_MAX_SHEETS` | 50 | ブックのシート数 |
| `CONVERSION_MAX_READ_CELLS` | 200,000 | 読み取りセル数 |
| `CONVERSION_MAX_TABLE_CELLS` | 1,000,000 | 検出した表の展開セル合計 |
| `CONVERSION_MAX_MARKDOWN_BYTES` | 20,971,520 | UTF-8 Markdown |
| `CONVERSION_MAX_IMAGES` | 200 | 貼り付け画像の配置数 |
| `CONVERSION_MAX_IMAGE_BYTES` | 20,971,520 | 画像・添付ファイル1件 |
| `CONVERSION_MAX_OUTPUT_BYTES` | 104,857,600 | 成果物合計と生成ZIP、それぞれ100 MiB |
| `CONVERSION_MAX_SHAPES` | 1,000 | 図形の数 |
| `CONVERSION_MAX_GROUP_DEPTH` | 16 | 図形グループの深さ |
| `CONVERSION_MAX_IMAGE_PIXELS` | 20,000,000 | 描画画像の画素数 |

図形文字のレイアウトには追加の固定上限があります。1図形につき20万文字・2万書式区間・1万段落まで、縮小の試算を含む行レイアウトは合計2万行までです。小さな図形に巨大な文字列を入れた場合もメモリーを使い尽くさないための上限で、超過時は切り捨てず `413 DRAWING_TEXT_LIMIT` を返します。

HTTPタイムアウトに収まることをファイルサイズだけで判定する機能はありません。時間のかかるブックは非同期を使い、実際のブックで時間とメモリーを確認してください。

## 開発・確認

```sh
./mvnw test
python3 -m unittest discover -s scripts -p 'test_*.py'
```

`ExternalFormulaIsolationTest` は、テストが所有する到達可能なループバックHTTPサーバを数式の参照先に指定し、実際にExcelを変換して取得リクエストが0件であることを確認します。保存済み値、未計算の式、入れ子の `IMAGE`、通常のリンクの扱いも検証します。インターネットへの接続は不要ですが、テスト中のローカルポートの利用は必要です。

実際のFunctionsホストとAzuriteを使う非同期E2Eは、macOS/Linuxで次のコマンドから明示的に実行します。Java 21・`func`・`azurite` がPATHに必要です。テスト専用のStorageアカウントと入力を生成し、HTTP・直接Queue投入・別Storage・重複配送・成果物を確認します。Azureクラウドへの接続は不要です。

```sh
./mvnw package
python3 scripts/test_async_e2e.py
```

既定ポートはFunctionsが7073、Azuriteが12200/12201/12202です。`--port` / `--blob-port` / `--queue-port` / `--table-port` で変更できます。終了時に起動プロセスと一時ファイルを片付けます。ログ・成果物を残す場合は `--keep-artifacts` を付けます。このE2Eは通常のunittest discoveryでは起動しません。

画面の検証はPlaywrightを利用する開発用スクリプトです。実行環境に `playwright` と対応するChromiumが必要で、製品の配信ファイルには含まれません。既存のChromeを使う場合は `PLAYWRIGHT_CHANNEL=chrome` を指定します。

```sh
node scripts/test_playground_browser.cjs --out /tmp/excel2md-browser
# 実ホストと sample.xlsx / sample.xls を置いたディレクトリでも確認
node scripts/test_playground_browser.cjs --base http://localhost:7072 --fixtures target/fixtures --out /tmp/excel2md-live
```

既定では隔離したテストAPIで、同期・非同期、ZIP内パス、認証、HTML・外部画像の抑止、エラー、画面幅を確認します。`./mvnw test` は日本語の `target/fixtures/sample.xlsx` と `sample.xls` も生成します。起動済みのローカルホストに `--base` とこのディレクトリを指定すると、実APIで変換し、スクリーンショット・ZIP・JSON形式の検証レポートを指定先に保存します。Storageが有効なら非同期も確認します。これはローカル検証で、Azure上の動作確認を代替しません。

## ライセンス

このプロジェクトのコードは [MIT License](LICENSE) です。Apache POIなどの依存ライブラリには各ライブラリのライセンスが適用されます。同梱のNoto Sans CJK JP / Noto Serif CJK JP（Regular・Bold）はSIL Open Font License 1.1です。[フォントの出典・ライセンス](src/main/resources/fonts/noto/README.md) と原文・SHA-256をJARにも含めています。Playgroundは独自のHTML/CSS/JavaScriptで、外部のMarkdown・ZIPライブラリやWebフォントを含みません。
