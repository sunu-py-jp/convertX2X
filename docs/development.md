# 開発環境と作業手順

[マニュアルの入口](README.md) · [Excelの実装](excel2md.md) · [PowerPoint / PDFの実装](ppt-pdf-to-images.md)

以下のシェルコマンドはmacOS/Linux向けです。変換機能のディレクトリを作業場所にします。リポジトリ直下に共通の `pom.xml` はありません。

## 1. 開発環境を用意する

| ツール | 必要な作業 |
| --- | --- |
| JDK 21 | Javaコードのビルド・テスト・実行。`JAVA_HOME` も同じJDKに合わせる |
| 同梱Maven Wrapper（`./mvnw`） | 機能ごとのMavenを実行。Mavenの別途インストールは不要 |
| Python 3.10以上 | 起動・設定・E2Eスクリプト |
| Azure Functions Core Tools v4（`func`） | ローカルFunctionsホスト、Azureへの配置 |
| Azurite | Blob・Queueを使うローカル検証 |
| Node.js・Playwright・対応Chromium | ブラウザーテストを実行するとき |
| Azure CLI（`az`） | 既存Azureアプリの接続設定・スケール設定を変更するとき |

初回はMaven本体・依存ライブラリやFunctionsの拡張を取得するため、ネットワーク接続が必要です。Playgroundは配布済みHTML/CSS/JavaScriptなので、利用時のNode.js・フロントエンドビルド・CDNは不要です。

## 2. まず同期HTTPで動かす

Excelを開発する場合は、リポジトリ直下から実行します。

```sh
cd functions/excel2md
python3 scripts/run_local.py
```

PowerPoint / PDFの場合は、別のターミナルでリポジトリ直下から実行します。

```sh
cd functions/ppt-pdf-to-images
python3 scripts/run_local.py
```

Excelは <http://localhost:7072/api/playground>、PowerPoint / PDFは <http://localhost:7071/api/playground> です。起動スクリプトはビルド・Javaテスト・パッケージ生成後にホストを起動します。停止はそのターミナルで `Ctrl+C` を使います。

ビルド済みの配布物を起動するときは、対象機能のディレクトリで次を使います。

```sh
python3 scripts/run_local.py --skip-build
```

`--skip-build` はソースやPlaygroundの変更を配布物へ反映しません。変更後は通常の起動コマンドで再ビルドするか、`./mvnw package` 後にホストを再起動します。ポートが使用中なら `--port 7082` のように指定します。

### 設定の読み込み

`local.settings.json` があれば読み、なければ `local.settings.example.json` を使います。初めて自分用の設定を作る場合だけ、対象機能で次を実行します。

```sh
cp local.settings.example.json local.settings.json
```

設定は `Values` に記載します。同じ設定の環境変数があればそちらを優先します。`local.settings.json`、`.env`、`target/` はGit管理対象外です。実キーをexampleや成果物の説明文へ転記しないでください。

## 3. QueueとBlobを使って動かす

まず別ターミナルで、開発用Azuriteを起動します。

```sh
azurite --location /tmp/convertx2x-azurite
```

対象機能のディレクトリから、接続設定を渡してFunctionsを起動します。

```sh
export CONVERSION_STORAGE_CONNECTION_STRING='UseDevelopmentStorage=true'
python3 scripts/run_local.py
```

接続が空・未設定なら非同期は無効です。設定ファイルに接続が残っている場合も明示的に無効化するには、`CONVERSION_STORAGE_CONNECTION_STRING='' python3 scripts/run_local.py` とします。

起動スクリプトは制御用Storage接続から、次の設定をJava起動前に導出します。QueueバインドはJavaの設定クラスより先にFunctionsホストが読むため、この処理を通して起動してください。

| 設定 | 接続なし | 接続あり |
| --- | --- | --- |
| `CONVERSION_QUEUE_CONNECTION_STRING` | 形式上有効なエミュレーター接続 | 制御用Storageと同じ接続 |
| `AzureWebJobs.ProcessConversion.Disabled` | `true` | `false` |
| `AzureWebJobs.PoisonConversion.Disabled` | `true` | `false` |

ローカルの `AzureWebJobsStorage` が空なら制御用Storageで補完します。非同期が無効でも、明示的に設定されたホスト用Storageまで無効になるわけではありません。Azure上のホスト用StorageはFunction App側で別途有効に設定します。

