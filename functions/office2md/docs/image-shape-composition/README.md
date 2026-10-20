# Office2MD：画像・図形・表の重なりを合成する対応

更新：2026-10-21

Excel・Word・PowerPointをMarkdownへ変換するとき、重なって配置された画像・図形・テキストボックス・表を、位置関係を保った1枚のPNGへまとめます。画面キャプチャに赤枠や別の画像を重ねた資料、表の上に注釈を置いた資料を対象にします。

対象は `office2md` の共通変換処理です。同期HTTP・非同期HTTP・直接Queueから利用できます。Office2PDFやExcel2IMGの処理を変更するものではありません。

## 変更前後

| 項目 | 変更前 | 変更後 |
| --- | --- | --- |
| 画像＋重なる図形・テキストボックス | 画像と注釈が別々の出力になる場合がある | 重なり順を保って1枚のPNGへ合成 |
| 画像＋重なる画像 | 画像だけでは重なりをまとめない | 重なりが連鎖する画像を同じPNGへ合成 |
| 表・セル本文＋重なる描画要素 | 文字・表と描画要素を別々に出す | 位置が確定する範囲で背景の表・セルも描画。文字・表はMarkdownにも残す |
| 部分的にはみ出す注釈 | 分離した出力になりうる | 全体が収まるまでキャンバスを拡張し、空いた背景だけ透明にする |
| 画像形式 | PNG/JPEG以外は主に原本添付 | GIF/BMP/DIB/TIFF、対応するSVG/EMF/WMFをPNGへ変換して利用 |
| 元画像の重複出力 | 個別画像と参考画像に重複しうる | 合成に取り込んだ画像・図形の単独画像参照は出さない |
| 検索用の内容 | 本文・表・図形文字・確認できた接続を抽出 | 継続してMarkdownに保持。画像だけに置き換えない |
| OCR | 元画像を認識 | 合成前の元画像だけを認識。表・図形の描画PNGは認識しない |

はみ出した図形も、塗り・線・文字を含めて表示します。透明なのは拡張した領域の背景です。APIDocsの市松模様は透明部分を見やすくするUIの背景で、PNGには含まれません。

## 合成の規則

- 保存された位置・形状・変形から、面積のある重なり、または画像や表の内部を横切る線を検出します。端が接するだけの要素はまとめません。
- 画像同士、画像と対応図形、表・セルと対応描画要素を対象にします。画像が含まれない「表＋赤枠」も合成できます。
- 重なりが連鎖する場合は全体をまとめます。明示グループや保存済み接続で既にまとめている図も維持します。
- 画像の透明画素を解析して重なりを判定する方式ではありません。元の重なり順も保持するため、背後の要素は前面の不透明画像で隠れる場合があります。
- 重なりから業務上の関係・接続先・読み順を推測しません。接続関係は既存の保存情報等による判定を使います。
- 対応する切り抜き・回転・反転を元画像に一度だけ適用してから合成します。
- 画像の画素数、1ファイルの容量、合計出力容量など既存の上限を適用します。大きなExcel表全体が画素数上限に収まらない場合は、表のMarkdownを保ったまま重なるセルの範囲へ局所化し、reportに理由を残します。合成する画像や図形そのものが上限を超えた場合は制限エラーになります。

## 形式ごとの範囲

| 形式 | 合成できる配置 | 保持する内容・制約 |
| --- | --- | --- |
| Excel XLSX・XLS | 同じシートの画像・図形と、重なる検出済み表・セル | セル幅・行高・結合範囲に基づき、直接設定された塗り・罫線・文字を描画。表とセル本文はMarkdownにも保持 |
| PowerPoint PPTX | 同じスライドの画像・図形・テキストボックス・表 | 表と通常テキストをMarkdownにも保持。スライドの背景全体を合成する方式ではない |
| Word DOCX | 同一描画内、または比較できる浮動アンカー同士。条件が揃った固定浮動表とその描画要素 | ページ組版は行わず、実位置を確定できない本文・表との合成は推測しない |

Wordの別アンカーは、同一段落・同じ保存済み改ページ区間にあり、上下・左右の座標基準が一致し、両軸に数値オフセットがある場合に比較します。`layoutInCell` が異なる場合、`allowOverlap=false`、別段落や位置不明の行内画像は分けます。

Wordの表は、保存済みの浮動位置・固定列幅・exact行高から範囲を求められ、同じ表内に比較可能な浮動描画がある場合に対象になります。対応するセル結合も反映します。通常の自動レイアウト表・本文段落のページ位置は推測しません。配置を確定できない場合は本文・表・画像を個別に保持し、理由をreportへ残します。

Officeと完全に同じ組版を再現するものではありません。Excel・Wordの文字や書式は近似を含みます。条件付き書式の再計算や、外部データ・リンク画像の取得は行いません。取消線の文字は既存の出力規則に従って除外します。

## 画像形式と対象外

PNG/JPEGに加え、デコードできるGIF・BMP・DIB・TIFFと、対応するSVG・EMF・WMFをPNGへ正規化します。複数フレーム・複数ページの画像は先頭だけを使います。静的なSVGを対象とし、スクリプト・外部参照・DTDなどは受け付けません。再現できない画像加工・形式は警告と原本添付へ戻し、可視領域が不確かな画像はOCRしません。

