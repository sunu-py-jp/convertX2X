# excel2img 利用ガイド

## 用途と描画範囲

Excelのセルと貼り付け画像を、一緒にLLMへ渡せるPNGまたはPDFへ描画します。
列幅・行高・結合・罫線・背景色・日本語文字・基本図形を反映します。
Excelの印刷エンジンではないため、フォント置換や折り返し、図形の描画に差があります。

選択は次の順です。

1. `sheet` と `range` があれば、そのセル範囲。
2. 印刷範囲があれば、その範囲。複数の印刷範囲は個別に処理。
3. それ以外は値や描画要素のある範囲。

`sheet` を省略すると表示シートを対象にします。明示すれば非表示シートも選択できます。
非表示行・列は描画しません。数式は保存済みの値を読み、再計算・外部参照の取得はしません。

横幅に合わせて縮小し、セル文字の最小サイズ（既定12px）を保てなくなる場合は、
その下限まで縮小したうえで横方向にも分割します。縦方向もページに分割します。
ページ順は上段の左から右、その次の段の左から右です。原則として行・列の境目で分け、
1ページに収まる結合セルは可能な範囲で分割を避けます。
1ページより大きい行・列・結合セルや、境界をまたぐ画像は、元の位置のまま複数ページへ続きます。
見出しの自動繰り返しは行いません。ページ数・画素数・総出力量の上限は引き続き適用します。

既定解像度でも12px未満になる文字は、拡大せず警告を残します。
セル文字がない範囲は、設定した解像度の50%を縮小の下限にして分割します。
画像内の文字の大きさは検査しません。

セルの折り返しと上下・左右の中央揃えは、結合セルなら結合後の全幅・全高を使って配置します。
折り返しのない左寄せ文字は、背景色が付いていても隣が空セルならその上へ表示できます。
隣の値・数式・結合領域や指定範囲の外へは延ばしません。
「縦書き」（rotation 255）は文字を正立させて縦に並べます。その他の角度の回転文字は横書きで近似して警告を残します。

複雑なグラフ、SmartArt、未対応の図形・画像形式などは完全再現できません。
省略・近似・見送りは `report.json` の警告と範囲情報を確認してください。
OCRやLLMへの送信は行いません。

## ローカル実行

