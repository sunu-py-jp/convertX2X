# 開発環境と作業手順

[マニュアルの入口](README.md) · [Office → Markdownの実装](office2md.md) · [PowerPoint / PDFの実装](ppt-pdf-to-images.md) · [Movie → Audioの実装](movie2audio.md)

以下のシェルコマンドはmacOS/Linux向けです。変換機能のディレクトリを作業場所にします。リポジトリ直下に共通の `pom.xml` はありません。

## 1. 開発環境を用意する

| ツール | 必要な作業 |
| --- | --- |
| JDK 21 | 画像・Office変換のJavaコードのビルド・テスト・実行。`JAVA_HOME` も同じJDKに合わせる |
| 同梱Maven Wrapper（`./mvnw`） | 画像・Office変換でMavenを実行。Mavenの別途インストールは不要 |
| Node.js 22または24・npm | Movie → Audioの実行・テスト・パッケージ作成。`.nvmrc` は24 |
| Python 3.10以上 | 起動・設定・E2Eスクリプト |
| Azure Functions Core Tools v4（`func`） | ローカルFunctionsホスト、Azureへの配置 |
| Azurite | Blob・Queueを使うローカル検証 |
| Node.js・Playwright・対応Chromium | ブラウザーテストを実行するとき |
| Azure CLI（`az`） | 既存Azureアプリの接続設定・スケール設定を変更するとき |

初回は対象機能のMavenまたはnpm依存とFunctionsの拡張を取得するため、ネットワーク接続が必要です。Playgroundは配布済みHTML/CSS/JavaScriptで、フロントエンドビルド・CDNは不要です。Movie → AudioのサーバーはNode.jsで動作します。

## 2. まず同期HTTPで動かす

Office → Markdownを開発する場合は、リポジトリ直下から実行します。

```sh
cd functions/office2md
python3 scripts/run_local.py
```

PowerPoint / PDFの場合は、別のターミナルでリポジトリ直下から実行します。

```sh
cd functions/ppt-pdf-to-images
python3 scripts/run_local.py
```

Movie → AudioはNode.jsで起動します。JDK・Mavenは不要です。同梱FFmpegはLinux x64・macOS Apple Siliconに対応します。

```sh
cd functions/movie2audio
npm ci
npm start
```

| 機能 | Playground | 対応する入力経路 |
| --- | --- | --- |
| PowerPoint / PDF → 画像 | <http://localhost:7071/api/playground> | 同期HTTP・非同期HTTP・直接Queue |
| Office → Markdown | <http://localhost:7072/api/playground> | 同期HTTP・非同期HTTP・直接Queue |
| Movie → Audio | <http://localhost:7073/api/playground> | 同期HTTPのファイル入力・許可済みHTTPS URL入力 |

画像・Office変換の起動スクリプトはビルド・Javaテスト・パッケージ生成後にホストを起動します。Movie → Audioは `npm start` がPythonの起動スクリプトを呼び、依存のSDK互換修正を確認してソースから起動します。従来の `python3 scripts/run_local.py` も使え、通常はnpm依存をインストールしてから起動します。停止はそのターミナルで `Ctrl+C` を使います。Movie → AudioのURL入力は `CONVERSION_URL_ALLOWED_HOSTS` の設定時だけ有効です。

画像・Office変換でビルド済みの配布物を起動するときは、対象機能のディレクトリで次を使います。

```sh
python3 scripts/run_local.py --skip-build
```

画像・Office変換の `--skip-build` はソースやPlaygroundの変更を配布物へ反映しません。変更後は通常の起動コマンドで再ビルドするか、`./mvnw package` 後にホストを再起動します。Movie → AudioのPythonスクリプトでは同じオプションを依存インストールの省略として受け付け、実行コードはソースを使います。変更後はホストを再起動してください。ポートが使用中ならPythonスクリプトへ `--port 7082` のように指定します。

### 設定の読み込み

`local.settings.json` があれば読み、なければ `local.settings.example.json` を使います。初めて自分用の設定を作る場合だけ、対象機能で次を実行します。

