# excel2img

Excelのセル・貼り付け画像・基本図形を、配置を保ったPNGまたはPDFへ変換する
独立したAzure Functionsアプリです。画像を含む資料を、そのままLLMへ渡す用途を想定しています。

Java 21、Apache POI、Java2D、PDFBoxで動作します。ExcelやLibreOfficeのインストールは不要です。
文字が小さくなりすぎる横長の範囲は、理由を `report.json` に記録して変換を見送ります。

| 入力 | 出力方法 |
| --- | --- |
| `.xlsx` / `.xls` | ページごとのPNGとreportを含むZIP |
| 同上、`format=pdf` | PDFとreportを含むZIP |

Excelの完全な印刷再現ではありません。同期HTTP・非同期HTTP・直接Queueが同じ描画処理を利用します。
OCRやLLMへの送信は行いません。

## ドキュメント

- [API Docs](../../docs/APIDocs/excel2img/index.html)
- [利用・起動・配置ガイド](docs/usage.md)
- [直接Queue連携](docs/direct-queue.md)
- [実装ガイド](../../docs/excel2img.md)
- [実際の入力・変換結果](../../docs/APIDocs/excel2img/examples.html)

## 最短の起動

Java 21、Python 3.10以上、Azure Functions Core Tools v4を用意します。

```shell
python3 scripts/run_local.py
```

Playgroundは <http://localhost:7076/api/playground> です。同期HTTPだけなら
変換用Storageは不要です。非同期を使う場合は `local.settings.json` に
`CONVERSION_STORAGE_CONNECTION_STRING` を設定します。

## ライセンス

アプリのコードは [MIT](LICENSE) です。第三者のライブラリ・フォントの原文とNOTICEは
[NOTICE.md](NOTICE.md) と `third-party/` に保持しています。ビルド時に検証し、
この機能単独の配布物にも同梱します。
