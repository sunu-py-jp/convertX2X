# html2pptx 利用・運用ガイド

## 対応範囲

静的HTMLの配置をChromiumで計算し、PowerPointの文字・図形・表へ変換します。
HTMLの本文、Flex/Grid配置、単色背景・罫線、PNG/JPEG、SVGの図形・線・文字を扱います。
図形名の一覧で判定するのではなく、輪郭・線・塗り・座標変換から編集可能なパスを生成します。SVGの任意の多角形・凹形・自由曲線、矢印、点線・破線を扱います。CSSのborderで作る三角形、clip-pathのpolygon/circle/ellipse/inset/path、角丸、回転、図形のskew、空の::before/::after装飾も同じベクター処理へ渡します。
SVGは親要素を含む回転・拡縮とviewBoxの配置を反映します。HTML本文はブラウザーの行・書式区間を計測し、編集可能なテキストとして配置します。編集後もHTMLの自動レイアウトが維持されるわけではありません。

SVG由来の線や矢印には、PowerPointの自動接続や組織図の意味は付与しません。
元から画像のPNG/JPEGは、移動・サイズ変更・差し替えが可能な画像として埋め込みます。
画像内の文字をOCRしたり、画像を編集可能な図形へ分解したりはしません。

現在は同期HTTPとCLIに対応しています。非同期HTTP・直接Queueからの依頼は未実装です。
Storageの設定によってジョブAPIが有効になる仕組みはありません。

## 入力HTMLとスライド

入力はUTF-8の `.html` / `.htm`、またはルートに `index.html` / `index.htm` のどちらか1つを含むZIPです。

```html
<!doctype html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <meta name="pptx-slide-width" content="1280">
  <meta name="pptx-slide-height" content="720">
  <style>
    [data-slide] { padding: 48px; background: #f7f9fc; }
    h1 { font-size: 34px; color: #173f61; }
  </style>
</head>
<body>
  <section data-slide>
    <h1>上期の業績</h1>
    <p>売上高は前年同期比12.6%増加しました。</p>
  </section>
  <section data-slide>
    <h1>下期の重点施策</h1>
    <p>共通基盤の整備と、顧客サポートを拡充します。</p>
  </section>
</body>
</html>
```

- `data-slide` を付けた要素をDOM順で1スライドずつ出力します。入れ子にはできません。
- 区切りがなければ本文全体を1枚として扱います。
- 既定は1280×720 CSS px。寸法変更時は幅・高さのmetaを両方指定します。
- 文章を要約したり、長いページを自動で分割したりはしません。各スライドに収めてください。
- はみ出しや未対応の描画指定を検出した場合はエラーで返します。
- ブラウザーが計算した座標に固定するため、編集後にFlex/Gridの再配置は行われません。

### CSSと画像の同梱

```text
input.zip
├── index.html
├── styles.css
└── assets/
    ├── diagram.svg
    └── product.png
```

HTML/CSSからZIP内の相対パスで参照します。HTML単体ではstyle要素・style属性、
インラインSVG、Base64のPNG/JPEG data URIを利用できます。SVGファイルを `img` で
参照した場合も、検査後に図形・文字へ変換します。

外部URL・PC上の絶対パス・ZIP外への参照は許可しません。リンクの表示と外部素材の取得は別です。
HTTP/HTTPS/mailtoのリンク先へ変換処理がアクセスすることはありません。
ZIPにはHTML・CSS・PNG・JPEG・SVGのみを含め、暗号化・シンボリックリンク・重複パスを使わないでください。

### 画像の変形と重なり

- PNG/JPEGは元のバイト列を保持し、同一の画像データはPPTX内で共有します。拡縮、縦横比の変更、親を含む回転、上下左右反転、`object-fit` / `object-position`、画像自身の角丸・円形、`opacity`を反映します。これらは画像の配置・切り抜き・輪郭等として保存し、画像全体を再描画して置き換えません。
- DOM順、`z-index`、親のスタッキングコンテキストを考慮して、画像・図形・文字の前後関係を保存します。上に重ねた文字や枠は別の編集可能な要素です。
- 画像自身の単独の `filter: blur(3px)` 等は、その画像だけをぼかしたPNGにして保持します。reportには `IMAGE_BLUR_APPLIED` を記録します。位置・サイズ・画像の差し替えは編集できますが、PPTX上でぼかし量を変更する効果にはなりません。同じ加工条件の画像も共有します。
- 画像のskew・3D変形・`clip-path`・影、親による切り抜き・filter、blur以外や複数を組み合わせたfilterは未対応です。切り抜きは `object-fit`、角丸は画像自身の `border-radius` を使います。ぼかす画像の背景や枠は別要素にし、ぼかしの余白が親で切り取られないようにします。
- 重なる複数の子要素を親の `opacity` でまとめて透過する表示は未対応です。各要素に個別の `opacity` を指定してください。画像自身の背景もまとめて透過する場合は、背景を別要素に分けます。

### 制約

