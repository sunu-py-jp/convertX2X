# office2pdf 実装ガイド

`functions/office2pdf` はOffice文書を正規化PDFへ変換する独立したJava 21
Azure Functionsアプリです。LibreOffice、Microsoft Office、FFmpegなどの
ネイティブプロセスを使わないため、Linuxの従量課金Function Appへ通常のJava
パッケージとして配置できます。

## 構成

```text
HTTP / Queue
    ↓
OfficePdfService（入力検証・同時実行1）
    ├─ ExcelPdfConverter
    ├─ WordPdfConverter
    └─ PowerPointPdfConverter
          ↓
NormalizedPdfWriter（PDFBox）
          ↓
document.pdf + report.json
```

ExcelとWordはPDFBoxの文字・罫線として出力します。PowerPointはApache POIの
Java2D描画結果を1スライド1画像でPDFへ配置し、POIが描画した文字を同じ位置へ
非表示の検索用テキストとして重ねます。テキストボックス、図形、グループ内の図形、
表の文字が対象で、スライドの見た目を保ちながらPDFビューアーで検索できます。
同梱の静的TrueTypeフォントをPDFへサブセット埋め込みし、日本語文字の検索と、
Functionsホストにフォントがない場合の描画を安定させます。

検索用テキストは同期HTTP・非同期HTTP・直接Queueで共通の既定動作です。
追加オプションは不要で、APIのリクエストとレスポンス形式は変わりません。

`SlideTextCapture` は `DrawFactory` の文字描画を捕捉し、改行・グループ・回転を
反映した文字位置を記録します。POIが中央・下端配置の測定に使う仮の描画は除外し、
`NormalizedPdfWriter` が不可視モードで文字を重ねます。同梱フォントで表せない文字は
検索層で `□` とし、`PDF_TEXT_GLYPH_UNSUPPORTED` の警告をreportへ記録します。

## 設計上の境界

- Officeの印刷組版との完全一致は目標にしない。
- Excel数式を再計算せず、保存済み値を使う。
- 外部参照を取得しない。
- PowerPointの検索対象はPOIが文字として描画した内容に限る。画像内の文字のOCRは
  行わず、POIが描画できないSmartArt・グラフなどの文字も検索対象にならない場合がある。
- 内容個数の固定上限は置かず、バイト数・画素数・任意ページ上限だけを持つ。
- 1インスタンス内の重い変換は1件ずつ実行する。

## 検証

```shell
cd functions/office2pdf
./mvnw -B -ntp clean test
python3 -m unittest discover -s scripts -p 'test_*.py'
./mvnw -B -ntp package
```

Javaテストは各Office形式、日本語検索可能PDF、ページ上限、同期HTTP、非同期状態・
成果物、直接Queueの再試行と冪等性を確認します。`package` は全Functionの
`function.json` を生成し、`target/azure-functions/office2pdf-local` を作ります。