```sh
cp local.settings.example.json local.settings.json
```

設定は `Values` に記載します。同じ設定の環境変数があればそちらを優先します。`local.settings.json`、`.env`、`target/`、Movie → Audioの `dist/`・`node_modules/` はGit管理対象外です。実キーをexampleや成果物の説明文へ転記しないでください。

## 3. QueueとBlobを使って動かす

3機能とも `CONVERSION_STORAGE_CONNECTION_STRING`、または後述のManaged Identity用Blob/Queue設定が揃っている場合に、非同期HTTPと直接Queueを利用できます。

まず別ターミナルで、開発用Azuriteを起動します。

```sh
azurite --location /tmp/convertx2x-azurite --skipApiVersionCheck
```

対象機能のディレクトリから、接続設定を渡してFunctionsを起動します。

```sh
export CONVERSION_STORAGE_CONNECTION_STRING='UseDevelopmentStorage=true'
python3 scripts/run_local.py
```

接続文字列もManaged Identity設定もなければ非同期は無効です。無効化するときは、設定ファイルと環境変数の両方に残っている制御用Storage設定を確認してください。

起動スクリプトは制御用Storage接続からQueueバインドの設定を起動前に導出します。Queue接続はワーカーの設定クラスより先にFunctionsホストが読むため、この処理を通して起動してください。Movie → AudioはNode.jsの起動時にQueue関数を条件付きで登録し、Javaの2機能はDisabled設定も導出します。下表は接続文字列方式です。

| 設定 | 接続なし | 接続あり |
| --- | --- | --- |
| `CONVERSION_QUEUE_CONNECTION_STRING` | 形式上有効なエミュレーター接続 | 制御用Storageと同じ接続 |
| `AzureWebJobs.ProcessConversion.Disabled` | `true` | `false` |
| `AzureWebJobs.PoisonConversion.Disabled` | `true` | `false` |

ローカルの `AzureWebJobsStorage` が空なら制御用Storageで補完します。非同期が無効でも、明示的に設定されたホスト用Storageまで無効になるわけではありません。Azure上のホスト用StorageはFunction App側で別途有効に設定します。

外部システムから直接依頼する流れは「入力Blobを保存 → 参照JSONをQueueへ送信 → 状態確認 → 成果物取得」です。JSONはBase64を1回だけ適用し、ファイル本体や接続文字列を含めません。Java SDKはエンコード設定を使い、Node.jsの送信例は明示的に1回エンコードします。形式・保存先・権限・送信例は [Office → Markdown](../functions/office2md/docs/direct-queue.md) / [PowerPoint・PDF](../functions/ppt-pdf-to-images/docs/direct-queue.md) / [Movie → Audio](../functions/movie2audio/docs/direct-queue.md) の直接Queueガイドを使います。

## 4. 変更に合った検証をする

画像・Office変換では、対象機能のディレクトリでJavaと設定スクリプトを確認します。Movie → Audioは後述のnpmコマンドを使います。

```sh
./mvnw test
python3 -m unittest discover -s scripts -p 'test_*.py'
```

配布物まで確認する場合は `./mvnw verify` を使います。HTTP・Queue・ブラウザーE2EはMavenから自動実行されません。通常のJavaテストでは、接続が必要なStorage統合テストがスキップされることがあります。テスト結果の件数だけで判断せず、今回変更した経路が実行されたか確認してください。Excelの外部数式テストは、到達可能なループバックHTTPサーバを起動して通信0件を検証するため、ローカルポートの利用が必要です。

| 変更対象 | 追加で確認すること |
| --- | --- |
| 本文・表・数式・描画・画像エンコード | 該当Javaテスト、実ファイルの成果物と警告 |
| HTTPの入力・認証・エラー | Functions入口のテスト、実HTTP変換 |
| Queue・Blob・再試行・保存形式 | 専用Azuriteを使う非同期E2E |
| Playground・成果物の取得URL | ブラウザーテスト、ダウンロード・プレビュー |
| 環境変数や上限 | 読み込み処理、example、利用ガイド、API資料、Python設定テストを揃える |
| 文書のみ | ファイル・リンク・コマンド・既定値を実装と照合する |