外部システムから直接依頼する流れは「入力Blobを保存 → 参照JSONをQueueへ送信 → 状態確認 → 成果物取得」です。JSONはSDKでBase64を1回だけ適用し、ファイル本体や接続文字列を含めません。形式・保存先・権限・Java送信例は [Excel](../functions/excel2md/docs/direct-queue.md) / [PowerPoint・PDF](../functions/ppt-pdf-to-images/docs/direct-queue.md) の直接Queueガイドを使います。

## 4. 変更に合った検証をする

対象機能のディレクトリで、まずJavaと設定スクリプトを確認します。

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
| 環境変数や上限 | 読み込み処理、example、README、Python設定テストを揃える |
| 文書のみ | ファイル・リンク・コマンド・既定値を実装と照合する |

### Excelの実変換と非同期

作業場所は `functions/excel2md` です。

```sh
./mvnw package
python3 scripts/test_async_e2e.py
```

このE2Eは専用FunctionsホストとAzuriteを起動し、同期HTTP・HTTPからのジョブ登録・別Storageへの直接Queue投入・重複配送・成果物を確認して、自分が起動したプロセスを停止します。既定のFunctionsポートは7073、Azuriteは12200/12201/12202です。

複雑な入力の確認には [包括Excelの実HTTP・Queue検証手順](../functions/excel2md/samples/README.md) を使います。サンプルの数式には、再計算されないことを検証するため意図的に計算式と異なる保存済み値があります。検証前にExcelなどで開いて再保存すると、その条件が変わる場合があります。

### PowerPoint / PDFの非同期

作業場所は `functions/ppt-pdf-to-images` です。

```sh
./mvnw verify
python3 scripts/test_async_e2e.py --storage-integration-test
```

専用AzuriteでHTTP・直接Queue・別Storage・ZIPと個別画像・重複依頼を確認します。`--storage-integration-test` は、そのテスト用Storageに対するJavaの統合テストも実行します。既定のFunctionsポートは7073、Azuriteは11000/11001/11002です。

両機能のQueue E2EはFunctionsポート7073を使うので順番に実行するか、片方に `--port 7074` などを指定します。PPT/PDFのE2Eは一時成果物を保持します。Excelでは既定でテスト用作業ディレクトリを削除し、`--keep-artifacts` で保持できます。

### ブラウザー

Node.jsから `playwright` を読み込め、Chromiumを起動できる環境を用意します。ブラウザーテスト用の `package.json` やロックファイルは同梱していないため、開発環境側でPlaywrightを用意し、利用したバージョンを検証結果に残します。既存のChromeを使う場合は `PLAYWRIGHT_CHANNEL=chrome` を指定できます。モジュールの置き場所が別なら `NODE_PATH` を設定します。

Excelでは `functions/excel2md` で実行します。

```sh
node scripts/test_playground_browser.cjs --out /tmp/excel2md-browser
```

既定は隔離したテストAPIでの検証です。Javaテストが生成した資料と、別ターミナルで起動済みのExcelホストを使う場合は次を実行します。

```sh
node scripts/test_playground_browser.cjs --base http://localhost:7072 --fixtures target/fixtures --out /tmp/excel2md-live
```

PowerPoint / PDFでは `functions/ppt-pdf-to-images` で実行します。

```sh
./mvnw package
python3 scripts/test_playground_e2e.py
```

このラッパーはFunctionsの7081/7082とAzuriteの12000/12001/12002を使い、テスト後に自分が起動したプロセスを停止します。

ローカルのHTTP・Queue・ブラウザー検証はAzure実機での検証とは別です。クラウドでの処理時間・メモリー・同時実行数は、配置先と実際の資料で確認します。

## 5. Azureへ配置する

配置先は機能ごとの既存Function Appです。Azure CLIにログインし、対象のサブスクリプション・リソースグループ・アプリ名を確認してから進めます。Linux・Java 21・Functions v4を前提に、有効なホスト用Storageと `JAVA_OPTS=-Djava.awt.headless=true` を設定します。既存のJavaオプションがある場合は保持して追加します。

1. 対象機能のテストを実行し、`./mvnw package` で配布物を作る。
2. 配置先の上限・Storage接続・非同期の派生設定を揃える。
3. 配布ディレクトリからCore Toolsで公開する。
4. Azure上で小さな実ファイルを変換し、非同期を使う場合はジョブと成果物取得も確認する。

非同期設定を更新するコマンドは、対象機能のディレクトリから実行します。制御用接続を環境変数へ設定してから使用してください。空・未設定で実行すると非同期を無効化します。