グラフ・SmartArt・数式オブジェクト・WordArtの装飾・埋め込みファイル・動画・音声は、今回の対応対象に含みません。取得できる文字や既存の未対応表示は維持します。

## 出力・OCR・利用側の変更点

合成PNGは `images/image-NNNN.png` に保存し、Markdownから参照します。文字・表は検索できるMarkdownにも残しますが、合成済みの図形や元画像を別の画像参照として重複出力しません。リクエスト形式と `imageMode` の指定は変更しておらず、合成は条件を満たす場合に自動で行います。

| フィールド | 内容 |
| --- | --- |
| `assets[].sourceKind` | `compositeImage`：合成PNG。既存の `embeddedImage`・`renderedDiagram` も継続 |
| `blocks[].type / path` | `compositeImage` とPNGの相対パス |
| `blocks[].metadata` | 合成範囲と構成要素の `sourceIds` など |
| `metadata.cellRanges` | Excelで画像へ含めた表・セル範囲 |
| `blocks[].sourceImages[]` | 元画像の可視領域ごとの `sha256 / sizeBytes / contentType / metadata / ocr` |
| `sourceImages[].ocr.status` | 元画像ごとのOCR状態。画像を含まない「表＋図形」は `sourceImages: []` |

座標はpt単位で、画像のピクセル寸法とは異なります。Excelはシート、PowerPointはスライド、Wordは保存された共通座標基準です。図形IDは変換時の図の範囲で扱い、文書全体で一意とはみなしません。利用側は固定の連番に頼らず、Markdownまたはreportの `path` を参照してください。

`imageMode=ignore` はOCR通信を行いません。`imageMode=ocr` は合成前の元画像の可視領域だけを認識し、参照の後へ文字を追加します。同一変換内では、そのバイト列のSHA-256でOCR結果を共有します。同じ画像に異なる注釈を付けても元画像の可視領域が同じなら共有します。1枚の合成画像内で同じ元画像を複数回使っている場合、同じOCR本文は1回だけ出します。別ジョブにまたがる永続キャッシュではありません。

合成PNG自体はOCRしません。セル・表・図形・テキストボックスから直接取り出した文字を、OCRへ重複送信しません。`sourceImages[]` は由来の記録で、合成に取り込んだ元画像の別ファイルをダウンロードするためのパスではありません。

## 主な実装と検証

| 実装 | 役割 |
| --- | --- |
| `ConversionWorkspace` | 合成アセット・元画像の由来・OCRの共有 |
| `DrawingExtractor / DrawingScene / SheetCellLayer` | Excelの重なり・表/セル背景・図形描画 |
| `NativeDrawingRenderer` | Word等の図形描画と共通の重なり判定 |
| `WordDrawings / WordMarkdownConverter` | 比較可能なWord配置の合成、本文と表の保持 |
| `PowerPointMarkdownConverter / PresentationOverlap / PresentationRenderer` | スライド内の画像・図形・表の合成 |
| `EmbeddedImagePreprocessor / VectorImageRasterizer` | 画像形式の正規化、可視領域の準備、外部取得の禁止 |

回帰テストでは、画像だけの連鎖した重なり、重なり順、境界接触の除外、表と図形だけの合成、文字・表の保持、透明背景、加工の一重適用、OCR対象と再利用を確認します。SVG等についても外部アクセスを行わないことと画素数制限を検証します。

今回の `./mvnw package` は成功しました。テスト453件中447件が成功し、実Storage接続を明示設定した場合だけ動かす6件はスキップしています。追加依存を含む86件の実行時ライブラリについて、LICENSE・NOTICE・出所・ハッシュの検証と、単独配布用パッケージへの同梱も成功しました。

## 入力と実際の変換結果

| 例 | 入力 | 出力 | 説明・実行記録 |
| --- | --- | --- | --- |
| Excel：画像同士、表・画像・注釈 | [input.xlsx](../../../../docs/APIDocs/office2md/examples/excel-annotated-images/input.xlsx) | [result.zip](../../../../docs/APIDocs/office2md/examples/excel-annotated-images/result.zip) | [README](../../../../docs/APIDocs/office2md/examples/excel-annotated-images/README.md) |
| Word：比較可能な浮動画像・固定表 | [input.docx](../../../../docs/APIDocs/office2md/examples/word-annotated-images/input.docx) | [result.zip](../../../../docs/APIDocs/office2md/examples/word-annotated-images/result.zip) | [README](../../../../docs/APIDocs/office2md/examples/word-annotated-images/README.md) |
| PowerPoint：画像同士、表・画像・注釈 | [input.pptx](../../../../docs/APIDocs/office2md/examples/powerpoint-annotated-images/input.pptx) | [result.zip](../../../../docs/APIDocs/office2md/examples/powerpoint-annotated-images/result.zip) | [README](../../../../docs/APIDocs/office2md/examples/powerpoint-annotated-images/README.md) |

[APIDocsの実変換例](../../../../docs/APIDocs/office2md/examples.html#excel-annotated-images)に合成PNG・Markdown・ZIPを掲載しています。`run.json` は実際の入力・成果物のハッシュと実行方法を記録します。サンプルは `imageMode=ignore` で実行しており、実AzureのOCR通信を検証した記録ではありません。