JavaScript、イベント属性、React等の実行前のHTML、iframe、object/embed、動画、フォームは非対応です。
外部Webフォントや任意のフォントファイルを入力に含めることもできません。
SVGフィルター・マスク・複雑なクリッピング、文字のskew・縦横比の変形、CSSの3D変形、文字・カウンターを含む疑似要素などはエラーになります。子要素を含むCSSクリッピング、背景サイズ・位置・描画範囲の変更などにも未対応の組合せがあります。静的HTMLのあらゆるCSSを完全に再現する契約ではありません。
SVGルート自身のCSS背景・枠線・paddingは、外側のHTML要素へ指定してください。空の疑似要素は置換前後の配置を検査し、元の配置を保てない場合やスタイル展開が32MiBを超える場合はエラーにします。
対応対象の文字・図形を黙って画像化して成功扱いにはしません。
一部の描画差はreportの警告へ記録します。

フォントとしてNoto Sans JP（Regular/Bold）を同梱します。HTML/CSSの指定にかかわらず、HTML・SVGの文字をこのフォントに統一して計測・変換します。PPTXへフォント自体は埋め込まないため、閲覧端末に
対応フォントがない場合は代替表示となり、文字幅や改行に差が出る場合があります。
PowerPointでの表示・編集も含め、利用予定の資料で確認してください。

## ローカルで変換する

Node.js 24とChromeまたはEdgeを使用します。CLIだけを利用する場合はAzure Functions Core Toolsを必要としません。

```shell
cd functions/html2pptx
npm ci --ignore-scripts --no-audit --no-fund
npm run convert -- examples/organization-governance.html output/organization
```

macOSのChrome、Windowsの標準的なChrome/Edgeの場所を確認します。自動検出できない場合は
環境変数 `HTML2PPTX_CHROMIUM_PATH` にブラウザー実行ファイルの絶対パスを設定します。
Linux x64のAzure環境では、同梱したChromiumと共有ライブラリを使用します。
Linux以外のローカル起動を、Azureと同じ実行環境での検証とは扱いません。

CLIの出力は以下です。

```text
output/organization/
├── presentation.pptx
├── report.json
├── result.zip
├── html-01.png
└── html-02.png
```

`html-*.png` は入力HTMLの描画です。PPTXを開いた画像ではありません。
`result.zip` にはPPTXとreportを含め、プレビューPNGは含めません。

## PlaygroundとHTTP

Azure Functions Core Tools v4を追加し、設定例をコピーします。

```shell
cp local.settings.example.json local.settings.json
npm start
```

Windowsではコピー操作を `Copy-Item local.settings.example.json local.settings.json` に置き換えます。
Playgroundは `http://localhost:7077/api/playground` です。同期処理だけなので、変換データ用のAzuriteは使いません。

| メソッド | パス | 内容 |
| --- | --- | --- |
| POST | `/api/convert?filename=input.html` | HTMLからPPTX＋reportのZIPを生成 |
| POST | `/api/convert?filename=input.zip&format=pptx` | ZIP入力からPPTX本体を返す |
| GET | `/api/playground` | 簡易画面。匿名で参照可能 |
| GET | `/api/capabilities` | 対応形式、上限、非同期対応の有無。匿名で参照可能 |

```shell
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @examples/organization-governance.html \
  'http://localhost:7077/api/convert?filename=organization-governance.html' \
  --output result.zip
```

本文はmultipart・JSONではなく、生のバイト列です。`filename` を省略した場合は `input.html`、
`format` を省略した場合は `zip` として扱います。ZIPを送る場合は `filename` に拡張子を含めます。
Azure上の変換APIは `x-functions-key` ヘッダーが必要です。

成功は200、入力や変換のエラーはJSONの `error.code`・`error.message` とHTTPステータスを返します。
同一ワーカーの別のHTTP変換が処理中なら429と `Retry-After: 10` を返します。
コード一覧と応答形式は [HTTPリファレンス](../../../docs/APIDocs/html2pptx/http.html) を参照してください。

## 設定と上限

MBという名前の設定はMiB（1,048,576 bytes）です。各値は正の整数で、0による無制限指定はありません。
Azureのアプリ設定、ローカルでは `local.settings.json` の `Values` に指定します。

| 環境変数 | 既定 | 設定可能な最大値 |
| --- | ---: | ---: |
| `CONVERSION_MAX_INPUT_MB` | 20 MiB | 100 MiB |
| `CONVERSION_MAX_UNPACKED_MB` | 100 MiB | 300 MiB |
| `CONVERSION_MAX_OUTPUT_MB` | 100 MiB | 300 MiB |
| `CONVERSION_MAX_PACKAGE_FILES` | 500 | 2,000 |
| `CONVERSION_MAX_SLIDES` | 50 | 100 |
| `CONVERSION_MAX_DOM_NODES` | 50,000 | 100,000 |
| `CONVERSION_MAX_DOM_DEPTH` | 64 | 128 |
| `CONVERSION_MAX_CSS_BYTES` | 2,097,152 | 8,388,608 |
| `CONVERSION_MAX_DIMENSION_PX` | 4096 px | 8192 px |
| `CONVERSION_MAX_IMAGE_PIXELS` | 20,000,000 | 40,000,000 |
| `CONVERSION_MAX_TOTAL_IMAGE_PIXELS` | 80,000,000 | 160,000,000 |
| `CONVERSION_MAX_SHAPES` | 10,000 | 30,000 |
| `CONVERSION_TIMEOUT_SECONDS` | 180秒 | 210秒 |