JDK 21、Python 3.10以上、Azure Functions Core Tools v4が必要です。
既定の `AzureWebJobsStorage=UseDevelopmentStorage=true` では、同期HTTPを使う場合も
Node.js・npmとAzurite CLIが必要です。初回は `npm install -g azurite` で導入します。
WindowsのPowerShellでは `npm.cmd install -g azurite` を使い、
`azurite.cmd --version` で確認してください。VS Code拡張だけの導入ではCLIがPATHにない場合があります。
詳しくは [Microsoft公式のAzurite導入手順](https://learn.microsoft.com/azure/storage/common/storage-install-azurite?tabs=npm)を参照してください。

```sh
python3 scripts/run_local.py
```

Windowsでは同じディレクトリで `python scripts/run_local.py` を実行します。
起動スクリプトは、Blob 10000・Queue 10001で起動済みのAzuriteを再利用し、未起動ならCLIから起動します。

<http://localhost:7076/api/playground> でファイルと出力形式を選べます。
ポートを変える場合は `--port 7086`、ビルド済み配布物を使う場合は `--skip-build` を付けます。
設定は `local.settings.example.json` を基に、Git管理対象外の `local.settings.json` へ保存してください。

### Azuriteが起動しない場合

`AZURITE_NOT_FOUND` はCLIの検出、`AZURITE_LAUNCH_FAILED` はプロセス起動、
`AZURITE_EXITED` は起動直後の終了、`AZURITE_TIMEOUT` はBlob・Queueの待受開始を確認できない状態です。
各コードの対処は [共通ガイドの診断表](../../../docs/development.md#azuriteの起動診断)に記載しています。

`azurite.cmd --version` が表示できても起動に失敗するときは、別のPowerShellで前面起動し、
表示されるエラーを確認します。Azuriteのデータは実行したディレクトリに保存されます。

```powershell
node --version
Get-Command node, npm.cmd, azurite.cmd
azurite.cmd --disableTelemetry --skipApiVersionCheck
```

特にBlob 10000・Queue 10001・Table 10002のポート競合、Node.jsとPATHを確認してください。
既存のAzuriteが利用できる場合は、そのまま起動スクリプトを再実行します。
ポートを空けるために既存プロセスを一括終了せず、用途と所有者を確認してください。

## 同期HTTP

```sh
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @sample.xlsx \
  'http://localhost:7076/api/convert?filename=sample.xlsx&format=png' \
  --output rendered.zip
```

入力はファイルの生バイト列です。multipartやBase64ではありません。
`format` は `png`（既定）または `pdf`、任意に `sheet` と `range=A1:L30` を指定します。
`range` を使うときは `sheet` が必須です。シート名を含むクエリ値はURLエンコードしてください。
Azureでは `x-functions-key` ヘッダーにFunctionキーを付けます。

成功時は **PDFを選んでもZIP** を返します。

```text
# format=png
pages/page-001.png
pages/page-002.png
report.json

# format=pdf
document.pdf
report.json
```

PDFの検索用文字層は描画したセル文字が対象です。図形・画像と重なる文字は保守的に文字層から除外し、警告を残します。見た目はPNGと同じです。画像内や図形内の文字はOCRしません。

reportには処理範囲、ページとの対応、見送った理由、警告を記録します。
各ページの `firstRow` / `lastRow`、`firstColumn` / `lastColumn` は1始まりの元シートの行・列番号です。
`sourceBoundsPoints` はシート上の座標と描画範囲を示します。
一部を見送っても他に描画できる範囲があれば成功し、reportの `outcome` が `partial` になります。
全範囲を見送る場合はHTTP 422 `NO_RENDERABLE_RANGE` です。
この場合のJSON `details` から理由を確認できます。

## 非同期HTTP・直接Queue

制御用Storageの接続文字列、またはManaged Identity設定が揃うと有効になります。

- `POST /api/jobs?filename=sample.xlsx&format=png`：ファイルを登録してジョブIDを取得
- `GET /api/jobs/{jobId}`：状態
- `GET /api/jobs/{jobId}/result` または `/archive`：完成結果のZIP
- `GET /api/jobs/{jobId}/report`：report JSON
- `GET /api/jobs/{jobId}/files/pages/page-001.png`：PNG
- `GET /api/jobs/{jobId}/files/document.pdf`：PDF

描画オプションは同期HTTPと共通です。ワーカーは `excel2img-jobs` Queueを処理し、
結果は個別Blobに保存します。外部システムからの依頼形式は [直接Queue](direct-queue.md) を参照してください。
成果物取得はジョブの所有manifestに含まれるパスだけを許可します。

## 設定

| 環境変数 | 既定 | 意味 |
| --- | --- | --- |
| `CONVERSION_MAX_INPUT_BYTES` | 20971520 | 入力20MiB |
| `CONVERSION_MAX_OUTPUT_BYTES` | 104857600 | 成果物合計と返却ZIPの上限 |
| `CONVERSION_MAX_PAGES` | 50 | 出力ページ上限。0はページ数上限なし |
| `CONVERSION_MAX_IMAGE_PIXELS` | 20000000 | 1出力画像の画素数上限 |
| `CONVERSION_RENDER_MAX_WIDTH_PIXELS` | 2048 | ページの最大横幅 |
| `CONVERSION_RENDER_MAX_HEIGHT_PIXELS` | 2800 | ページの最大縦幅 |
| `CONVERSION_RENDER_MIN_TEXT_PIXELS` | 12 | 縮小後に許容する最小文字サイズ |
| `CONVERSION_RENDER_PIXELS_PER_POINT` | 2 | 描画解像度。既定144dpi相当 |
| `CONVERSION_RENDER_MAX_CELLS` | 200000 | 描画する範囲の合計セル数上限 |
| `CONVERSION_RENDER_MAX_SOURCE_IMAGE_PIXELS` | 20000000 | 埋め込み画像を展開する前の画素数上限 |
| `CONVERSION_STORAGE_CONNECTION_STRING` | 未設定 | Blob/Queueを使う非同期処理の接続設定 |

読みやすさの閾値は幾何学的な基準です。画像内の小さい文字やLLM側の画像縮小後の
読み取り精度までは保証しません。上限を上げる場合は、メモリ・一時ディスク・時間も実測してください。
同期HTTPでは入力・応答ZIPをメモリに保持します。全ページのビットマップを同時には保持しません。

## 配置と検証

```sh
./mvnw clean package
```

配布物は `target/azure-functions/excel2img-local/` に生成されます。
Java 21 / Linuxの独立したFunction Appへ配置してください。Flex従量課金ではJavaの実行環境を使用し、
Office、LibreOffice、Chromiumの導入は不要です。
Azure上の設定・Managed Identity・Private Endpoint・保持管理の手順は
[共通の開発・配置ガイド](../../../docs/development.md) を参照してください。

`third-party/` に依存JARと原文の対応・ハッシュを保持し、`scripts/PackageLicenses.java` が
ビルド時に検証します。アプリJARと配布ディレクトリに `LICENSE`、`NOTICE.md`、
`THIRD-PARTY-NOTICES.txt`、原文群を同梱します。単独で配布するときも削除しないでください。
