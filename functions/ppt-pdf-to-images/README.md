# PowerPoint / PDF to Images

[convertX2X](../../README.md) のPowerPoint・PDFを画像へ変換するAzure Functionsアプリです。PPTX・PPT・PDFをPNG／JPEGに変換し、画像1枚・ZIP・ページごとの画像Blobとして出力します。Java 21、Apache POI、PDFBoxを使用します。

同期HTTP・非同期HTTP・外部システムからの直接Queue依頼が、共通の変換処理を使います。ブラウザから試せる簡単なPlaygroundも含みます。このフォルダだけでビルド・起動・デプロイできます。

| 利用方法 | 依頼と結果 |
| --- | --- |
| 同期HTTP | ファイルを送信し、画像またはZIPを受け取る |
| 非同期HTTP | ファイルを送信し、ジョブの完了後に結果を取得する |
| 直接Queue | 入力Blobと出力先を指定したJSONを送信する |

非同期機能は `CONVERSION_STORAGE_CONNECTION_STRING` 設定時に有効になります。入力上限の既定値は20MiB・50ページで、環境変数から変更できます。画像サイズは元のページ寸法を基準に決まります。

## ドキュメント

| 知りたいこと | 参照先 |
| --- | --- |
| API一覧・リクエストとレスポンス | [API Docs](../../docs/APIDocs/ppt-pdf-to-images/index.html) |
| ローカル起動・Playground・設定・デプロイ・検証 | [利用ガイド](docs/usage.md) |
| 直接QueueのJSON・別Storageとの連携 | [Queue連携ガイド](docs/direct-queue.md) |
| コード構成・開発と保守 | [開発者ガイド](../../docs/ppt-pdf-to-images.md) |
| 共通の環境構築・作業手順 | [開発ドキュメント](../../docs/development.md) |

## ライセンス

コードは [MIT License](LICENSE) です。依存ライブラリ・同梱フォントなどには各提供元のライセンスが適用されます。[出典とライセンスの詳細](docs/usage.md#ライセンス)を参照してください。