### Office → Markdownの実変換と非同期

作業場所は `functions/office2md` です。

```sh
./mvnw package
python3 scripts/test_async_e2e.py
```

このE2Eは専用FunctionsホストとAzuriteを起動し、同期HTTP・HTTPからのジョブ登録・別Storageへの直接Queue投入・重複配送・成果物を確認して、自分が起動したプロセスを停止します。既定のFunctionsポートは7073、Azuriteは12200/12201/12202です。

Word・PowerPointは `--input target/fixtures/sample.docx` または `--input target/fixtures/sample.pptx` を付けて同じE2Eへ通します。

複雑な入力の確認には [包括Excelの実HTTP・Queue検証手順](../functions/office2md/samples/README.md) を使います。サンプルの数式には、再計算されないことを検証するため意図的に計算式と異なる保存済み値があります。検証前にExcelなどで開いて再保存すると、その条件が変わる場合があります。

### PowerPoint / PDFの非同期

作業場所は `functions/ppt-pdf-to-images` です。

```sh
./mvnw verify
python3 scripts/test_async_e2e.py --storage-integration-test
```

専用AzuriteでHTTP・直接Queue・別Storage・ZIPと個別画像・重複依頼を確認します。`--storage-integration-test` は、そのテスト用Storageに対するJavaの統合テストも実行します。既定のFunctionsポートは7073、Azuriteは11000/11001/11002です。

両機能のQueue E2EはFunctionsポート7073を使うので順番に実行するか、片方に `--port 7074` などを指定します。PPT/PDFのE2Eは一時成果物を保持します。Office → Markdownでは既定でテスト用作業ディレクトリを削除し、`--keep-artifacts` で保持できます。

Movie → Audioの既定起動ポートも7073です。同時に起動している場合は、Queue E2E側の `--port` を変更してください。

### Movie → Audioの実変換

作業場所は `functions/movie2audio` です。

```sh
npm ci
npm test
npm run package
```

同梱FFmpegを使う実抽出、AACパケットの保持、M4Aの構造、URLの取得先制限、HTTP入力と応答ストリームの終了処理を確認します。配布物は `dist/` に作成します。起動済みホストには `python3 scripts/test_http_e2e.py` で実動画を送り、音声を取得します。URL入力は許可した直接HTTPS URLで確認します。音声選択、期限・容量、同梱FFmpegの再ビルド方法は[利用ガイド](../functions/movie2audio/docs/usage.md)を参照してください。

Movie → Audioの非同期E2Eは、標準ポート10000/10001/10002の専用Azuriteと、別ターミナルのFunctionsホストを使います。作業場所は同じです。次の再試行設定はテストの不正メッセージを短時間でpoison処理するためのもので、本番の既定値を変更しません。

```sh
# Azuriteは前述のコマンドで起動済みとします。
CONVERSION_STORAGE_CONNECTION_STRING='UseDevelopmentStorage=true' \
AzureFunctionsJobHost__extensions__queues__maxPollingInterval='00:00:01' \
AzureFunctionsJobHost__extensions__queues__visibilityTimeout='00:00:01' \
AzureFunctionsJobHost__extensions__queues__maxDequeueCount='2' \
python3 scripts/run_local.py --skip-build --port 7074
```

別ターミナルで検証します。これらのE2Eはローカル限定です。テスト自身の入力・出力コンテナーとジョブのBlobだけを終了時に削除し、FunctionsやAzuriteのプロセスは起動したターミナルで停止します。

```sh
MOVIE_TEST_AZURITE=1 npm test
MOVIE_TEST_FAST_RETRY=1 node scripts/test_async_e2e.mjs
```

HTTP受付、Queueからの実抽出、M4A取得、重複・失敗・poisonを確認します。URL入力も検証する場合は、Functions側の許可ホストとテスト側の `MOVIE_TEST_VIDEO_URL` を設定します。