```sh
python3 scripts/configure_azure_async.py --resource-group YOUR_RESOURCE_GROUP --name YOUR_FUNCTION_APP
```

このスクリプトはプロセスの環境変数を読み、`local.settings.json` は読みません。指定アプリの接続・派生設定を更新しますが、Azureリソース作成やコードのデプロイは行いません。追加Storageの登録・上限・ホスト用Storageは別途設定し、詳しい手順は [Excel](../functions/excel2md/README.md) / [PowerPoint・PDF](../functions/ppt-pdf-to-images/README.md) のAzure配置節を参照します。

Excelの既定の配布物を公開する例です。作業場所は `functions/excel2md` です。

```sh
cd target/azure-functions/excel2md-local
func azure functionapp publish YOUR_FUNCTION_APP --no-build
```

PowerPoint / PDFでは、`functions/ppt-pdf-to-images/target/azure-functions/slide2image-local` から同じpublishコマンドを実行します。配布ディレクトリ名は既定値で、Azure上の実アプリ名とは別です。`local.settings.json` の秘密情報を公開設定へ自動転送するオプションは付けません。

## 6. 実装を拡張するときに守ること

- **変換処理はHTTPとQueueで共有する。** 入口に形式ごとの別実装を追加せず、共通コアを変更し、両経路の成果物を確認する。
- **Excelの数式は一切評価しない。** `IMPORTRANGE`、`WEBSERVICE`、未知・入れ子の式も保存済み値だけを読み、キャッシュ欠落を外部取得で補完しない。関数名の禁止リストに依存しない。[外部数式テスト](../functions/excel2md/src/test/java/com/convertx2x/excel2md/conversion/ExternalFormulaIsolationTest.java)を維持する。
- **入力の文字列と実行対象を分ける。** Markdown/HTMLのエスケープ、リンクのスキーム検証、成果物に限定した画像表示を維持する。Playgroundに任意URLからの画像取得を追加しない。
- **Storageは管理者が登録した接続を使う。** Queueへ任意URL・SAS・接続文字列を追加しない。重複配送、リース、ETag、成果物公開の順序を変更するときは競合・失敗時も確認する。
- **上限と後始末を保つ。** 画像バッファや表の展開前に上限を確認し、途中までの出力を成功扱いにしない。一時ファイルや変換結果は例外時にも解放する。
- **変換品質と保存内容を説明する。** Excelの取消線除外、画像の原本抽出、未対応要素の警告を保つ。フォント・ライブラリ・サンプルを追加したら、出典とライセンスも更新する。

機能内での具体的な変更箇所は [Excel](excel2md.md) / [PowerPoint・PDF](ppt-pdf-to-images.md)、独立した新機能の追加は [CONTRIBUTING](../CONTRIBUTING.md) を参照してください。

## 7. 問題を切り分ける

| 症状 | 最初に確認すること |
| --- | --- |
| Javaのビルド・起動に失敗 | JDK 21と `JAVA_HOME`、対象機能の作業ディレクトリ、初回依存取得の通信 |
| 変更したコードやUIが反映されない | `--skip-build` で古い配布物を起動していないか。再ビルド後にホストを再起動 |
| 非同期が選べない・503になる | 制御用接続の有無、設定ファイルより優先される環境変数、起動時の派生設定 |
| Queueが処理されない | Blob・Queue・ホスト用Storage、機能ごとのQueue名、JSON契約、Base64二重適用の有無 |
| ローカルでは成功しAzureで401になる | ローカルの認証条件との差、`x-functions-key`。非同期では状態・成果物取得にも有効なアプリのホストキーを使う |
| 上限エラー・同期タイムアウト | エラーコードと機能の上限表。メモリー・出力も含めて確認し、長い処理は非同期へ |
| Excelの値が古い・数式が文字で出る | 保存済み計算結果の有無。変換側は再計算しないため、元ファイル側の保存状態を確認 |
| 日本語や図形の見た目が違う | 同梱フォント、元フォントの有無、Excelの `report.json` と描画の対応範囲 |
| 非同期の結果が見つからない | 成功状態が公開したパス・manifestを使っているか。試行途中のBlobを一覧から拾わない |

調査結果には再現手順、秘密情報を除いたエラーコード、入力形式、期待値と実際の成果物を残します。実資料をそのまま公開サンプルやテスト結果へ追加しないでください。
