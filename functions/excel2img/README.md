# excel2img

Excelのセル・貼り付け画像・基本図形を、配置を保ったPNGまたはPDFへ変換する
独立したAzure Functionsアプリです。画像を含む資料を、そのままLLMへ渡す用途を想定しています。

Java 21、Apache POI、Java2D、PDFBoxで動作します。ExcelやLibreOfficeのインストールは不要です。
横長の範囲は、文字の最小サイズを保てる範囲で縮小し、収まらない分を横方向にも分割します。
各ページの元の行・列範囲と座標を `report.json` に記録します。

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

Java 21、Python 3.10以上、Azure Functions Core Tools v4、Node.js・npm、Azurite CLIを用意します。
既定設定はホスト用Storageに `UseDevelopmentStorage=true` を使うため、同期HTTPだけでもAzuriteが必要です。

```shell
npm install -g azurite
python3 scripts/run_local.py
```

WindowsのPowerShellでは次を使います。

```powershell
npm.cmd install -g azurite
azurite.cmd --version
python scripts/run_local.py
```

VS CodeのAzurite拡張だけではCLIがPATHに登録されない場合があります。
起動済みのAzurite（Blob 10000・Queue 10001）は再利用します。
導入の詳細は [Microsoft公式手順](https://learn.microsoft.com/azure/storage/common/storage-install-azurite?tabs=npm)、
起動に失敗した場合は [利用ガイドの切り分け手順](docs/usage.md#azuriteが起動しない場合)を参照してください。

Playgroundは <http://localhost:7076/api/playground> です。同期HTTPだけなら
変換用Storageは不要です。非同期を使う場合は `local.settings.json` に
`CONVERSION_STORAGE_CONNECTION_STRING` を設定します。

## ライセンス

アプリのコードは [MIT](LICENSE) です。第三者のライブラリ・フォントの原文とNOTICEは
[NOTICE.md](NOTICE.md) と `third-party/` に保持しています。ビルド時に検証し、
この機能単独の配布物にも同梱します。