### ブラウザー

Node.jsから `playwright` を読み込め、Chromiumを起動できる環境を用意します。画像・Office変換のブラウザーテスト用にPlaywrightの `package.json` やロックファイルは同梱していないため、開発環境側でPlaywrightを用意し、利用したバージョンを検証結果に残します。既存のChromeを使う場合は `PLAYWRIGHT_CHANNEL=chrome` を指定できます。モジュールの置き場所が別なら `NODE_PATH` を設定します。

Office → Markdownでは `functions/office2md` で実行します。

```sh
node scripts/test_playground_browser.cjs --out /tmp/office2md-browser
```

既定は隔離したテストAPIでの検証です。Javaテストが生成した資料と、別ターミナルで起動済みのoffice2mdホストを使う場合は次を実行します。

```sh
node scripts/test_playground_browser.cjs --base http://localhost:7072 --fixtures target/fixtures --out /tmp/office2md-live
```

PowerPoint / PDFでは `functions/ppt-pdf-to-images` で実行します。

```sh
./mvnw package
python3 scripts/test_playground_e2e.py
```

このラッパーはFunctionsの7081/7082とAzuriteの12000/12001/12002を使い、テスト後に自分が起動したプロセスを停止します。

ローカルのHTTP・Queue・ブラウザー検証はAzure実機での検証とは別です。クラウドでの処理時間・メモリー・同時実行数は、配置先と実際の資料で確認します。

## 5. Azureへ配置する

配置先は機能ごとの既存Function Appです。Azure CLIにログインし、対象のサブスクリプション・リソースグループ・アプリ名を確認してから進めます。画像・Office変換はLinux・Java 21・Functions v4を前提に、有効なホスト用Storageと `JAVA_OPTS=-Djava.awt.headless=true` を設定します。既存のJavaオプションがある場合は保持して追加します。Movie → AudioはLinux x64・Node.js 24・Functions v4の独立したアプリへ配置し、`FUNCTIONS_WORKER_RUNTIME=node` を設定します。Java機能と同じFunction Appには混在させません。

1. 対象機能のテストを実行し、Java機能は `./mvnw package`、Movie → Audioは `npm run package` で配布物を作る。
2. 配置先のランタイム・上限・ホスト用Storageを揃え、非同期を使う場合はStorage接続を設定する。Queueトリガー用の派生設定も設定する。
3. 配布ディレクトリからCore Toolsで公開する。
4. Azure上で小さな実ファイルを変換し、非同期を使う場合はジョブと成果物取得も確認する。

各機能の非同期設定を更新するコマンドは、対象機能のディレクトリから実行します。制御用接続を環境変数へ設定してから使用してください。空・未設定で実行すると非同期を無効化します。

```sh
python3 scripts/configure_azure_async.py --resource-group YOUR_RESOURCE_GROUP --name YOUR_FUNCTION_APP
```

このスクリプトはプロセスの環境変数を読み、`local.settings.json` は読みません。指定アプリの接続・派生設定を更新しますが、Azureリソース作成やコードのデプロイは行いません。追加Storageの登録・上限・ホスト用Storageは別途設定し、詳しい手順は [Office → Markdown](../functions/office2md/docs/usage.md) / [PowerPoint・PDF](../functions/ppt-pdf-to-images/docs/usage.md) のAzure配置節を参照します。

office2mdの既定の配布物を公開する例です。作業場所は `functions/office2md` です。

```sh
cd target/azure-functions/office2md-local
func azure functionapp publish YOUR_FUNCTION_APP --no-build
```

PowerPoint / PDFでは `functions/ppt-pdf-to-images/target/azure-functions/slide2image-local` から同じpublishコマンドを実行します。配布ディレクトリ名は既定値で、Azure上の実アプリ名とは別です。

Movie → Audioでは `functions/movie2audio` でパッケージを作成してから公開します。

```sh
npm ci
npm test
npm run package
cd dist
func azure functionapp publish YOUR_FUNCTION_APP --no-build
```

