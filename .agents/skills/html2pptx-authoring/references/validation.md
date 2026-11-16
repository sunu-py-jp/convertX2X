# 実変換と納品

## 入力を確認する

全ページを目視し、ページ数・縦横比・画像参照を確認。境界だけでなく文字同士、図とラベル、写真と注釈の重なりも見る。意図した重なりと誤った重なりを分ける。自動検査はすべての見た目の問題を検出しない。

SVGのid重複、未解決の画像パス、data-slide以外のdata属性、実行コードや外部CSSを残さない。

## ローカル変換

convertX2Xリポジトリのfunctions/html2pptxでNode.js 24とローカルChrome/Edgeを使う。依存が準備済みならインストールを繰り返さない。

```sh
cd /path/to/convertX2X/functions/html2pptx
# 初回の依存準備が必要な場合
npm ci --ignore-scripts --no-audit --no-fund
npm run convert -- /path/to/input.html /path/to/output
```

ZIPも同じコマンドで扱う。ブラウザーを自動検出できなければHTML2PPTX_CHROMIUM_PATHに実行ファイルの場所を設定する。

presentation.pptx、report.json、result.zipを確認する。html-01.png等は変換で採用したHTMLの静止状態であり、PPTXのスクリーンショットではない。

## API送信を依頼された場合

指定された接続先と認証方法を使う。接続先・キーを推測せず、スキルを読んだだけでデプロイや外部送信を行わない。

| 項目 | 契約 |
| --- | --- |
| メソッド | POST /api/convert?filename=input.html。ZIPならfilename=input.zip |
| 本文 | HTMLまたはZIPの生バイト列。JSON・multipart・全ファイルのBase64文字列ではない |
| Content-Type | application/octet-streamを使える |
| Azure認証 | x-functions-keyヘッダー。HTML/ZIPの中へキーを入れない |
| 成功 | 200。既定はpresentation.pptx＋report.jsonのZIP |
| PPTX単体 | format=pptx。reportがないため検証には既定ZIPを優先 |
| エラー | error.code/error.messageを含むJSON。成功したPPTXとして保存・提示しない |

ローカルの例:

```sh
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @input.html \
  'http://localhost:7077/api/convert?filename=input.html' \
  --output result.zip
```

## reportと見た目

| 検査 | 対応 |
| --- | --- |
| ページ数・順序・画像 | 元HTMLと照合。文字・図形の画像化を解決策にしない |
| FONTS_NOT_EMBEDDED | 非埋込という仕様上の警告。画像化して警告を消さない |
| IMAGE_BLUR_APPLIED | 加工PNGになったことを確認。効果量はPPTXで編集できない |
| ANIMATION_STATIC_FALLBACK | 要素・理由と終端の可読性を確認。動きが必須なら対応範囲へ修正 |
| SLIDE_OVERFLOW | 該当ページを再構成・分割。切り捨てや極端な縮小で通さない |
| UNSUPPORTED_* | 該当表現を調べ、意味を保てる対応表現へ置換。制限を解除しない |

PPTXも利用予定のビューアーで資料全体を開き、全ページの背景・図形・文字を確認する。先頭ページのサムネイルや、1枚ずつ切り出したPPTXだけでは、複数枚を開いた際の図形の混入を検出できない。利用予定の環境を使えない場合は、利用できるレンダラーで確認し、未確認の環境を明記する。リポジトリには開発用の比較ツールがある。

```sh
node scripts/visual-compare.mjs /path/to/input.html /path/to/comparison
```

比較ツールにはLibreOffice、Java 21、Python/Pillow等が別途必要。これは静止描画の比較で、PowerPoint本体でのアニメーション再生検証ではない。Quick Lookで背景や図形が欠落・混入した場合は、別レンダラーで正しく見えても、利用予定の環境での問題として再現条件を調べる。変換器では単一図形のSVG由来グループを変形を保って解除し、塗りだけの厳密な矩形パスを標準図形にする対処を行っているが、全資料・全ビューアーの一致を保証するものではない。

Noto Sans JPはPPTXへ埋め込まれない。Quick Lookを含む閲覧端末で代替フォントになり、見た目・文字幅・改行が変わる場合は、その環境でも読めるか確認する。macOSのプレビュー確認をiPhoneやPowerPoint本体での表示・編集・再生の確認と混同しない。

入力の修正後は修正内容を再確認する。同じエラーで変更なく再送しない。環境・接続・認証不足は資料の書き換えで隠さず、検証できた範囲を報告する。

## 納品

HTML単体か全素材を含むZIPを渡す。変換も依頼されていればPPTXとreportを添え、確認範囲と残る固定・近似を短く伝える。生成途中のファイルや私的なパスを素材ZIPへ混ぜない。

スキルの配布ZIPと変換APIへ送る入力ZIPは別物。SKILL.mdやこの参照資料はAPIの入力に含めない。
