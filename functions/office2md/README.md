# office2md

[convertX2X](../../README.md) のOffice文書をMarkdownへ変換するAzure Functionsアプリです。Excel（XLSX・XLS）、Word（DOCX）、PowerPoint（PPTX）から、`document.md`・`report.json`・`images/` の画像を取り出します。Java 21とApache POIを使用します。

同期HTTP・非同期HTTP・外部システムからの直接Queue依頼が、共通の変換処理を使います。ブラウザでMarkdown・画像・警告を確認できるPlaygroundも含みます。このフォルダだけでビルド・起動・デプロイできます。

| 利用方法 | 依頼と結果 |
| --- | --- |
| 同期HTTP | ファイルを送信し、Markdown・画像・変換情報を含むZIPを受け取る |
| 非同期HTTP | ファイルを送信し、完了後にMarkdown・画像などを個別取得する |
| 直接Queue | 入力Blobと出力先を指定したJSONを送信する |

非同期機能は `CONVERSION_STORAGE_CONNECTION_STRING` 設定時に有効になります。表・太字・リンクを保持し、各形式の図中の文字と確認できた接続関係も本文に出します。図形・接続のJSONと、図またはスライドをまとめた確認用画像も添えます。取消線の文字は除去し、数式の再計算や文書内の外部参照へのアクセスは行いません。

## ドキュメント

| 知りたいこと | 参照先 |
| --- | --- |
| API一覧・リクエストとレスポンス | [API Docs](../../docs/APIDocs/office2md/index.html) |
| 変換ルール・ローカル起動・設定・デプロイ・検証 | [利用ガイド](docs/usage.md) |
| 直接QueueのJSON・別Storageとの連携 | [Queue連携ガイド](docs/direct-queue.md) |
| コード構成・開発と保守 | [開発者ガイド](../../docs/office2md.md) |
| 共通の環境構築・作業手順 | [開発ドキュメント](../../docs/development.md) |
| 入力サンプル・再生成手順 | [サンプル集](samples/README.md) |

## ライセンス

コードは [MIT License](LICENSE) です。依存ライブラリ・同梱フォントには各提供元のライセンスが適用されます。[出典とライセンスの詳細](docs/usage.md#ライセンス)を参照してください。