Movie → Audioにも `python3 scripts/configure_azure_async.py --resource-group YOUR_RESOURCE_GROUP --name YOUR_FUNCTION_APP` を用意しています。プロセスの環境変数から非同期接続・Queueバインド・通知・保持の設定を適用し、認証方式切替時の古い設定を除去します。ホスト用Storage・権限・ネットワークは別途構成し、再起動・トリガー同期を確認します。`local.settings.json` の秘密情報を公開設定へ自動転送するオプションは付けません。

## 6. 実装を拡張するときに守ること

- **変換処理は入力経路で共有する。** 全機能でHTTPとQueueが共通コアを使う。入口に別の変換実装を追加せず、対応する各経路の成果物を確認する。
- **Excelの数式は一切評価しない。** `IMPORTRANGE`、`WEBSERVICE`、未知・入れ子の式も保存済み値だけを読み、キャッシュ欠落を外部取得で補完しない。関数名の禁止リストに依存しない。[外部数式テスト](../functions/office2md/src/test/java/com/convertx2x/office2md/conversion/ExternalFormulaIsolationTest.java)を維持する。
- **入力の文字列と実行対象を分ける。** Markdown/HTMLのエスケープ、リンクのスキーム検証、成果物に限定した画像表示を維持する。Playgroundに任意URLからの画像取得を追加しない。
- **Storageは管理者が登録した接続を使う。** Queueへ任意URL・SAS・接続文字列を追加しない。重複配送、リース、ETag、成果物公開の順序を変更するときは競合・失敗時も確認する。
- **上限と後始末を保つ。** 画像バッファや表の展開前に上限を確認し、途中までの出力を成功扱いにしない。一時ファイルや変換結果は例外時にも解放する。
- **変換品質と保存内容を説明する。** Officeの取消線除外、画像の原本抽出、未対応要素の警告を保つ。フォント・ライブラリ・サンプルを追加したら、出典とライセンスも更新する。

機能内での具体的な変更箇所は [Office → Markdown](office2md.md) / [PowerPoint・PDF](ppt-pdf-to-images.md)、独立した新機能の追加は [CONTRIBUTING](../CONTRIBUTING.md) を参照してください。

## 7. 問題を切り分ける

| 症状 | 最初に確認すること |
| --- | --- |
| Javaのビルド・起動に失敗 | JDK 21と `JAVA_HOME`、対象機能の作業ディレクトリ、初回依存取得の通信 |
| Movie → Audioの起動に失敗 | Node.js 22 / 24、`npm ci`、Core Tools v4、`FUNCTIONS_WORKER_RUNTIME=node` |
| 変更したコードやUIが反映されない | `--skip-build` で古い配布物を起動していないか。再ビルド後にホストを再起動 |
| 非同期が選べない・503になる | 制御用接続の有無、設定ファイルより優先される環境変数、起動時の派生設定 |
| Queueが処理されない | Blob・Queue・ホスト用Storage、機能ごとのQueue名、JSON契約、Base64二重適用の有無 |
| ローカルでは成功しAzureで401になる | ローカルの認証条件との差、`x-functions-key`。非同期では状態・成果物取得にも有効なアプリのホストキーを使う |
| 上限エラー・同期タイムアウト | エラーコードと機能の上限表。メモリー・一時ディスク・出力も確認。HTTPの応答待ちには非同期の利用も検討。ただし非同期でも変換処理自体の上限は適用 |
| Movie → Audioの応答が途中で止まる | 本文読み取りのエラー、入力受信からHTTP応答へのファイル読み出しまでの共通期限、伝搬したクライアント切断を確認。送信開始後はJSONエラーへ変更できない |
| Excelの値が古い・数式が文字で出る | 保存済み計算結果の有無。変換側は再計算しないため、元ファイル側の保存状態を確認 |
| 日本語や図形の見た目が違う | 同梱フォント、元フォントの有無、office2mdの `report.json` と描画の対応範囲 |
| 非同期の結果が見つからない | 成功状態が公開したパス・manifestを使っているか。試行途中のBlobを一覧から拾わない |

