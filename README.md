# convertX2X

変換用途ごとのAzure Functionsをまとめたコレクションです。必要な変換機能を選び、HTTP APIやQueue経由で外部システムから利用できます。

各機能は `functions/` 配下の独立したプロジェクトとして管理します。ビルド・設定・デプロイの単位を機能ごとに分けることで、変換に必要なライブラリやランタイムを個別に選べます。

## 利用できる変換

| 機能 | 入力 | 出力 | 呼び出し方 | 実装 |
| --- | --- | --- | --- | --- |
| [PowerPoint / PDF to Images](functions/ppt-pdf-to-images/README.md) | PowerPoint（PPTX・PPT）、PDF | PNG・JPEG、全ページZIP、ページごとの画像Blob | 同期HTTP・非同期HTTP・直接Queue | Java 21 / Apache POI / PDFBox |

現在の実装は **PowerPoint / PDF to Images** です。新しい変換機能は、実装と検証ができた段階でこの一覧へ追加します。

## まず試す

PowerPoint / PDF to Imagesをローカルで起動するには、JDK 21、Azure Functions Core Tools v4、Python 3を用意します。Mavenは機能フォルダ内のWrapperを使います。

```bash
cd functions/ppt-pdf-to-images
python3 scripts/run_local.py
```

[Playground](http://localhost:7071/api/playground)を開き、PowerPointまたはPDFを選択すると変換できます。PlaygroundはHTML・CSS・JavaScriptのみで、フロントエンドのビルドは不要です。

既定は全ページを元の寸法・96dpiでPNGに変換し、ZIPとして返します。入力上限は20MiB・50ページで、環境変数から変更できます。

非同期を利用する場合は `CONVERSION_STORAGE_CONNECTION_STRING` を設定します。直接Queueへの依頼では、登録済みStorageの入力・出力先をJSONで指定でき、`output.mode: "images"` にすると1ページずつ画像Blobへ保存します。

詳しい使い方は次を参照してください。

- [起動・HTTP API・環境変数・Azureへの配置](functions/ppt-pdf-to-images/README.md)
- [外部システムからQueueへ直接依頼する](functions/ppt-pdf-to-images/docs/direct-queue.md)
- [ZIP保存のJSON例](functions/ppt-pdf-to-images/examples/queue-request.json)
- [個別画像保存のJSON例](functions/ppt-pdf-to-images/examples/queue-request-images.json)
- [別Storageを使うJSON例](functions/ppt-pdf-to-images/examples/queue-request-cross-account.json)
- [JavaからBlobへアップロードしてQueueへ送る例](functions/ppt-pdf-to-images/examples/QueueProducer.java)

## リポジトリ構成

```text
convertX2X/
├── README.md
├── CONTRIBUTING.md
├── LICENSE
└── functions/
    └── ppt-pdf-to-images/
        ├── README.md
        ├── LICENSE
        ├── pom.xml / mvnw / .mvn/
        ├── host.json
        ├── local.settings.example.json
        ├── src/
        ├── scripts/
        ├── docs/
        ├── examples/
        └── samples/
```

各機能のフォルダには、変換処理、Azure Functionsの入口、設定例、テスト、利用手順をまとめます。機能ごとに使用言語やビルド方法を選べるため、リポジトリ全体に共通のMaven親プロジェクトは置いていません。

PowerPoint / PDF to Imagesは、1つのFunction AppでHTTPの入口とQueue `conversion-jobs` を共有します。Azureに依存しない `ConversionService` が入力内容に応じて `PptConverter`（PPT・PPTX）または `PdfConverter`（PDF）を選びます。形式ごとの描画を分け、上限・同時実行制御・画像エンコード・ZIP・ページごとの出力は共通化しています。

ここでの提供単位は、まとまった変換機能を単独でデプロイできるFunctionsプロジェクトです。既存の文書画像変換に入力形式を追加する場合は、このプロジェクト内のコンバーターを拡張します。Maven Centralなどに公開したJavaライブラリはまだありません。

リポジトリ名とAzure上のFunction App名は別です。PowerPoint / PDF to Imagesの既定のローカル成果物名は `slide2image-local` で、実際のAzureアプリ名は配置先に合わせて設定します。

## 検証する

各機能のフォルダで検証します。PowerPoint / PDF to Imagesの場合：

```bash
cd functions/ppt-pdf-to-images
./mvnw verify
python3 -m unittest discover -s scripts -p 'test_*.py'
```

AzuriteとCore Toolsを使ったHTTP・Queue・別Storageの連携テスト、Playwrightによるブラウザーテストも用意しています。[検証手順](functions/ppt-pdf-to-images/README.md#8-検証とプロジェクト構成)を参照してください。

ローカル起動や検証のコマンドはAzureへの公開を行いません。Azureの接続設定を更新するスクリプトは、各機能のREADMEに更新対象と実行方法を記載しています。

## 変換機能を追加する

独立した変換機能は `functions/<機能名>/` にプロジェクトを追加し、対応形式・入出力・設定・制限・検証方法を機能のREADMEへ記載します。既存機能の入力形式を増やす手順も含め、[CONTRIBUTING.md](CONTRIBUTING.md) を参照してください。

## ライセンス

プロジェクトのコードは [MIT License](LICENSE) です。独立して利用できるよう、各機能にもライセンス本文を配置します。

依存ライブラリやサンプル資料には、それぞれの提供元のライセンスが適用されます。PowerPoint / PDF to Imagesに用意したPowerPointテンプレートの出典と利用条件は [サンプルのREADME](functions/ppt-pdf-to-images/samples/templates/README.md) を参照してください。

PowerPoint / PDF to Imagesに同梱する日本語フォントNoto Sans CJK JP・Noto Serif CJK JPはSIL Open Font License 1.1です。取得元・著作権表示・ライセンス原文は [フォントのREADME](functions/ppt-pdf-to-images/src/main/resources/fonts/noto/README.md) を参照してください。
