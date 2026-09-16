# 開発環境と作業手順

[マニュアルの入口](README.md) · [Office → Markdownの実装](office2md.md) · [PowerPoint / PDFの実装](ppt-pdf-to-images.md) · [Movie → AACの実装](movie2audio.md)

以下のシェルコマンドはmacOS/Linux向けです。変換機能のディレクトリを作業場所にします。リポジトリ直下に共通の `pom.xml` はありません。

## 1. 開発環境を用意する

| ツール | 必要な作業 |
| --- | --- |
| JDK 21 | 画像・Office変換のJavaコードのビルド・テスト・実行。`JAVA_HOME` も同じJDKに合わせる |
| 同梱Maven Wrapper（`./mvnw`） | 画像・Office変換でMavenを実行。Mavenの別途インストールは不要 |
| Node.js 22または24・npm | Movie → AACの実行・テスト・パッケージ作成。`.nvmrc` は24 |
| Python 3.10以上 | 起動・設定・E2Eスクリプト |
| Azure Functions Core Tools v4（`func`） | ローカルFunctionsホスト、Azureへの配置 |
| Azurite | Blob・Queueを使うローカル検証 |
| Node.js・Playwright・対応Chromium | ブラウザーテストを実行するとき |
| Azure CLI（`az`） | 既存Azureアプリの接続設定・スケール設定を変更するとき |

初回は対象機能のMavenまたはnpm依存とFunctionsの拡張を取得するため、ネットワーク接続が必要です。Playgroundは配布済みHTML/CSS/JavaScriptで、フロントエンドビルド・CDNは不要です。Movie → AACのサーバーはNode.jsで動作します。

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

Movie → AACはNode.jsで起動します。JDK・Mavenは不要です。同梱FFmpegはLinux x64・macOS Apple Siliconに対応します。

```sh
cd functions/movie2audio
npm ci
npm start
```

| 機能 | Playground | 対応する入力経路 |
| --- | --- | --- |
| PowerPoint / PDF → 画像 | <http://localhost:7071/api/playground> | 同期HTTP・非同期HTTP・直接Queue |
| Office → Markdown | <http://localhost:7072/api/playground> | 同期HTTP・非同期HTTP・直接Queue |
| Movie → AAC | <http://localhost:7073/api/playground> | 同期HTTPのファイル入力・許可済みHTTPS URL入力 |

画像・Office変換の起動スクリプトはビルド・Javaテスト・パッケージ生成後にホストを起動します。Movie → AACは `npm start` がPythonの起動スクリプトを呼び、依存のSDK互換修正を確認してソースから起動します。従来の `python3 scripts/run_local.py` も使え、通常はnpm依存をインストールしてから起動します。停止はそのターミナルで `Ctrl+C` を使います。Movie → AACのURL入力は `CONVERSION_URL_ALLOWED_HOSTS` の設定時だけ有効です。

画像・Office変換でビルド済みの配布物を起動するときは、対象機能のディレクトリで次を使います。

```sh
python3 scripts/run_local.py --skip-build
```

画像・Office変換の `--skip-build` はソースやPlaygroundの変更を配布物へ反映しません。変更後は通常の起動コマンドで再ビルドするか、`./mvnw package` 後にホストを再起動します。Movie → AACのPythonスクリプトでは同じオプションを依存インストールの省略として受け付け、実行コードはソースを使います。変更後はホストを再起動してください。ポートが使用中ならPythonスクリプトへ `--port 7082` のように指定します。

### 設定の読み込み

`local.settings.json` があれば読み、なければ `local.settings.example.json` を使います。初めて自分用の設定を作る場合だけ、対象機能で次を実行します。

```sh
cp local.settings.example.json local.settings.json
```

設定は `Values` に記載します。同じ設定の環境変数があればそちらを優先します。`local.settings.json`、`.env`、`target/`、Movie → AACの `dist/`・`node_modules/` はGit管理対象外です。実キーをexampleや成果物の説明文へ転記しないでください。

## 3. QueueとBlobを使って動かす

3機能とも <code>CONVERSION_STORAGE_CONNECTION_STRING</code> が設定されている場合に、非同期HTTPと直接Queueを利用できます。

まず別ターミナルで、開発用Azuriteを起動します。

```sh
azurite --location /tmp/convertx2x-azurite --skipApiVersionCheck
```

対象機能のディレクトリから、接続設定を渡してFunctionsを起動します。

```sh
export CONVERSION_STORAGE_CONNECTION_STRING='UseDevelopmentStorage=true'
python3 scripts/run_local.py
```

接続が空・未設定なら非同期は無効です。設定ファイルに接続が残っている場合も明示的に無効化するには、`CONVERSION_STORAGE_CONNECTION_STRING='' python3 scripts/run_local.py` とします。

画像・Office変換の起動スクリプトは制御用Storage接続から、次の設定をJava起動前に導出します。QueueバインドはJavaの設定クラスより先にFunctionsホストが読むため、この処理を通して起動してください。Movie → AACはNode.jsの起動時にQueue関数を条件付きで登録するため、この表の派生設定は不要です。

| 設定 | 接続なし | 接続あり |
| --- | --- | --- |
| `CONVERSION_QUEUE_CONNECTION_STRING` | 形式上有効なエミュレーター接続 | 制御用Storageと同じ接続 |
| `AzureWebJobs.ProcessConversion.Disabled` | `true` | `false` |
| `AzureWebJobs.PoisonConversion.Disabled` | `true` | `false` |

ローカルの `AzureWebJobsStorage` が空なら制御用Storageで補完します。非同期が無効でも、明示的に設定されたホスト用Storageまで無効になるわけではありません。Azure上のホスト用StorageはFunction App側で別途有効に設定します。