調査結果には再現手順、秘密情報を除いたエラーコード、入力形式、期待値と実際の成果物を残します。実資料をそのまま公開サンプルやテスト結果へ追加しないでください。

## 8. Managed Identity・閉域Storage・結果通知

### 制御用Storageと別アカウント

接続文字列方式は継続して使えます。MI方式へ切り替える場合は、同じ登録の接続文字列を削除し、次のアプリ設定を使います。これらは管理者が登録するサービスのエンドポイントです。Queueの依頼JSONには書きません。

```text
CONVERSION_STORAGE__blobServiceUri=https://CONTROL.blob.core.windows.net
CONVERSION_STORAGE__queueServiceUri=https://CONTROL.queue.core.windows.net
# ユーザー割り当てMIの場合のみ、そのクライアントID
CONVERSION_STORAGE__clientId=00000000-0000-0000-0000-000000000000

CONVERSION_INPUT_STORAGE_SOURCE__blobServiceUri=https://SOURCE.blob.core.windows.net
CONVERSION_OUTPUT_STORAGE_ARCHIVE__blobServiceUri=https://OUTPUT.blob.core.windows.net
CONVERSION_CREATE_RESOURCES=false
```

別アカウントの登録にも任意の `__clientId` を指定できます。省略時はシステム割り当てMIです。MI方式のアプリSDKはManaged Identityを使用するため、ローカルのAzure CLIログインでMIを代用する構成ではありません。ローカルではAzuriteと接続文字列を使います。サービスURIは資格情報・クエリ・Blobパスを含まないHTTPSのオリジンに限定します。

Queueトリガーには、ワーカー起動前に次の設定も必要です。ローカル起動スクリプトや配置補助が導出する場合も、Azureの保存済み設定を確認してください。古い単一キー `CONVERSION_QUEUE_CONNECTION_STRING` が残っていると、設定コレクションより優先され得るため削除します。

```text
CONVERSION_QUEUE_CONNECTION_STRING__queueServiceUri=https://CONTROL.queue.core.windows.net
CONVERSION_QUEUE_CONNECTION_STRING__credential=managedidentity
# ユーザー割り当てMIの場合のみ
CONVERSION_QUEUE_CONNECTION_STRING__clientId=00000000-0000-0000-0000-000000000000
```

