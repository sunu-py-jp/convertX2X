# 実変換用のサンプル

Officeの各形式を編集可能な元ファイルで試せます。すべてこのリポジトリの生成プログラムによるオリジナル資料です。

図形は各形式とも文字と確認できた接続関係をMarkdown本文に残し、図形・接続のJSONと参考画像を添えます。Excelは明示グループまたは保存済み接続でつながる図、Wordは描画オブジェクト内のグループ・キャンバス、PowerPointはスライドを画像の単位にします。見た目の近さから接続を推測しません。

| サンプル | 確認する内容 |
| --- | --- |
| [office-sample.docx](office-sample.docx) | H1/H2、太字・リンク、取消線、最終変更履歴、罫線なし表・結合、3からの番号、脚注、日本語の回転図形、埋め込み画像 |
| [office-sample.pptx](office-sample.pptx) | 4表示＋1非表示スライド、タイトル・本文、番号、罫線なし結合表、図形内文字・座標・取消線・リンク、埋め込み画像 |
| [PowerPointのRAG向け業務フロー](../../../docs/APIDocs/office2md/examples/powerpoint-rag-flow/input.pptx) | 分岐・差戻し・グループ・双方向・始点矢印・接続先不明の線。[実変換結果と再生成手順](../../../docs/APIDocs/office2md/examples/powerpoint-rag-flow/README.md) |
| [WordのRAG向け業務フロー](../../../docs/APIDocs/office2md/examples/word-rag-flow/input.docx) | グループ・描画キャンバス内の図形文字と保存済み接続、本文位置、まとめた参考画像。[生成プログラム](../examples/WordRagSample.java) |
| [full-feature.xlsx](full-feature.xlsx) | Excelの包括ケース。罫線表を起点にした左右の値・横並び表の取り込みも含む。詳細は以下 |
| [shape-text.xlsx](shape-text.xlsx) | Excel図形内の文字配置・書式。[比較項目](shape-text.md) |

## Word・PowerPointを試す

`functions/office2md` で `python3 scripts/run_local.py` を起動し、7072番のPlaygroundへファイルを渡します。HTTP APIを直接使う場合も入力拡張子を `filename` に指定します。

```sh
curl --fail-with-body -H 'Content-Type: application/octet-stream' \
  --data-binary @samples/office-sample.docx \
  'http://localhost:7072/api/convert?filename=office-sample.docx' -o target/word-result.zip
curl --fail-with-body -H 'Content-Type: application/octet-stream' \
  --data-binary @samples/office-sample.pptx \
  'http://localhost:7072/api/convert?filename=office-sample.pptx' -o target/pptx-result.zip
```

実際のFunctions・Azuriteへ同じ入力を送って、同期・HTTP受付→Queue・直接Queueの成果物を確認する場合は、順に実行します。

```sh
python3 scripts/test_async_e2e.py --input samples/office-sample.docx --result-dir target/word-queue
python3 scripts/test_async_e2e.py --input samples/office-sample.pptx --result-dir target/pptx-queue
```

サンプルの再生成と実ブラウザーテストの準備は次の通りです。生成物の `sample.docx` / `sample.pptx` はブラウザーテスト用です。保存場所を引数に指定すれば、同梱サンプルも再生成できます。

```sh
./mvnw package
mkdir -p target/sample-tools
javac -encoding UTF-8 -cp 'target/azure-functions/office2md-local/lib/*' \
  -d target/sample-tools examples/WordSample.java examples/PowerPointSample.java
java -Djava.awt.headless=true -cp 'target/sample-tools:target/azure-functions/office2md-local/lib/*' WordSample
java -Djava.awt.headless=true -cp 'target/sample-tools:target/azure-functions/office2md-local/lib/*' PowerPointSample
node scripts/test_playground_browser.cjs --base http://localhost:7072 --fixtures target/fixtures --out target/office-browser
```

WordのRAG向け業務フローは、図の前後の本文、4つの表示ノード、4つの解決済み接続と1つの接続先不明の線、通常表を含みます。次のコマンドで再生成できます。

```sh
javac -encoding UTF-8 -cp 'target/azure-functions/office2md-local/lib/*' \
  -d target/sample-tools examples/WordRagSample.java
java -Djava.awt.headless=true -cp 'target/sample-tools:target/azure-functions/office2md-local/lib/*' \
  WordRagSample target/fixtures/word-rag-flow.docx
```

## Excelの包括サンプル

