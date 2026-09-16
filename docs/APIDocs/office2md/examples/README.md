# Officeの実変換例

[HTMLの実例ページ](../examples.html)で、実際の出力とスクリーンショットを確認できます。

| ケース | 入力 | 実際の結果 |
| --- | --- | --- |
| `excel-complex` | 13シート、罫線表、保存済み数式、図形・画像、未対応要素 | 8シート、7表、34アセット、17警告 |
| `word-complex` | 見出し、変更履歴、罫線なし結合表、脚注、回転図形、画像 | 1文書、2アセット、1警告 |
| `powerpoint-complex` | 4表示＋1非表示スライド、表、グループ図形、接続線、画像 | 4スライド、4アセット、1警告 |

各フォルダの `input.*` は入力、`result.zip` は実際のHTTP応答をPlaygroundからダウンロードしたZIP、`output/` はそのZIPを変更せず展開したものです。`run.json` に入力・成果物・スクリーンショットのSHA-256、HTTP結果、環境、計測範囲を保存しています。

入力はリポジトリ内のオリジナル資料で、アプリと同じMITライセンスです。元の生成コードは [FullFeatureWorkbook.java](../../../../functions/office2md/examples/FullFeatureWorkbook.java)・[FullFeatureDrawings.java](../../../../functions/office2md/examples/FullFeatureDrawings.java)・[WordSample.java](../../../../functions/office2md/examples/WordSample.java)・[PowerPointSample.java](../../../../functions/office2md/examples/PowerPointSample.java) です。

Excelの入力コピーに限り、旧仕様の合成処理を説明していた3個のセルラベルを、現在の「図形ごとの出力」に合わせています。セル値のラベル以外の数式・キャッシュ・画像・図形・書式は変更していません。[prepare_inputs.py](prepare_inputs.py) がそのコピー処理を再現します。

## 再実行

リポジトリのルートで入力を用意し、別のターミナルでOfficeのローカルホストを起動します。Java 21、Maven Wrapperが利用する環境、Azure Functions Core Tools v4が必要です。

```sh
python3 docs/APIDocs/office2md/examples/prepare_inputs.py
CONVERSION_STORAGE_CONNECTION_STRING='' python3 functions/office2md/scripts/run_local.py --port 7072
```

Playwrightが利用可能なNode.js環境で、次を実行します。`capture.cjs` は上の3ファイルを順番に実APIへ送り、成果物・スクリーンショット・実行記録を更新します。既存の記録を上書きするため、変更内容を確認してください。

```sh
node docs/APIDocs/office2md/examples/capture.cjs
python3 docs/APIDocs/office2md/examples/verify.py
```

独立した場所へPlaywrightを準備する場合の例です。

```sh
npm install --prefix /tmp/office2md-docs-tools playwright
/tmp/office2md-docs-tools/node_modules/.bin/playwright install chromium
NODE_PATH=/tmp/office2md-docs-tools/node_modules \
  node docs/APIDocs/office2md/examples/capture.cjs
```

既存のGoogle Chromeを使う場合は `PLAYWRIGHT_CHANNEL=chrome` を指定できます。ホストURLの変更は `OFFICE_EXAMPLE_BASE=http://localhost:7072` です。誤ってクラウドへ投入しないよう、スクリプトはローカルホストだけを受け付けます。

## 記録の範囲

- 2026-09-16にmacOS arm64、Java 21、ローカルFunctionsで実行しました。`run.json` の時間は送信操作から応答ZIPの展開・プレビュー生成までを含みます。同一プロセスで順番に実行しており、コールドスタートやAzureの性能を測るものではありません。
- この記録は同期HTTPの結果です。Queue・別Storage・Azureデプロイの検証記録ではありません。
- 通常の画面は `screenshots/overview.png` と `markdown-source.png`。セクション別画像はプレビューのスクロールと画像高さを広げ、ほかのセクションを隠して撮影しています。元の文章・画像は編集していません。
- Excelの `tables-detail.png` / `group-detail.png` は先頭790 / 1000 CSSピクセルを撮影し、同じセクションの全体画像も保存しています。警告の画像は警告リストを展開して撮影しています。
- Markdownは位置関係を説明文に残しますが、元資料の横並び・重なり・ページレイアウトは復元しません。Excelのグラフ描画やWord図形の文字組みなど、今回の省略・近似は各 `report.json` の実際の警告を参照してください。
- `capture.cjs` は画像の読み込み完了、ブラウザーエラーなし、外部ブラウザー通信なし、画面ソースとZIP内Markdown/reportの一致を確認します。サーバーのネットワーク全体を監視するツールではありません。

入力をOffice等で開いて再保存すると、数式キャッシュや書式が変わる場合があります。記録を再現する際は、このフォルダの入力または `prepare_inputs.py` で作り直した入力をそのまま送信してください。
