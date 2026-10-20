# 横長・画像付きの追加サンプル

架空のExcelを3種類作り、稼働中の `excel2img` の同期HTTP APIでPNGとPDFへ変換します。
生成結果は `outputs/<入力名>/`、リクエストと結果の記録は `run.json` に保存します。

| 入力 | 確認する内容 |
| --- | --- |
| `inputs/wide-table.xlsx` | 横長の表を縮小して描画する例と、広すぎて見送る例 |
| `inputs/embedded-images.xlsx` | 画面画像・模式図・グラフ画像とセルの位置関係 |
| `inputs/wide-with-images.xlsx` | 横長の表と貼り付け画像を同じ範囲で描画する例 |

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

横長の入力には、見送りを確認するためのシートも含まれます。
デフォルトの全シート変換では描画できるシートを返し、レポートに見送り理由を残します。
広すぎるシートだけを指定したリクエストでは、422と範囲別の理由が返ることも確認します。

計測時間はローカル環境でのHTTP往復時間です。Azure上での性能保証ではありません。
