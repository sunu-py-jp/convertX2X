# Officeの実変換例

[HTMLの実例ページ](../examples.html)で、実際の出力とスクリーンショットを確認できます。

| ケース | 入力 | 実際の結果 |
| --- | --- | --- |
| `excel-complex` | 13シート、罫線表と名前付き範囲、保存済み数式、図形・画像、接続IDなしの線端接触、未対応要素 | 8シート、6表、33アセット、6接続（3確定・3不明）、20警告 |
| `word-complex` | 罫線なし結合表と3列の判定表、複数段落セル、変更履歴、2脚注、回転図形・楕円・画像 | 1文書、3アセット、2警告。別途3ページの実PDFと1ページ目画像 |
| `word-rag-flow` | 購入申請・修正再申請のDrawingMLグループ、接続先なしの矢印、前後の段落 | 1文書、1プレビュー、5接続（4確定・1不明）、5警告 |
| `powerpoint-complex` | 6表示＋1非表示スライド、2種類の結合表、レビュー経路の図形・接続線、画像 | 6スライド、3プレビュー、3警告 |
| `powerpoint-rag-flow` | 3スライド、購入申請の分岐・差戻し、グループ内外の接続、双方向・始点矢印、接続先不明の線 | 3スライド、3プレビュー、14接続（13確定・1不明）、1警告 |
| [`excel-merge-matrix`](../merge-cases.html) | 色付き1～3行ヘッダー、無色、部分・全幅の横結合、縦結合、非表示行、無罫線の角を持つ多段マトリックスなど24シート | 24シートの入力Excelと実変換Markdownをケース別に比較 |

既存の5つの包括例では、各フォルダの `input.*` は入力、`result.zip` は実際のHTTP応答をPlaygroundからダウンロードしたZIP、`output/` はそのZIPを変更せず展開したものです。`run.json` に入力・成果物・スクリーンショットのSHA-256、HTTP結果、環境、計測範囲を保存しています。

`excel-merge-matrix` は独立した検証例です。入力の生成とHTTPキャプチャ、比較ページの再作成は[専用README](excel-merge-matrix/README.md)を参照してください。`run.json` にはHTTP応答と入力・成果物のハッシュを保存し、ブラウザースクリーンショットは含めていません。

入力はリポジトリ内のオリジナル資料で、アプリと同じMITライセンスです。元の生成コードは [FullFeatureWorkbook.java](../../../../functions/office2md/examples/FullFeatureWorkbook.java)・[FullFeatureDrawings.java](../../../../functions/office2md/examples/FullFeatureDrawings.java)・[WordSample.java](../../../../functions/office2md/examples/WordSample.java)・[WordRagSample.java](../../../../functions/office2md/examples/WordRagSample.java)・[PowerPointSample.java](../../../../functions/office2md/examples/PowerPointSample.java)・[RagFlowSample.java](../../../../functions/office2md/examples/RagFlowSample.java) です。

Excelの「06_グループと接続」には、明示グループ、保存済み接続ID、保存IDのない横・斜めコネクターの線端接触、重なりだけでは統合しない例を収録しています。[prepare_inputs.py](prepare_inputs.py) は同梱サンプルをそのままコピーします。

Excelの検出した罫線表では、先頭から直接塗りつぶしが設定されたセルを1つ以上含む表示行が連続する間をヘッダーにします。無罫線の角を持つ確定済み格子では、最上段の罫線付き・非空セルを塗りなしでも1段のヘッダーとします。左端の無罫線角と横結合見出しなどで構造を確認できる場合だけ次段へ、さらに縦結合の継続と下位の横結合ラベルがあれば後続の段へ延ばします。途中の欠けだけでは次行を見出しと推測しません。無罫線セルに文字があれば表直前の注記へ出します。結合見出しは固定幅にせず、その元範囲の子列へ継承し、縦結合の語は重複させません。複数行の見出しは各列で ` / ` によって連結し、Markdownの1行にします。いずれの条件もなく先頭行に直接セル塗りがなければ空ヘッダーを生成し、元の行はすべて明細として残します。`report.json` の各表の `header` は `fill-color`、`matrix-grid` または `empty-generated`、`headerSourceRows` は採用した元行の1始まりの行番号配列です。全行に直接塗りがある場合はヘッダーのみになり、このサンプルの「04_画像」にもその例があります。明細にしたい行は直接塗りを外す必要があります。Excelテーブル定義、条件付き書式、テーブルスタイルの見た目の色は判定に使いません。

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

