# convertX2X

変換用途ごとのAzure Functionsをまとめたコレクションです。必要な機能を独立したFunction Appへ配置し、同期HTTP・非同期HTTP・Queueから利用できます。

## できること

| 機能 | 入力 → 出力 | Docs |
| --- | --- | --- |
| PowerPoint / PDF → 画像 | PPTX・PPT・PDF → PNG・JPEG・ZIP | [API資料](docs/APIDocs/ppt-pdf-to-images/index.html) |
| Office → Markdown | Excel・Word・PowerPoint → Markdown・画像 | [API資料](docs/APIDocs/office2md/index.html) |
| Movie → AAC | 動画ファイル・URL・Blob → AAC音声をコピーしたM4A | [API資料](docs/APIDocs/movie2audio/index.html) |

画像・Office変換はJava 21、Movie → AACはNode.js 22 / 24と同梱FFmpegで実装しています。各機能に簡易Playgroundがあります。Movie → AACは、書き込み用SASで指定したBlobへの保存にも対応します。

非同期は `CONVERSION_STORAGE_CONNECTION_STRING` 設定時に有効になります。外部システムからQueueへ直接依頼でき、入力・出力に別のStorageアカウントを登録することもできます。

## ドキュメント

- [APIリファレンス](docs/APIDocs/index.html)：同期・非同期・直接Queueの仕様とリクエスト例
- [利用・開発ガイド](docs/README.md)：機能別の使い方、構成、起動・設定・検証・デプロイ
- [開発への参加](CONTRIBUTING.md)：機能追加・変更のルール

実装と配布物は `functions/ppt-pdf-to-images/`、`functions/office2md/`、`functions/movie2audio/` で個別に管理します。

## ライセンス

プロジェクトのコードは [MIT](LICENSE) です。同梱ライブラリ・フォント・FFmpegには各提供元のライセンスが適用されます。詳細は各機能のDocsと同梱ライセンスを参照してください。
