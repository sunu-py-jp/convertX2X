# convertX2X

変換用途ごとのAzure Functionsをまとめたコレクションです。必要な機能を独立したFunction Appへ配置し、その機能が対応するHTTP・Queue・CLIから利用できます。

## できること

| 機能 | 入力 → 出力 | Docs |
| --- | --- | --- |
| PowerPoint / PDF → 画像 | PPTX・PPT・PDF → PNG・JPEG・ZIP | [API資料](docs/APIDocs/ppt-pdf-to-images/index.html) |
| Office → Markdown | Excel・Word・PowerPoint → Markdown・画像、任意で埋め込み画像をOCR | [API資料](docs/APIDocs/office2md/index.html) |
| Excel → 画像・PDF | XLSX・XLSのセル・画像・図形を配置を保ってPNG・PDFへ | [API資料](docs/APIDocs/excel2img/index.html) |
| Office → PDF | Excel・Word・PowerPoint → Document Intelligence向けPDF | [API資料](docs/APIDocs/office2pdf/index.html) |
| Markdown → PDF | Markdown・画像入りZIP → 検索可能な日本語PDF | [API資料](docs/APIDocs/md2pdf/index.html) |
| Movie → Audio | 動画ファイル・URL・Blob → AACコピーのM4A、または音声変換したM4A・WAV | [API資料](docs/APIDocs/movie2audio/index.html) |
| HTML → PowerPoint | HTML・素材入りZIP → 編集可能な文字・表・SVG図形を持つPPTX。対応するCSSアニメーションも反映 | [API資料](docs/APIDocs/html2pptx/index.html) |

画像・Office・Markdown変換はJava 21、Movie → AudioはNode.js 22 / 24と同梱FFmpeg、HTML → PowerPointはNode.js 24と同梱Chromiumで実装しています。各機能に簡易Playgroundがあります。Movie → Audioは、書き込み用SASで指定したBlobへの保存にも対応します。

非同期対応の機能は、接続文字列またはManaged Identity用の制御Storage設定が揃うと有効になります。外部システムからQueueへ直接依頼でき、入力・出力に別のStorageアカウントを登録することもできます。HTML → PowerPointは現在、同期HTTPとCLIに対応しています。

## ドキュメント

- [公開API資料（GitHub Pages）](https://sunu-py-jp.github.io/convertX2X/)
- [APIリファレンス](docs/APIDocs/index.html)：同期・非同期・直接Queueの仕様とリクエスト例
- [利用・開発ガイド](docs/README.md)：機能別の使い方、構成、起動・設定・検証・デプロイ
- [開発への参加](CONTRIBUTING.md)：機能追加・変更のルール

実装と配布物は `functions/ppt-pdf-to-images/`、`functions/office2md/`、`functions/office2pdf/`、`functions/md2pdf/`、`functions/excel2img/`、`functions/movie2audio/`、`functions/html2pptx/` で個別に管理します。

## ライセンス

プロジェクトのコードは [MIT](LICENSE) です。同梱ライブラリ・フォント・FFmpegには各提供元のライセンスが適用されます。詳細は各機能のDocsと同梱ライセンスを参照してください。