拡張したWord・PowerPointの包括例は `WordSample.java`・`PowerPointSample.java` をコンパイルし、`functions/office2md/samples/office-sample.docx`・`office-sample.pptx` を再生成してから `prepare_inputs.py` でコピーします。Wordフロー例は `WordRagSample.java` から `word-rag-flow/input.docx` を直接再生成します。各入力を変更したら、対象ケースの `capture.cjs` を再実行し、変換結果と画面を更新してください。

```sh
mkdir -p functions/office2md/target/sample-tools
javac -encoding UTF-8 -cp 'functions/office2md/target/azure-functions/office2md-local/lib/*' \
  -d functions/office2md/target/sample-tools \
  functions/office2md/examples/WordSample.java \
  functions/office2md/examples/PowerPointSample.java \
  functions/office2md/examples/WordRagSample.java
java -Djava.awt.headless=true \
  -cp 'functions/office2md/target/sample-tools:functions/office2md/target/azure-functions/office2md-local/lib/*' \
  WordSample functions/office2md/samples/office-sample.docx
java -Djava.awt.headless=true \
  -cp 'functions/office2md/target/sample-tools:functions/office2md/target/azure-functions/office2md-local/lib/*' \
  PowerPointSample functions/office2md/samples/office-sample.pptx
java -Djava.awt.headless=true \
  -cp 'functions/office2md/target/sample-tools:functions/office2md/target/azure-functions/office2md-local/lib/*' \
  WordRagSample docs/APIDocs/office2md/examples/word-rag-flow/input.docx
python3 docs/APIDocs/office2md/examples/prepare_inputs.py
```

WordのPDF例は同じ `word-complex/input.docx` を `output=pdf` 付きの実HTTP APIへ送った応答です。[PDF](word-complex/pdf/document.pdf)と[1ページ目画像](word-complex/pdf/page-01.png)、[実行記録](word-complex/pdf/run.json)を保存しています。ローカルホスト起動後に次のコマンドで再取得できます。
1ページ目の画像作成とページ数の検査には `pdftoppm` と `pdfinfo` を使います。

```sh
python3 docs/APIDocs/office2md/examples/capture_pdf.py --base http://localhost:7072
python3 docs/APIDocs/office2md/examples/render_word_powerpoint.py
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

- Excel・Word包括・Wordフロー・PowerPoint包括例は2026-10-04、PowerPointフロー例は2026-09-23に、macOS arm64・Java 21・ローカルFunctionsで実行しました。成果物の `report.json` は `specVersion: 1` です。`run.json` の時間は送信操作から応答ZIPの展開・プレビュー生成までを含みます。コールドスタートやAzureの性能を測るものではありません。
- この記録は同期HTTPの結果です。Queue・別Storage・Azureデプロイの検証記録ではありません。
- 通常の画面は `screenshots/overview.png` と `markdown-source.png`。セクション別画像はプレビューのスクロールと画像高さを広げ、ほかのセクションを隠して撮影しています。元の文章・画像は編集していません。
- Excelの `tables-detail.png` / `group-detail.png` は先頭790 / 1000 CSSピクセルを撮影し、同じセクションの全体画像も保存しています。警告の画像は警告リストを展開して撮影しています。
- PowerPointは図形の文字を通常のMarkdown本文に残し、個別図形・接続は `report.json` の `nodes / edges` に保存します。スライドを9分割した位置は `positionOnSlide` にあり、接続先が保存されていない片方向の直線矢印は、判定できる見た目の方向を `arrowheadPointsToward` に残します。3枚目の `shape-9` は `left-middle` にある右向き（`right-middle`）の矢印ですが、接続先は不明です。元の配置は本文では再現せず、必要に応じて補助画像を確認します。参考画像のaltはスライド全体のプレビュー範囲です。Word・Excelも図形の文字と保存済み接続を本文・JSONに残しますが、スライド専用の位置・方向フィールドは付けません。Wordは段落位置に描画範囲のプレビュー、Excelはセル付近に明示グループ・解決した接続のプレビューを置きます。Wordはページ組版を行わず、未知の座標はnull、保存配置はplacementに残します。省略・近似は各 `report.json` の警告を参照してください。
- `capture.cjs` は画像の読み込み完了、ブラウザーエラーなし、外部ブラウザー通信なし、画面ソースとZIP内Markdown/reportの一致を確認します。サーバーのネットワーク全体を監視するツールではありません。

入力をOffice等で開いて再保存すると、数式キャッシュや書式が変わる場合があります。記録を再現する際は、このフォルダの入力または `prepare_inputs.py` で作り直した入力をそのまま送信してください。
