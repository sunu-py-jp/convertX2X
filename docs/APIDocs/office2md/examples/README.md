# Officeの実変換例

[HTMLの実例ページ](../examples.html)で、実際の出力とスクリーンショットを確認できます。

| ケース | 入力 | 実際の結果 |
| --- | --- | --- |
| `excel-complex` | 13シート、罫線表、保存済み数式、図形・画像、未対応要素 | 8シート、7表、32アセット、4接続（1確定・3不明）、20警告 |
| `word-complex` | 見出し、変更履歴、罫線なし結合表、脚注、回転図形、画像 | 1文書、2アセット、1警告 |
| `word-rag-flow` | 購入申請・修正再申請のDrawingMLグループ、接続先なしの矢印、前後の段落・表 | 1文書、1プレビュー、5接続（4確定・1不明）、5警告 |
| `powerpoint-complex` | 4表示＋1非表示スライド、表、グループ図形、接続先不明の線、画像 | 4スライド、2プレビュー、2警告 |
| `powerpoint-rag-flow` | 3スライド、購入申請の分岐・差戻し、グループ内外の接続、双方向・始点矢印、接続先不明の線 | 3スライド、3プレビュー、14接続（13確定・1不明）、1警告 |

各フォルダの `input.*` は入力、`result.zip` は実際のHTTP応答をPlaygroundからダウンロードしたZIP、`output/` はそのZIPを変更せず展開したものです。`run.json` に入力・成果物・スクリーンショットのSHA-256、HTTP結果、環境、計測範囲を保存しています。

入力はリポジトリ内のオリジナル資料で、アプリと同じMITライセンスです。元の生成コードは [FullFeatureWorkbook.java](../../../../functions/office2md/examples/FullFeatureWorkbook.java)・[FullFeatureDrawings.java](../../../../functions/office2md/examples/FullFeatureDrawings.java)・[WordSample.java](../../../../functions/office2md/examples/WordSample.java)・[WordRagSample.java](../../../../functions/office2md/examples/WordRagSample.java)・[PowerPointSample.java](../../../../functions/office2md/examples/PowerPointSample.java)・[RagFlowSample.java](../../../../functions/office2md/examples/RagFlowSample.java) です。

Excelの入力コピーに限り、古い挙動を説明していた3個のセルラベルを、現在の「明示グループを確認用に合成し、重なりだけでは統合しない」動作に合わせています。セル値のラベル以外の数式・キャッシュ・画像・図形・書式は変更していません。[prepare_inputs.py](prepare_inputs.py) がそのコピー処理を再現します。

## 再実行

リポジトリのルートで入力を用意し、別のターミナルでOfficeのローカルホストを起動します。Java 21、Maven Wrapperが利用する環境、Azure Functions Core Tools v4が必要です。

```sh
python3 docs/APIDocs/office2md/examples/prepare_inputs.py
CONVERSION_STORAGE_CONNECTION_STRING='' python3 functions/office2md/scripts/run_local.py --port 7072
```

Playwrightが利用可能なNode.js環境で、次を実行します。`capture.cjs` は上の5ファイルを順番に実APIへ送り、成果物・スクリーンショット・実行記録を更新します。既存の記録を上書きするため、変更内容を確認してください。

```sh
node docs/APIDocs/office2md/examples/capture.cjs
python3 docs/APIDocs/office2md/examples/verify.py
```

1ケースだけ更新する場合は `OFFICE_EXAMPLE_CASE` に表のケース名を指定します。不明な名前は実行せずエラーにします。例えばPowerPointだけを再変換する場合は次のとおりです。

```sh
OFFICE_EXAMPLE_CASE=powerpoint-complex node docs/APIDocs/office2md/examples/capture.cjs
python3 docs/APIDocs/office2md/examples/verify.py
```

`prepare_inputs.py` は包括例3件をコピーします。フロー用の2件は同梱済みです。再生成は [Wordの手順](word-rag-flow/README.md)・[PowerPointの手順](powerpoint-rag-flow/README.md) を参照してください。

独立した場所へPlaywrightを準備する場合の例です。

```sh
npm install --prefix /tmp/office2md-docs-tools playwright
/tmp/office2md-docs-tools/node_modules/.bin/playwright install chromium
NODE_PATH=/tmp/office2md-docs-tools/node_modules \
  node docs/APIDocs/office2md/examples/capture.cjs
```

既存のGoogle Chromeを使う場合は `PLAYWRIGHT_CHANNEL=chrome` を指定できます。ホストURLの変更は `OFFICE_EXAMPLE_BASE=http://localhost:7072` です。誤ってクラウドへ投入しないよう、スクリプトはローカルホストだけを受け付けます。

## 記録の範囲

- 全例を2026-09-22に、macOS arm64・Java 21・ローカルFunctionsで実行しました。PowerPointは図中の文字と保存済み接続を本文に出し、スライド全体の画像を補助プレビューとする出力です。`run.json` の時間は送信操作から応答ZIPの展開・プレビュー生成までを含みます。コールドスタートやAzureの性能を測るものではありません。
- この記録は同期HTTPの結果です。Queue・別Storage・Azureデプロイの検証記録ではありません。
- 通常の画面は `screenshots/overview.png` と `markdown-source.png`。セクション別画像はプレビューのスクロールと画像高さを広げ、ほかのセクションを隠して撮影しています。元の文章・画像は編集していません。
- Excelの `tables-detail.png` / `group-detail.png` は先頭790 / 1000 CSSピクセルを撮影し、同じセクションの全体画像も保存しています。警告の画像は警告リストを展開して撮影しています。
- PowerPointは図形の文字を通常のMarkdown本文に残し、座標・個別図形は `report.json` の `nodes`、接続は `edges` に保存します。JSONのaltはスライド全体のプレビュー範囲です。本文では元の配置を再現せず、必要に応じて補助画像を確認します。Word・Excelも同じ本文・JSON形式です。Wordは段落位置に描画範囲のプレビュー、Excelはセル付近に明示グループ・解決した接続のプレビューを置きます。Wordはページ組版を行わず、未知の座標はnull、保存配置はplacementに残します。省略・近似は各 `report.json` の警告を参照してください。
- `capture.cjs` は画像の読み込み完了、ブラウザーエラーなし、外部ブラウザー通信なし、画面ソースとZIP内Markdown/reportの一致を確認します。サーバーのネットワーク全体を監視するツールではありません。

入力をOffice等で開いて再保存すると、数式キャッシュや書式が変わる場合があります。記録を再現する際は、このフォルダの入力または `prepare_inputs.py` で作り直した入力をそのまま送信してください。
