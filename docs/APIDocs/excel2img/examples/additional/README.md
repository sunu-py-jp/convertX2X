# 横長・画像・文字配置の追加サンプル

架空のExcelを3種類作り、稼働中の `excel2img` の同期HTTP APIでPNGとPDFへ変換します。
生成結果は `outputs/<入力名>/`、リクエストと結果の記録は `run.json` に保存します。

| 入力 | 確認する内容 |
| --- | --- |
| `inputs/wide-table.xlsx` | 24列を1ページに縮小する例と、48列を読みやすい倍率で横分割する例 |
| `inputs/embedded-images.xlsx` | 画像3枚、空セルへの文字のはみ出し、結合セルの折り返し・中央揃え・縦書きの比較 |
| `inputs/wide-with-images.xlsx` | 27列の表と画像2枚を縮小する例、52列の表と画像1枚を横分割する例 |

グラフはPNGとして貼り付けたものです。Excelのネイティブグラフの描画を検証するサンプルではありません。
画像は全てサンプル生成時に作成し、実在の業務データや外部画像は使用しません。

## 再実行

JDK 21で `functions/excel2img/` から実行します。生成はPOI、変換はHTTP経由です。

```sh
./mvnw package
java -Djava.awt.headless=true \
  --class-path "target/classes:$(cat target/license-runtime-classpath.txt)" \
  scripts/CreateAdditionalRenderSamples.java \
  ../../docs/APIDocs/excel2img/examples/additional/inputs
```

別ターミナルでFunctionを起動します。

```sh
python3 scripts/run_local.py --skip-build --port 7076
```

変換スクリプトを実行します。

```sh
python3 scripts/run_additional_examples.py
```

接続先や入力先を変える場合は `--base-url http://localhost:7076/api` と
`--fixture-folder <入力フォルダー>` を指定できます。既定の保存先は入力フォルダーと同じ階層の
`outputs/` です。`--output-folder <保存先>` も指定できます。認証が必要な接続先では、
環境変数 `EXCEL2IMG_FUNCTION_KEY` の値をヘッダーへ渡します。記録にキーは保存しません。

3つの入力の全シートをPNG・PDFへ変換し、さらに `48列・横分割` と `52列・横分割` を
それぞれ単独指定してPNG・PDFへ変換します。計10回のリクエストで200応答を確認し、
単独指定した横長シートでは複数の横方向ページと `HORIZONTAL_PAGINATION` を検証します。
文字サイズの下限を保つ倍率まで縮小し、その倍率で横幅を超える部分を分割します。
ページ順は上段の左から右、続いて次の段の左から右です。

全シートの結果は `outputs/<入力名>/png/`・`pdf/`、横長シートだけの結果は
`outputs/<入力名>/wide-sheet/png/`・`pdf/` に保存します。各結果のZIPも残します。
PNGはreportに記載された全ページを保存し、連番、寸法、サイズ、ハッシュを照合します。
ZIP内の保存可能な名前を制限し、フォルダー外のパスやシンボリックリンクを拒否します。
`run.json` には入力と画像のメタデータ、リクエスト条件、時間、各ページの行列範囲、
倍率、結果のパスを記録します。

## 文字配置の比較

`embedded-images.xlsx` の `文字のはみ出し比較` シートには、同じ「プログラム名」を3通り配置します。

| セル | 設定と比較点 |
| --- | --- |
| C8、D8、E8 | C8は折り返しなし。D8・E8は同じ背景色の空セルで、文字が隣へはみ出す |
| C13:E13 | 同じ幅の範囲を結合した比較用ラベル |
| C18、D18 | D18に「隣の値」を置き、C18のはみ出しを止める |

`縦書きと折返し中央` シートでは、上段に「ウィンドウ」と「検証結果」の結合見出しを置き、
「起動」「表示内容」「表示順」「検索機能」「PG検証」「カーソル移動順」を並べます。

| 範囲 | 設定 |
| --- | --- |
| A7:F19の各列 | 縦長結合、縦書き（rotation 255）、左右中央・上揃え |
| H7:M19の各列 | 縦長結合、約1文字幅、折り返し、上下左右中央揃え |
| O7:T19の各列 | 縦長結合、約2文字幅、折り返し、上下左右中央揃え |

縦書きと、横書きを狭いセルで折り返す設定を別々に比較できます。

計測時間はローカル環境でのHTTP往復時間です。Azure上での性能保証ではありません。
