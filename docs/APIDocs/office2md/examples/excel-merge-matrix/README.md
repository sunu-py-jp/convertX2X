# Excelの結合セル・色付きヘッダー検証例

[編集可能な入力Excel](input.xlsx)には、罫線表のヘッダー行数、直接塗り、結合範囲と枠の欠けたマトリックスを変えた24シートが入っています。[入力セルの一覧](cases.json)には、各セルの実際の値・直接塗り・上下左右の直接罫線・結合範囲・非表示状態をワークブックから抽出して記録しています。Excelのシート名はケースIDで始まります。

| ID | 入力パターン |
| --- | --- |
| C01–C03 | 結合なし、色付きヘッダーが1・2・3行 |
| C04–C07 | 全行無色、先頭無色、途中で色が途切れる、先頭の1セルだけ色付き |
| C08–C11 | 単行・複数行ヘッダーだけの横結合、全幅横結合、ヘッダーの縦横結合 |
| C12–C13 | ヘッダーと明細の全行で同じ幅の横結合、1行・2行ヘッダー |
| C14–C16 | 明細だけの横・縦結合、ヘッダー行間で位置のずれた横結合 |
| C17–C20 | 全行色付き、無色の同幅結合、非表示の分割行、隣接する2組の同幅結合 |
| C21–C22 | 左上だけ無罫線のマトリックス、通常表の右に接続したマトリックス |
| C23–C24 | 複数の無罫線注記と、幅の異なる上段の結合見出し・縦横結合の3段見出し |

色付きの判定対象はセルへの直接塗りです。先頭行は1セルでも直接塗りがあれば見出しにし、続く行は表の表示対象セル（縦結合の続きは除く）が全て直接塗りのときだけ見出しに追加します。部分的に塗られた行と後続行は明細です。条件付き書式やExcelテーブルの見た目上の色は、この入力例では使っていません。全行を塗ったC17は、全行がヘッダーになる境界例です。

C21ではA1が無罫線の空欄でも、罫線付きの「1月」「2月」を1段見出しとして出します。C22はA:Cの通常表とD:Fのマトリックスを一つの表として扱い、色付きの先頭行をヘッダーにします。A1・D1の各セルには四辺とも直接罫線がなく、該当する空欄セルの診断は `TABLE_OPEN_TOP_CELL` です。

C23・C24のA1・B1には文字がありますが罫線がないため、表の値やヘッダーに混ぜず、表直前の注記として出します（`TABLE_OPEN_TOP_NOTE`）。C23はC1:E1の3列結合、C24はC1:D1の2列結合です。幅は固定せず、入力の結合範囲に従います。C24のE1・F1は無罫線の空欄ですが、明示的な上段の結合見出しと閉じた下段格子から同じ表の右端として扱えます。C24ではA2:A3・B2:B3の縦結合と、C3:D3・E3:F3の横結合も合わせ、3段の見出しをMarkdownの1行へ列ごとに展開します。単独の右端欠けや数式・数値を含む欠けは推測しません。

入力を再生成する場合は、リポジトリのルートからJava 21で以下を実行します。先にOffice2MDモジュールをビルドして依存JARを配置してください。生成プログラムは [ExcelMergeMatrix.java](../../../../../functions/office2md/examples/ExcelMergeMatrix.java) です。

```sh
mkdir -p functions/office2md/target/sample-tools
javac -encoding UTF-8 \
  -cp 'functions/office2md/target/azure-functions/office2md-local/lib/*' \
  -d functions/office2md/target/sample-tools \
  functions/office2md/examples/ExcelMergeMatrix.java
java -cp 'functions/office2md/target/sample-tools:functions/office2md/target/azure-functions/office2md-local/lib/*' \
  ExcelMergeMatrix docs/APIDocs/office2md/examples/excel-merge-matrix
```

`input.xlsx` と `cases.json` は再生成時に同一バイト列になります。変換後のMarkdownや`report.json`は、この入力ファイルを実際にOffice2MDへ送って得た結果です。

別のターミナルでOffice2MDのローカルFunctionsを7074番ポートに起動してから、実際のHTTP応答を保存し、[比較ページ](../../merge-cases.html)を更新します。

```sh
python3 functions/office2md/scripts/run_local.py --skip-build --port 7074
```

```sh
python3 docs/APIDocs/office2md/examples/excel-merge-matrix/capture.py --base http://localhost:7074
python3 docs/APIDocs/office2md/examples/excel-merge-matrix/render.py
```

`capture.py` はローカルホスト以外への送信を拒否します。`result.zip` をそのまま保存し、`output/` に展開した結果と入力・成果物のハッシュを `run.json` に記録します。

## ケース別の画像とMarkdown

[ケース別一覧](pairs/index.md)では、C01〜C24の各シートをスクリーンショットと変換後Markdownの組として参照できます。画像は入力ブックのセル・直接塗り・直接罫線・結合を[比較ページ](../../merge-cases.html)でHTML表示した部分を撮影したもので、Excelアプリの画面ではありません。`pairs/C01/document.md` のような各ファイルは、実変換の `output/document.md` から該当シートの文字列をそのまま切り出したものです。

追加したC23・C24の入力セル表をChromeで撮影するには、`capture_screenshots.py` を使います。macOS以外では `--chrome` にChrome実行ファイルのパスを指定してください。画像は `cases.json` に記録した実入力セルからHTMLを組み立て、ブラウザーで描画したものです。

```sh
python3 docs/APIDocs/office2md/examples/excel-merge-matrix/capture_screenshots.py C23 C24
```

画像の撮影後に次のコマンドを実行すると、24組のファイル、対応関係と変換レポートの情報を持つ [manifest.json](pairs/manifest.json)、配布用の [pairs.zip](pairs.zip) を更新できます。画像が不足している間はMarkdownと一覧だけを更新し、ZIPは作りません。

```sh
python3 docs/APIDocs/office2md/examples/excel-merge-matrix/build_pairs.py
python3 docs/APIDocs/office2md/examples/excel-merge-matrix/render.py
python3 docs/APIDocs/office2md/examples/excel-merge-matrix/verify.py
```
