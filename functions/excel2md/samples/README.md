# 実変換用の包括サンプル

[full-feature.xlsx](full-feature.xlsx) は、文章・表・表示値・画像・基本図形をまとめて試すオリジナルExcelです。編集可能なセル、図形、グループ、実データ付きグラフを含み、画像素材も生成プログラムが作成します。

出力対象の8シートに加え、非表示・VeryHidden・空・取消線のみ・空の罫線のみの5シートを入れています。上限超過、暗号化、マクロなど変換を中断する異常系は、成功ケースを確認するこの1ファイルには混在させません。

| シート | 確認する機能 |
| --- | --- |
| 01_文章と書式 | 日本語、段落、複数セル、全体・部分太字、通常書式の明示、全体・部分取消線、取り消したリンクと数式の除外、HTTP/HTTPS/mailto、太字リンク、空の表示名、不正・内部・ファイルリンクの文字保持、Markdown/HTMLのエスケープ、改行、結合文章、非表示行・列 |
| 02_罫線の表 | 閉じた罫線、空のヘッダー追加、元の先頭行、空行・空列、表内の書式・リンク・改行・縦棒、横並びの別表、片側罫線、非表示行、結合見出し、Excelテーブルのヘッダー、スタイルだけの表、囲み・不完全な罫線・下線、空の表、条件付き書式 |
| 03_表示値と数式 | 先頭ゼロ、桁区切り、通貨、割合、日付、時刻、負数、指数、真偽値、エラー、ゼロと空欄、数式キャッシュ、キャッシュなし、固定・動的HYPERLINK、IMAGEの代替表示、数値の取消線 |
| 04_画像 | PNG/JPEG原本、重複データ共有、罫線表の直後への配置、非表示画像と非表示アンカーの除外 |
| 05_基本図形 | 長方形、角丸、楕円、上下左右矢印、直線、コネクター、テキストボックス、日本語Sans/Serifの通常・太字、部分取消線、リンク、回転、反転、テーマ色 |
| 06_グループと接続 | 明示的・入れ子グループの変形、接続ID、重なった図形、画像と図形の合成 |
| 07_未対応と加工 | 実グラフと星形の未対応表示、グラデーション・縦書きの簡略化、加工画像の原本抽出、BMPの添付 |
| 08_画像だけ | セルに文章がなくても画像のあるシートを残す |
| 末尾の5シート | 非表示・VeryHiddenと、出力内容のないシートを除外 |

`03_表示値と数式!C17` は意図的に `1+2` の数式と保存キャッシュ `999` を持ちます。生成したファイルをそのまま送信すると、再計算せず `999` を出力します。Excelなどで開いて再保存すると再計算される可能性があるため、厳密な検証には再生成したファイルを使います。

## 再生成

Java 21を用意し、モジュールのディレクトリ `functions/excel2md` で実行します。同梱フォントを利用します。

```sh
./mvnw package
mkdir -p target/sample-tools
javac -encoding UTF-8 \
  -cp 'target/azure-functions/excel2md-local/*:target/azure-functions/excel2md-local/lib/*' \
  -d target/sample-tools examples/FullFeatureWorkbook.java examples/FullFeatureDrawings.java
java -Djava.awt.headless=true \
  -cp 'target/sample-tools:target/azure-functions/excel2md-local/*:target/azure-functions/excel2md-local/lib/*' \
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
