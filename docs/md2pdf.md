# md2pdf 実装ガイド

`functions/md2pdf` はMarkdownを検索可能な日本語PDFへ変換する独立したJava 21
Azure Functionsアプリです。CommonMarkで構文解析し、PDFBoxで文字・表・画像を
配置します。ブラウザーやネイティブ実行ファイルを必要としません。

## 構成

```text
HTTP / Queue
    ↓
MarkdownPdfService（入力検証・同時実行1）
    ↓
Markdown構文解析（CommonMark + GFM表）
    ↓
NormalizedPdfWriter（PDFBox）
    ↓
document.pdf + report.json
```

日本語フォントをPDFへサブセット埋め込みし、ホスト環境に依存せず文字を検索可能に
します。単独のUTF-8 Markdownと、画像を含むZIPを同じ変換処理へ渡します。

## 設計上の境界

- HTML/CSSのレイアウト再現ではなく、本文・表・画像を読みやすく再配置する。
- 画像はZIP内の相対パスまたはBase64データURIから解決する。外部通信はしない。
- 未対応画像は警告と代替ラベル、Mermaidは警告とコード表示にする。
- HTMLは文字として表示する。ただし単純な `<br>`（`<br/>`・`<br />` を含む）は改行、`<!-- pagebreak -->` は改ページ命令にする。
- 内容個数の固定上限は置かず、バイト数・画素数・任意ページ上限を持つ。
- 1インスタンス内の重い変換は1件ずつ実行する。

## 検証

```shell
cd functions/md2pdf
./mvnw -B -ntp test
python3 -m unittest discover -s scripts -p 'test_*.py'
./mvnw -B -ntp package
```

Markdown構文、日本語テキスト抽出、改ページ、画像入力、ZIPの選択・安全なパス解決、
同期HTTPと非同期の成果物を確認します。`package` は各Functionの `function.json` と
`target/azure-functions/md2pdf-local` を生成します。
