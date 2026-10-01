# office2pdf 利用ガイド

## 変換方針

PDFはAzure AI Document Intelligenceへの中間入力です。Officeの印刷組版を
再現するのではなく、内容を欠落させにくく、文字・表を読み取りやすい形へ
正規化します。

- Excel: 表示シートごとに、値のある行・列を一つの表へまとめます。空列を挟んだ
  左右の補足表も同じ表に入ります。数式は再計算せず保存済み値を使います。
- Word: 本文、見出し、箇条書き、表、対応画像を本文順に配置します。
- PowerPoint: 表示スライドをPOIのJava2D描画で1スライド1ページにします。
  スライド文字は画像になるため、後段のOCRで読み取ります。

外部参照やリンク先へアクセスしません。暗号化ファイルは対象外です。

## ローカル起動

必要なものはJava 21、Python 3.10以上、Azure Functions Core Tools v4、Azuriteです。
WindowsではMaven WrapperとCore Toolsの `.cmd` を明示的に解決します。

```shell
cd functions/office2pdf
python scripts/run_local.py        # Windows
# または
python3 scripts/run_local.py       # macOS / Linux
```

既定ポートは `7074`、Playgroundは
`http://localhost:7074/api/playground` です。ビルド済み成果物を再利用する場合は
`--skip-build` を付けます。

同期HTTPの確認例です。

```shell
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @samples/excel-normalized/input.xlsx \
  'http://localhost:7074/api/convert?filename=input.xlsx' \
  --output document.pdf
```

## 非同期をローカルで使う

`local.settings.example.json` を `local.settings.json` へコピーして次を設定します。

```json
{
  "Values": {
    "AzureWebJobsStorage": "UseDevelopmentStorage=true",
    "CONVERSION_STORAGE_CONNECTION_STRING": "UseDevelopmentStorage=true",
    "CONVERSION_CREATE_RESOURCES": "true"
  }
}
```

`UseDevelopmentStorage=true` の場合、起動スクリプトは既定ポート（Blob `10000`、
Queue `10001`）を確認し、Azuriteが停止中なら起動します。既存プロセスがあれば
そのまま使います。Functionsホストを起動する前に `office2pdf-jobs` コンテナー、
処理Queue、毒Queueを作成します。
非同期設定が空ならQueue関数と保守タイマーを無効化して同期HTTPだけ起動します。

## HTTP API

| メソッド | パス | 内容 |
| --- | --- | --- |
| `POST` | `/api/convert?filename=...` | 生のOfficeファイルを送り、PDFを受け取る |
| `POST` | `/api/jobs?filename=...` | 非同期ジョブを登録する |
| `GET` | `/api/jobs/{id}` | 状態と成果物URLを取得する |
| `GET` | `/api/jobs/{id}/result` | `document.pdf` を取得する |
| `GET` | `/api/jobs/{id}/report` | `report.json` を取得する |
| `GET` | `/api/jobs/{id}/archive` | PDFとreportのZIPを取得する |
| `GET` | `/api/capabilities` | 対応形式と公開上限を取得する |

入力はmultipartではなくファイルのバイト列です。Azureでは変換・ジョブAPIに
`x-functions-key` を付けます。Playgroundとcapabilitiesは匿名で開けます。

## 上限

内容の個数制限は既定で設けません。シート数、行・列数、Wordの段落・表数、
スライド数、図形数に固定上限はありません。リソース枯渇を防ぐ境界だけを設定します。

| 設定 | 既定 | 用途 |
| --- | ---: | --- |
| `CONVERSION_MAX_INPUT_BYTES` | 20 MiB | HTTP・Blobから読む入力全体 |
| `CONVERSION_MAX_OUTPUT_BYTES` | 100 MiB | PDF・report・ZIPの出力境界 |
| `CONVERSION_MAX_PAGES` | `0` | 生成ページ数。`0`は無制限 |
| `CONVERSION_MAX_IMAGE_PIXELS` | 20,000,000 | 1画像または1スライド描画の画素数 |

`host.json` は1インスタンス内のHTTP同時処理とQueueバッチを1にしています。
アプリ内にも変換セマフォを置き、4GB級の従量課金インスタンスで複数の重い
Office変換が同時にメモリを消費しない構成です。大きい入力は非同期Queueへ送り、
Azure側のスケールで並列化します。

## Azureへの配置

MavenパッケージはLinux / Java 21のFunction App向けです。

```shell
./mvnw -B -ntp package
./mvnw -DfunctionAppName='<function-app-name>' azure-functions:deploy
```

Flex Consumptionなどの従量課金Function Appを作成し、必要ならStorage設定を
追加します。接続文字列の代わりに
`CONVERSION_STORAGE__blobServiceUri` と
`CONVERSION_STORAGE__queueServiceUri` を設定するとManaged Identityを使えます。
詳細な別Storage・結果通知・保持設定は[直接Queueガイド](direct-queue.md)を参照してください。

## ライセンス

- アプリコード: MIT
- Apache POI / Apache PDFBox: Apache License 2.0
- BIZ UDPゴシック: SIL Open Font License 1.1

同梱フォントは公式リポジトリの固定コミットから取得した未改変TTFです。出典、
ハッシュ、ライセンス本文は `src/main/resources/fonts/bizud/` にあります。
