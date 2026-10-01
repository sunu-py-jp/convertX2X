# office2pdf

Excel・Word・PowerPointを、Azure AI Document Intelligenceへ渡しやすい
正規化PDFへ変換するAzure Functionsアプリです。Java 21、Apache POI、
Apache PDFBoxだけで動作し、LibreOfficeやネイティブ実行ファイルは使いません。

| 入力 | 出力方法 |
| --- | --- |
| `.xlsx` / `.xls` / `.docx` / `.pptx` / `.ppt` | 同期HTTPでPDFを直接返す |
| 同上 | 非同期HTTPまたは直接QueueでPDF・reportをBlobへ保存する |

PDFは元のOffice印刷結果の完全な再現ではありません。ExcelとWordは文字・表を
検索可能なPDFへ再配置し、PowerPointは表示スライドを1ページずつ画像化します。

## ドキュメント

- [API Docs](../../docs/APIDocs/office2pdf/index.html)
- [利用・起動・配置ガイド](docs/usage.md)
- [直接Queue連携](docs/direct-queue.md)
- [実装ガイド](../../docs/office2pdf.md)
- [実変換サンプル](samples/README.md)

## 最短の起動

Java 21、Python 3.10以上、Azure Functions Core Tools v4、Azuriteを用意します。

```shell
python3 scripts/run_local.py
```

Playgroundは <http://localhost:7074/api/playground> です。同期HTTPだけなら
変換用Storageは不要です。非同期を使う場合は `local.settings.json` に
`CONVERSION_STORAGE_CONNECTION_STRING` を設定します。

## ライセンス

アプリのコードは [MIT](LICENSE) です。Apache POIとApache PDFBoxは
Apache License 2.0、同梱BIZ UDPゴシックはSIL Open Font License 1.1です。
いずれも商用利用できます。配布時の表示物は [NOTICE.md](NOTICE.md) と
フォントの [OFL.txt](src/main/resources/fonts/bizud/OFL.txt) に含めています。