外部システムから直接依頼する流れは「入力Blobを保存 → 参照JSONをQueueへ送信 → 状態確認 → 成果物取得」です。JSONはBase64を1回だけ適用し、ファイル本体や接続文字列を含めません。Java SDKはエンコード設定を使い、Node.jsの送信例は明示的に1回エンコードします。形式・保存先・権限・送信例は [Office → Markdown](../functions/office2md/docs/direct-queue.md) / [PowerPoint・PDF](../functions/ppt-pdf-to-images/docs/direct-queue.md) / [Movie → AAC](../functions/movie2audio/docs/direct-queue.md) の直接Queueガイドを使います。

## 4. 変更に合った検証をする

画像・Office変換では、対象機能のディレクトリでJavaと設定スクリプトを確認します。Movie → AACは後述のnpmコマンドを使います。

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

Movie → AACの既定起動ポートも7073です。同時に起動している場合は、Queue E2E側の `--port` を変更してください。

### Movie → AACの実変換

作業場所は `functions/movie2audio` です。

```sh
npm ci
npm test
npm run package
```

同梱FFmpegを使う実抽出、AACパケットの保持、M4Aの構造、URLの取得先制限、HTTP入力と応答ストリームの終了処理を確認します。配布物は `dist/` に作成します。起動済みホストには `python3 scripts/test_http_e2e.py` で実動画を送り、音声を取得します。URL入力は許可した直接HTTPS URLで確認します。音声選択、期限・容量、同梱FFmpegの再ビルド方法は[利用ガイド](../functions/movie2audio/docs/usage.md)を参照してください。

Movie → AACの非同期E2Eは、標準ポート10000/10001/10002の専用Azuriteと、別ターミナルのFunctionsホストを使います。作業場所は同じです。次の再試行設定はテストの不正メッセージを短時間でpoison処理するためのもので、本番の既定値を変更しません。

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

配置先は機能ごとの既存Function Appです。Azure CLIにログインし、対象のサブスクリプション・リソースグループ・アプリ名を確認してから進めます。画像・Office変換はLinux・Java 21・Functions v4を前提に、有効なホスト用Storageと `JAVA_OPTS=-Djava.awt.headless=true` を設定します。既存のJavaオプションがある場合は保持して追加します。Movie → AACはLinux x64・Node.js 24・Functions v4の独立したアプリへ配置し、`FUNCTIONS_WORKER_RUNTIME=node` を設定します。Java機能と同じFunction Appには混在させません。

1. 対象機能のテストを実行し、Java機能は `./mvnw package`、Movie → AACは `npm run package` で配布物を作る。
2. 配置先のランタイム・上限・ホスト用Storageを揃え、非同期を使う場合はStorage接続を設定する。Java機能は派生設定も設定する。
3. 配布ディレクトリからCore Toolsで公開する。
4. Azure上で小さな実ファイルを変換し、非同期を使う場合はジョブと成果物取得も確認する。

画像・Office変換の非同期設定を更新するコマンドは、対象機能のディレクトリから実行します。制御用接続を環境変数へ設定してから使用してください。空・未設定で実行すると非同期を無効化します。

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

Movie → AACでは `functions/movie2audio` でパッケージを作成してから公開します。

```sh
npm ci
npm test
npm run package
cd dist
func azure functionapp publish YOUR_FUNCTION_APP --no-build
```

Movie → AACに非同期設定の派生スクリプトはありません。Linux x64・Node.js 24の配置先へ、機能固有の容量・期限・許可ホストと、非同期を使う場合の `CONVERSION_STORAGE_CONNECTION_STRING` を設定して再起動・トリガー同期を行います。Queueバインドもこの接続設定を直接参照します。`local.settings.json` の秘密情報を公開設定へ自動転送するオプションは付けません。

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
| Movie → AACの起動に失敗 | Node.js 22 / 24、`npm ci`、Core Tools v4、`FUNCTIONS_WORKER_RUNTIME=node` |
| 変更したコードやUIが反映されない | `--skip-build` で古い配布物を起動していないか。再ビルド後にホストを再起動 |
| 非同期が選べない・503になる | 制御用接続の有無、設定ファイルより優先される環境変数、起動時の派生設定 |
| Queueが処理されない | Blob・Queue・ホスト用Storage、機能ごとのQueue名、JSON契約、Base64二重適用の有無 |
| ローカルでは成功しAzureで401になる | ローカルの認証条件との差、`x-functions-key`。非同期では状態・成果物取得にも有効なアプリのホストキーを使う |
| 上限エラー・同期タイムアウト | エラーコードと機能の上限表。メモリー・一時ディスク・出力も確認。HTTPの応答待ちには非同期の利用も検討。ただし非同期でも変換処理自体の上限は適用 |
| Movie → AACの応答が途中で止まる | 本文読み取りのエラー、入力受信からHTTP応答へのファイル読み出しまでの共通期限、伝搬したクライアント切断を確認。送信開始後はJSONエラーへ変更できない |
| Excelの値が古い・数式が文字で出る | 保存済み計算結果の有無。変換側は再計算しないため、元ファイル側の保存状態を確認 |
| 日本語や図形の見た目が違う | 同梱フォント、元フォントの有無、office2mdの `report.json` と描画の対応範囲 |
| 非同期の結果が見つからない | 成功状態が公開したパス・manifestを使っているか。試行途中のBlobを一覧から拾わない |

調査結果には再現手順、秘密情報を除いたエラーコード、入力形式、期待値と実際の成果物を残します。実資料をそのまま公開サンプルやテスト結果へ追加しないでください。