`AzureWebJobsStorage` はこれとは別のホスト用接続です。Identity-based接続では、ホスト用アカウントの `AzureWebJobsStorage__accountName` または必要なサービスURIと、必要に応じて `__credential`・`__clientId` を設定します。ホスト、Timerの調停、配置用Storageの要件を、対象プランに合わせて確認してください。アプリの設定を変えただけでホスト用認証は切り替わりません。[公式の接続管理](https://learn.microsoft.com/en-us/azure/azure-functions/manage-connections)・[Queue接続](https://learn.microsoft.com/en-us/azure/azure-functions/functions-bindings-storage-queue-trigger#connections)を参照してください。

### 権限と閉域構成

`CONVERSION_CREATE_RESOURCES=false` の場合、制御用コンテナー、入力・出力コンテナー、依頼Queue、poison Queue、結果Queueを運用側で事前作成します。既定の `true` ではアプリの必要箇所で作成します。コンテナー作成を省略しても、リースや状態更新、結果取得に必要なデータ権限は必要です。

| 対象 | 必要な操作・権限の例 |
| --- | --- |
| 入力原本 | Blobの属性取得・読取。`Storage Blob Data Reader`等 |
| 制御用Blob | 状態と所有記録の読取・作成・更新・一覧、リース、清掃。`Storage Blob Data Contributor`等 |
| 出力Blob | 出力・属性取得・HTTP結果取得時の読取、保持機能を使う場合の削除。`Storage Blob Data Contributor`等 |
| 依頼Queue | ワーカーの取得・削除、HTTP受付の送信、poisonへの送信。用途ごとのQueueデータ権限 |
| 結果Queue | 変換側の送信と、利用側の受信を別々に付与。`Storage Queue Data Message Sender` / `Processor`等 |

上記はアプリの操作範囲の例です。ホスト用Storageの権限とは分けて確認します。管理プレーンのContributorだけでデータアクセスを代用しません。アカウント全体のキーでは登録名を分けても実権限は縮まりません。Queueの送信者は、登録済み接続先で処理を依頼できる信頼された主体に限定します。

Flex従量課金で閉域Storageを使う場合は、FunctionsのVNet統合、Blob/QueueそれぞれのPrivate Endpoint、Private DNSとVNetリンク、必要なネットワーク規則を構成します。通常のサービス名が実行環境でプライベートIPへ解決されることを確認し、入力元・出力先・制御用・ホスト用・配置用の経路を揃えます。HTTP入口の閉域化は別設定です。任意URL取得のホスト許可・SSRF対策を緩める必要はありません。[Functionsのネットワーク](https://learn.microsoft.com/en-us/azure/azure-functions/functions-networking-options)を参照してください。

### 結果Queueの登録

例えば `completed` という通知先を登録します。接続文字列の場合はURI・clientIdの代わりに `CONVERSION_RESULT_QUEUE_COMPLETED__connectionString` を設定します。

```text
CONVERSION_RESULT_QUEUE_COMPLETED__queueName=conversion-results
CONVERSION_RESULT_QUEUE_COMPLETED__queueServiceUri=https://RESULTS.queue.core.windows.net
# 必要な場合のみ __clientId を追加
```

直接Queueのversion 2依頼へ `"notification":{"queue":"completed"}` を指定すると、終端の成功・失敗を通知します。通知先を依頼JSONのURLや資格情報で指定できません。結果通知用Queueには、変換依頼Queueやpoison Queueを使わないでください。

通知はBase64を1回適用したJSONです。利用側は `eventId` で重複を除外してください。送信後・送信済み記録前の停止で、同じイベントが再送されることがあります。通知送信待ちは永続状態から再処理するため、通知障害だけを理由に変換をやり直しません。ジョブを特定できない不正JSONについては通知を保証せず、poison Queueと監視で調査します。未登録の通知エイリアスや権限不備で送信できない場合は、状態Blobの送信待ちを監視し、管理者が登録・権限を修正します。未送信のままでは保持清掃も保護されるため、通知障害を放置しないでください。

結果通知先または保持機能を設定したアプリは、5分ごとのメンテナンス処理を使います。これによりアイドル時も短い定期実行が発生します。両方未設定の場合、この定期処理は無効です。Javaの2機能では配置補助が `AzureWebJobs.MaintainConversions.Disabled` を導出します。補助を使わず直接配置する場合、通知・保持なしなら明示的にtrue、有効にする場合はfalseを設定してください。この設定なしにTimer関数だけを配置すると、処理本体が何もしなくてもホストの定期起動は発生します。Node.js版は設定に応じてTimer自体を登録します。Queueの重複配送や水平スケールを前提とし、定期処理と変換はジョブのリースで調整します。

## 9. 結果の採用・保存期限・受入確認

### 入力版と結果の公開

version 2の `input.expectedETag` は依頼元が取得したBlobのETagです。ワーカーはこの値で入力を検証し、異なる内容へ置き換わっていれば `INPUT_VERSION_MISMATCH` で失敗させます。ETagの引用符も保持してください。付加情報の `metadata` は最大16項目の文字列マップで、キー64文字・値512文字・JSON全体8KiB以下です。業務IDやrevisionの引き継ぎに使えますが、認証情報を入れたり認可の根拠にしたりしません。

変換結果は試行ごとの場所へ保存し、全成果物が揃ってから成功状態を確定します。成功状態とManifestが指す成果物だけを採用してください。途中のBlobが物理的に不可視になる仕組みではありません。固定パスへの複数Blob上書きは全体で原子的に切り替わらないため、利用側で完成したManifestへの参照を条件付きで切り替えます。

依頼時のETag確認だけでは、処理後に新しい版が作られたことまでは判断できません。利用側の現在のrevisionと結果のrevisionを照合し、同じ状態保存先で条件付き更新などを使って採用してください。ETagはBlobの版を識別する値で、内容のSHA-256ではありません。[Blobの同時更新制御](https://learn.microsoft.com/en-us/azure/storage/blobs/concurrency-manage)を参照してください。

### 保持期間

| 設定 | 既定値 | 意味 |
| --- | --- | --- |
| `CONVERSION_RESULT_RETENTION_DAYS` | `0` | 所有する結果・入力コピー・試行出力の自動清掃を無効。正の整数で保持日数を設定 |
| `CONVERSION_STATE_RETENTION_DAYS` | `0` | 状態の自動削除を無効。有効にする場合は結果保持も有効にし、それより長く設定 |

削除する場所は、アプリが作成前に記録した所有ファイルの範囲です。任意の出力prefix全体や、外部システムが用意した原本は削除しません。実行中、通知送信待ち、保存期限内の結果を保護します。清掃は定期実行で順次進むため、期限の瞬間の削除は保証しません。通知を使うジョブの結果・状態の保持期間は、完了時刻と通知送信済み時刻の遅い方から数えます。遅延通知の直後に成果物が消えることを避けます。保持期限は結果の取得可能期間として利用側にも通知してください。

HTTP受付では、状態の記録から入力保存・Queue投入が終わるまで受付途中として管理します。この途中で停止し、結果保持日数を超えても完了しない依頼は、メンテナンスで `SUBMISSION_EXPIRED` の失敗にします。その失敗日時から通常の保持期間を適用して所有する入力コピーを清掃します。通常のQueue待ち・変換中のジョブを、受付途中として期限切れにすることはありません。

状態を削除すると、そのjobIdの重複判定も終了します。同じ依頼を期限後に送ると、新規の処理として実行され得ます。恒久的な重複排除が必要な利用側は別途記録を保持するか、状態の自動削除を無効にしてください。失敗後に条件を直して再実行する場合は新しいjobIdを使います。

この機能導入前のジョブには所有記録・メンテナンス索引がないため、自動で所有権を推測して削除しません。旧成果物は保存先と参照状況を確認して運用側で管理します。新しいジョブは保持機能が無効な間も索引を記録するため、後から保持を有効にできます。

Storage Lifecycleを併用する場合は、アプリの状態・所有記録・通知待ちを先に消さないよう対象を分けます。論理削除やバージョン保持が有効なら、アプリで削除しても課金対象データが残ることがあります。検索・一覧・Blobイベントから出力を除外したい場合も、利用側でコンテナーや対象prefixを設定します。

### 配置先で行う確認

- Shared Key無効のStorageで、MIによる原本読取・Queue起動・出力・通知・HTTP結果取得を確認する。
- 公開ネットワーク無効で名前解決・接続・ホスト起動・配置・スケールが成立することを確認する。
- 入力を依頼後に差し替える、同一依頼を再送する、通知送信を失敗させる、途中で停止する、保持期限を過ぎるケースを確認する。
- 4GBにはワーカー、文書モデル、画像、バッファ等が含まれる。入力サイズだけで使用量を予測せず、実資料で時間・メモリ・一時ディスク・同時実行を測る。任意入力でのメモリ不足ゼロは保証しない。
- 日本語・図形・グラフ・縦横比・Markdownの構造を、利用許諾のある基準資料と比較する。未対応の描画は制約として扱い、必要なら入力側で画像化するか別エンジンを評価する。
- アプリだけでなく、Functionsホスト、SDK診断、Application Insights、依頼側ログにもSAS・接続文字列・本文が残っていないか確認する。

ローカル／Azuriteのテストと、実AzureでのMI・閉域・負荷検証は別です。この変更では実Azureへの配置や権限・ネットワーク変更は行っていません。文字起こしサービスの呼び出しも含みません。必要な場合は独立したワークフローで本文・セグメント・大容量結果のBlob参照・中間音声の保持を扱ってください。
