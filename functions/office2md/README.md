# office2md

[convertX2X](../../README.md) のOffice文書をMarkdownへ変換するAzure Functionsアプリです。Excel（XLSX・XLS）、Word（DOCX）、PowerPoint（PPTX）から、`document.md`・`report.json`・`images/` の画像を取り出し、指定時に埋め込み画像のOCRテキストを補います。Java 21とApache POIを使用します。

同期HTTP・非同期HTTP・外部システムからの直接Queue依頼が、共通の変換処理を使います。ブラウザでMarkdownと画像を確認できるPlaygroundも含みます。

| 利用方法 | 依頼と結果 |
| --- | --- |
| 同期HTTP | ファイルを送信し、Markdown・画像・変換情報を含むZIPを受け取る |
| 非同期HTTP | ファイルを送信し、完了後にMarkdownを取得する |
| 直接Queue | 入力Blobと出力先を指定したJSONを送信する。version 2の `imageMode: "ocr"` に対応 |

非同期機能は `CONVERSION_STORAGE_CONNECTION_STRING` 設定時に有効になります。表・太字・リンクを保持し、各形式の図中の文字と確認できた接続関係も本文に出します。図形・接続のJSONと、図またはスライドをまとめた確認用画像も添えます。取消線の文字は除去し、数式の再計算や文書内の外部参照へのアクセスは行いません。

## ドキュメント

| 知りたいこと | 参照先 |
| --- | --- |
| API一覧・リクエストとレスポンス | [API Docs](../../docs/APIDocs/office2md/index.html) |
| 変換ルール・ローカル起動・設定・デプロイ・検証 | [利用ガイド](docs/usage.md) |
| 直接QueueのJSON・別Storageとの連携 | [Queue連携ガイド](docs/direct-queue.md) |
| コード構成・開発と保守 | [開発者ガイド](../../docs/office2md.md) |
| 画像・図形・表の重なり合成の差分 | [変更内容のREADME](docs/image-shape-composition/README.md) |
| 共通の環境構築・作業手順 | [開発ドキュメント](../../docs/development.md) |
| 入力サンプル・再生成手順 | [サンプル集](samples/README.md) |

## ライセンス

コードは [MIT License](LICENSE) です。依存ライブラリ・同梱フォントには各提供元のライセンスが適用されます。[出典とライセンスの詳細](docs/usage.md#ライセンス)を参照してください。

画像参照は常に保持します。既定の `imageMode=ignore` はOCRをせず、`ocr` は設定済みのDocument Intelligenceへ元の埋め込み画像を送り、その位置へ文字を追加します。接続設定・失敗時の扱いは[利用ガイド](docs/usage.md#画像ocr)を参照してください。Office2MDのPDF出力は廃止しました。

Excel・Word・PowerPointの重なる画像同士、画像と図形・テキストボックス、表・セルと描画要素は、位置を確定できる範囲で、元の重なり順を保った1枚のPNGへまとめます。表とセル本文はMarkdownにも残します。画像からはみ出す注釈全体が収まるようキャンバスを広げ、拡張領域の背景だけを透明にします。図形の塗り・線・文字を保持し、元画像と同じ図形を単独で重複出力しません。離れた図形や接触だけの図形は別に残し、図形文字と確認できた接続はMarkdownでも保持します。合成画像は `sourceKind: "compositeImage"` で識別でき、OCRは合成前の元画像の可視領域を対象にします。

Wordは同一描画キャンバス内、または同一段落内の共通座標基準を持つ浮動オブジェクトが対象です。段落をまたぐ位置やページ組版は推測しません。[形式ごとの条件と実変換例](../../docs/APIDocs/office2md/examples.html#word-annotated-images)を確認できます。