[full-feature.xlsx](full-feature.xlsx) は、文章・表・表示値・画像・基本図形をまとめて試すオリジナルExcelです。編集可能なセル、図形、グループ、実データ付きグラフを含み、画像素材も生成プログラムが作成します。

出力対象の8シートに加え、非表示・VeryHidden・空・取消線のみ・空の罫線のみの5シートを入れています。上限超過、暗号化、マクロなど変換を中断する異常系は、成功ケースを確認するこの1ファイルには混在させません。

| シート | 確認する機能 |
| --- | --- |
| 01_文章と書式 | 日本語、段落、複数セル、全体・部分太字、通常書式の明示、全体・部分取消線、取り消したリンクと数式の除外、HTTP/HTTPS/mailto、太字リンク、空の表示名、不正・内部・ファイルリンクの文字保持、Markdown/HTMLのエスケープ、改行、結合文章、非表示行・列 |
| 02_罫線の表 | 閉じた罫線、空のヘッダー追加、元の先頭行、空行・空列、表内の書式・リンク・改行・縦棒、横並びの別表、同じ行範囲の左右取り込み、片側罫線、非表示行、結合見出し、Excelテーブルのヘッダー、スタイルだけの表、囲み・不完全な罫線・下線、空の表、条件付き書式 |
| 03_表示値と数式 | 先頭ゼロ、桁区切り、通貨、割合、日付、時刻、負数、指数、真偽値、エラー、ゼロと空欄、数式キャッシュ、キャッシュなし、固定・動的HYPERLINK、IMAGEの代替表示、数値の取消線 |
| 04_画像 | PNG/JPEGの参考画像への描画、重複データ共有、罫線表の直後への配置、非表示画像と非表示アンカーの除外 |
| 05_基本図形 | 長方形、角丸、楕円、上下左右矢印、直線、コネクター、テキストボックス、日本語Sans/Serifの通常・太字、部分取消線、リンク、回転、反転、テーマ色 |
| 06_グループと接続 | 明示的・入れ子グループをまとめた参考画像、変形後座標、図形文字・保存済み接続の本文とJSON、接続情報がない線の未解決表示。重なりだけでは図をまとめない |
| 07_未対応と加工 | 実グラフと星形の未対応表示、グラデーション・縦書きの簡略化、画像の回転・反転、BMPの添付 |
| 08_画像だけ | セルに文章がなくても画像のあるシートを残す |
| 末尾の5シート | 非表示・VeryHiddenと、出力内容のないシートを除外 |

`03_表示値と数式!C17` は意図的に `1+2` の数式と保存キャッシュ `999` を持ちます。生成したファイルをそのまま送信すると、再計算せず `999` を出力します。Excelなどで開いて再保存すると再計算される可能性があるため、厳密な検証には再生成したファイルを使います。

## 再生成

Java 21を用意し、モジュールのディレクトリ `functions/office2md` で実行します。同梱フォントを利用します。

```sh
./mvnw package
mkdir -p target/sample-tools
javac -encoding UTF-8 \
  -cp 'target/azure-functions/office2md-local/*:target/azure-functions/office2md-local/lib/*' \
  -d target/sample-tools examples/FullFeatureWorkbook.java examples/FullFeatureDrawings.java
java -Djava.awt.headless=true \
  -cp 'target/sample-tools:target/azure-functions/office2md-local/*:target/azure-functions/office2md-local/lib/*' \
  FullFeatureWorkbook samples/full-feature.xlsx
```

## 実HTTP変換と検証

別のターミナルで `python3 scripts/run_local.py --skip-build --port 7072` を起動してから実行します。出力先は新しいディレクトリを指定してください。

```sh
python3 scripts/verify_full_feature.py \
  --input samples/full-feature.xlsx \
  --expect samples/full-feature.expected.json \
  --base http://127.0.0.1:7072 \
  --out target/full-feature-http
```

実際のHTTPレスポンスの `result.zip` と、その中の `document.md`、`report.json`、`images/` を保存します。SHA-256、実測時間、シート順、表数、必要な文字と除外対象、警告、画像参照・サイズを `verification.json` と `README.md` に記録します。

## 同じファイルをQueue経由で処理

Core ToolsとAzuriteが必要です。試験専用のローカルホストとAzuriteを起動し、終了時に停止します。既存のAzure Storageには接続しません。

```sh
python3 scripts/test_async_e2e.py \
  --input samples/full-feature.xlsx \
  --result-dir target/full-feature-queue
```

同期HTTP、HTTPからのジョブ登録、別Storageアカウントを指定したQueueへの直接投入を実行し、同じMarkdownになることと成果物の整合性を検証します。
