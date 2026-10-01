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
Java2D描画結果を1スライド1画像でPDFへ配置します。同梱の静的TrueTypeフォントを
PDFへサブセット埋め込みし、日本語文字の検索と、Functionsホストにフォントがない
場合の描画を安定させます。

## 設計上の境界

- Officeの印刷組版との完全一致は目標にしない。
- Excel数式を再計算せず、保存済み値を使う。
- 外部参照を取得しない。
- PowerPointはページ内検索より後段OCRを前提にする。
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
