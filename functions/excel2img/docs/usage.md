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

横方向を一律に縮小したときの文字サイズを検査し、既定12pxを下回る範囲は
`WIDTH_UNREADABLE` として見送ります。横に分割して離れた列を別ページにする処理はありません。
縦方向はページに分割します。長大な行・結合などがページをまたぐときは警告を残します。
印刷範囲を指定していても可読性や資源上限の検査は行います。

複雑なグラフ、SmartArt、未対応の図形・画像形式などは完全再現できません。
省略・近似・見送りは `report.json` の警告と範囲情報を確認してください。
OCRやLLMへの送信は行いません。

## ローカル実行

JDK 21、Python 3.10以上、Azure Functions Core Tools v4が必要です。

```sh
python3 scripts/run_local.py
```

<http://localhost:7076/api/playground> でファイルと出力形式を選べます。
ポートを変える場合は `--port 7086`、ビルド済み配布物を使う場合は `--skip-build` を付けます。
設定は `local.settings.example.json` を基に、Git管理対象外の `local.settings.json` へ保存してください。

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
