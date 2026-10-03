# md2pdf

Markdownを検索可能な日本語PDFへ変換する、独立したAzure Functionsアプリです。
Java 21、CommonMark、Apache PDFBoxで動作し、従量課金Function Appへ配置できます。

| 入力 | 出力方法 |
| --- | --- |
| UTF-8の `.md` / `.markdown` | 同期HTTPでPDFを直接返す |
| Markdownと画像を含む `.zip` | 同期HTTPでPDFを直接返す |
| 同上 | 非同期HTTPまたは直接QueueでPDF・reportをBlobへ保存する |

見出し、段落、太字・斜体、リスト、表、引用、コード、リンク、画像、改ページ、
ページ番号に対応します。PDFは内容を読みやすく再配置した正規化出力です。

## ドキュメント

- [API Docs](../../docs/APIDocs/md2pdf/index.html)
- [利用・起動・配置ガイド](docs/usage.md)
- [直接Queue連携](docs/direct-queue.md)
- [実装ガイド](../../docs/md2pdf.md)
- [変換サンプル](samples/README.md)

## 最短の起動

Java 21、Python 3.10以上、Azure Functions Core Tools v4、Azuriteを用意します。

```shell
python3 scripts/run_local.py
```

Playgroundは <http://localhost:7075/api/playground> です。Markdownの貼り付け、
ファイル・ZIP選択、同期・非同期変換、PDFのプレビューと保存ができます。
非同期を使う場合は `local.settings.json` にStorage接続を設定します。

## ライセンス

アプリのコードは [MIT](LICENSE)、CommonMarkはBSD 2-Clause、Apache PDFBoxは
Apache License 2.0、同梱BIZ UDPゴシックはSIL Open Font License 1.1です。
いずれも商用利用できます。[NOTICE.md](NOTICE.md) に配布物の表示をまとめています。