スライドの幅・高さはそれぞれ64 px以上、`CONVERSION_MAX_DIMENSION_PX` 以下です。
ZIPは展開後の実データ量も監視します。ファイルの圧縮サイズだけを上限判定に使うものではありません。
画像は1枚と合計の画素数を制限します。同じ画像の繰り返しも、変換時の配置ごとの処理量として合計に数えます。
時間・メモリー・出力要素数は入力によって変わり、これらの上限が4GBでの性能を保証する値ではありません。

## Azureで実行する

ほかの機能とは独立したLinux x64 / Node.js 24のFunction Appへ配置します。
Flex Consumptionの4096 MBを対象とし、検証時はHTTP同時実行数1・最大インスタンス数1・always-readyなしで確認しています。

Chromiumと共有ライブラリは機能の依存パッケージに含めます。ホストOSへパッケージを追加する方式ではありません。
ブラウザーは外部URLの取得を禁止し、変換用の子プロセスへStorageキーなどのAzure設定を継承しません。
ブラウザーのsandboxを無効化するフォールバックは本機能では行いません。
任意HTMLを受け付けるアプリとして、認証・上限・利用者のアクセス制御を含めて運用してください。

2026-10-30の検証では、変換ハンドラーが実行されない存在しないURLへのGETでも、通常の404と約60秒後の503が断続的に返りました。最大インスタンス数を2へ一時変更しても再現し、現在は1へ戻しています。always-readyは設定せず、スケールトゥゼロを維持しています。変換処理自体の成功と、HTTP入口の継続的な安定稼働は分けて評価しており、本番利用前に連続呼び出しを再検証する必要があります。

## 検証用の資料

[実際の変換例](../../../docs/APIDocs/html2pptx/examples.html) に、7資料・20スライドを掲載しています。
企業名・数値・画面画像は架空の内容です。画像の変形例には、この検証用にAIで生成した写真を使用しています。

| 入力 | 主なパターン |
| --- | --- |
| `examples/enterprise-announcement.html` | 企業発表、CSSレイアウト、原PNG、工程図 |
| `examples/financial-results.html` | KPI、結合見出しの表、利益増減のSVG図 |
| `examples/organization-governance.html` | 30ノードの体制図、横断関係、分岐・循環、基本図形・矢印 |
| `examples/api-architecture.html` | 4層構成、双方向通信、シーケンス、再試行 |
| `examples/project-plan.html` | 工程表、マイルストーン、課題表、稼働基準 |
| `examples/visual-fidelity.html` | 5枚・30項目の図形・線・文字の比較 |
| `examples/image-patterns-input.zip` | 5枚・26パネル、同じ写真28配置。変形・ぼかし・重なり順 |

画像例のHTMLは同梱PNGを相対参照するため、API・CLIへは `image-patterns-input.zip` を送ります。元画像と生成プロンプトも実例ページで確認できます。

```shell
npm run convert -- examples/image-patterns-input.zip output/images
npm test
```

入力検査と変換のテストは、ブラウザーが必要な項目と不要な項目があります。
HTMLのプレビューと、生成PPTXをLibreOfficeまたはPOIで描いたプレビューは区別して扱います。
PPTXの内部に文字・図形が存在することを確認しても、PowerPointの全バージョンでの表示一致の証明にはなりません。

### ピクセル比較

`examples/visual-fidelity.html` は5枚・30パターンの図形・線・文字・画像を含みます。

```shell
node scripts/visual-compare.mjs examples/visual-fidelity.html output/visual-comparison
```

開発用にLibreOffice、Java/PDFBox、Python/Pillowを使い、入力HTMLと生成PPTXを同じ1280×720で描画します。これらはFunctions本番の依存ではありません。比較レポートは画素差、平均誤差、輪郭の差、重ね合わせを保存します。Microsoft PowerPoint本体の検証ではなく、フォント描画やアンチエイリアスにも差があるため、画素の完全一致は保証しません。
主比較はLibreOffice→PDF→PDFBox、副比較はPOIです。比較用のJava 21・Python・LibreOfficeと関連スクリプトは単独配布の `dist/` に含めません。本番で使うのはNode.js 24、同梱Chromium、その実行ライブラリとフォントです。

## ライセンス

アプリはMITです。単独配布時も、機能内の `LICENSE`、`NOTICE.md`、`third-party/`、フォントのライセンスを含めます。
依存ライブラリ・Chromium・共有ライブラリ・フォントの出典と通知は、同梱資料を参照してください。
