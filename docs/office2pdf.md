# office2pdf 実装ガイド

`functions/office2pdf` はOffice文書を正規化PDFへ変換する独立したJava 21
Azure Functionsアプリです。LibreOffice、Microsoft Office、FFmpegなどの
ネイティブプロセスを使わないため、Linuxの従量課金Function Appへ通常のJava
パッケージとして配置できます。

PowerPointのPDFを検索可能にした変更経緯・実装詳細・検証結果は、
[検索対応の変更記録](#searchable-powerpoint-change)を参照してください。

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

<a id="searchable-powerpoint-change"></a>

## 変更記録：PowerPoint PDFの検索対応

実装日：2026-10-11。記録日：2026-10-12。
対象コミット：[3376cea — Make PowerPoint PDF output searchable](https://github.com/sunu-py-jp/convertX2X/commit/3376cea38e314ac4fce7c9bf8cf22f8b9365f618)。
対象は `office2pdf` の `.pptx` / `.ppt` 入力です。

### 目的と変更前後

PDFビューアーでスライド内の文字列を検索できるようにしました。変更前は、
PowerPointをJava2Dで画像化してPDFへ配置していたため、見た目に文字があっても
PDFには検索用の文字情報がありませんでした。

| 項目 | 変更前 | 変更後 |
| --- | --- | --- |
| スライドの表示 | POIで描画したスライド画像 | 同じスライド画像を維持 |
| PDF内の文字情報 | スライド画像のみ | スライド画像と不可視の検索用テキスト |
| 日本語・図形・表の文字の検索 | OCRなどの追加処理が必要 | POIが文字として描画した内容を検索可能 |
| 元から画像に含まれる文字 | 文字情報なし | 引き続き文字情報なし。OCRは実行しない |

スライドをPDFの文字・図形だけで描き直す方式では、別途レイアウトの再現が必要です。
既存の描画結果を保つため、画像の上に見えない文字を重ねる方式を採用しました。
新しい外部サービス、ネイティブ処理、ライブラリ依存は追加していません。

```text
PowerPoint
    ↓ POIのslide.draw(graphics)
    ├─ スライド画像 ─────────────────┐
    └─ 描画した文字・位置・回転の記録 ─┤
                                    ↓
                         PDFBoxで1ページに配置
                         ├─ 表示用のスライド画像
                         └─ 検索用の不可視テキスト
```

### 実装した内容

| ファイル | 変更内容 |
| --- | --- |
| [PowerPointPdfConverter.java](../functions/office2pdf/src/main/java/com/convertx2x/office2pdf/conversion/PowerPointPdfConverter.java) | スライドごとに文字の記録処理を設定し、画像配置後に検索用テキストを書き込む |
| [SlideTextCapture.java](../functions/office2pdf/src/main/java/com/convertx2x/office2pdf/conversion/SlideTextCapture.java) | 新規追加。POIの `DrawFactory#getTextFragment` を使い、描画された文字と配置を記録する |
| [NormalizedPdfWriter.java](../functions/office2pdf/src/main/java/com/convertx2x/office2pdf/conversion/NormalizedPdfWriter.java) | `slideText` を追加。記録した文字を埋め込みフォントでPDFへ出力する |
| [PowerPointSearchablePdfTest.java](../functions/office2pdf/src/test/java/com/convertx2x/office2pdf/conversion/PowerPointSearchablePdfTest.java) | 検索・重複・位置・描画結果・旧形式PPTの回帰テストを追加する |

`SlideTextCapture` は独自にスライドの文字列を並べ直さず、POIが実際に描画する
行の `TextLayout` と `Graphics2D` の変換を利用します。テキストボックスに加え、
図形、グループ内の図形、表のセルも同じ経路で記録します。

- **文字の重複を防止**：POIは中央・下端配置の計算でも文字描画を呼び出します。
  実際のスライド用 `Graphics2D` と同じオブジェクトの場合だけ記録し、測定用の
  仮描画が検索文字列へ混入しないようにしています。
- **位置と回転を反映**：行のベースラインと描画時の変換行列を保存します。
  グループによる配置はPOIの座標へ反映済みなので、二重に適用しません。
  PDFへ配置するときは実際の画像幅・高さから倍率を求め、上下の座標方向を変換します。
  画素数上限で画像を縮小したときの、整数ピクセルへの丸めもこの倍率に含まれます。
- **文字幅を合わせる**：`BreakIterator` で文字のまとまりを扱い、サロゲートペアや
  結合文字を途中で分けないようにします。POIのキャレット位置から幅を求め、PDF側の
  文字幅を合わせます。単語を検索できるよう、元の文字列の順序を保持します。
- **見た目を維持**：PDFBoxの `RenderingMode.NEITHER` で文字を出力します。
  文字は表示・クリッピングに使わず、スライド画像の描画を変えません。
  検索層には既存の同梱BIZ UDPゴシックをサブセット埋め込みします。

### APIと既存ファイルへの影響

同期HTTP・非同期HTTP・直接Queueはいずれも `OfficePdfService` を共有するため、
新しい実装で変換すると既定で検索用テキストが付きます。追加の指定や環境変数は不要です。
エンドポイント、Queue依頼JSON、PDF・report・ZIPの返却方法は変更していません。
ExcelとWordの変換ロジックも変更していません。

文字情報とフォントを追加する分、PDFサイズは増えます。既存の出力バイト数上限が
引き続き適用されます。増加量や処理時間のベンチマークは実施していません。

**変更前に生成・保存したPDFには自動で文字情報は追加されません。**
新しい変換アプリを配置した後、元のPowerPointから再変換し、ビューアー側で新しいPDFを
読み直してください。ビューアーに古いPDFのキャッシュがある場合は、その更新も必要です。

### 対応範囲と制約

- 日本語・英数字など、POIが文字として描画し、同梱フォントで表現できる内容が検索対象です。
  貼り付け画像の内部、グラフの代替画像などから文字を抽出する処理はありません。
- 同梱フォントで表せない文字は検索層のみ `□` へ置き換え、
  `PDF_TEXT_GLYPH_UNSUPPORTED` をスライド単位でreportに記録します。スライド画像は変えません。
  同期HTTPでは `X-Warning-Count` で警告数を確認できます。警告の詳細を含むreportは
  非同期の取得API・成果物ZIP、またはJavaの変換結果から参照します。
- `.pptx` の非表示スライドは従来どおり除外し、検索層にも含めません。
  旧形式 `.ppt` の非表示スライド除外は実装していません。この変更で追加された挙動ではありません。
- 上付き・下付きなど、文字単位の縦方向の移動・フォント変換は検索層へ個別に保持していません。
  表示画像はそのままですが、ビューアーの選択範囲・ハイライト位置が完全に一致するとは限りません。
- 描画後に別の図形で覆われる文字を、見えるかどうかで判定する処理はありません。
  PDFの読み順・改行の解釈や、行をまたぐ検索の可否はビューアー側の文字抽出にも依存します。

### 実装時の検証結果

2026-10-11のローカル検証では、`office2pdf` のJavaテスト24件が成功し、
Mavenの `package` も成功しました。追加した3件のテストでは次を確認しています。

| 確認内容 | 結果 |
| --- | --- |
| 装飾が異なる複数の文字列に分かれた「四半期売上報告」 | 連続した文字列として抽出できる |
| 日本語の表、拡大・回転したグループの文字 | 対応するページで抽出できる |
| 中央・下端配置の文字 | 測定用の仮描画による重複がなく、意図した位置にある |
| `.pptx` の非表示スライド | ページ数にも抽出文字列にも含まれない |
| 検索層の状態 | フォントが埋め込まれ、不可視モードで出力されている |
| 見た目 | テスト資料では、検索層を持たない既存POI描画のPDFと全画素が一致する |
| 旧形式 `.ppt` | 日本語文字列を重複なく抽出できる |

別プロジェクト `explorer-source` のPDFモードでも確認しました。実装時のPDF.jsは
6.4.299です。`createPdfTextLoader` / `searchPdf` に実変換PDFを渡し、次の結果を得ました。

| 検索語 | 検索結果 |
| --- | --- |
| 四半期売上報告 | 1ページ |
| 営業部 | 2ページ |
| 拡大回転グループ | 2ページ |
| 非表示の機密メモ | 0件 |

実画面でも「四半期売上報告」「拡大回転グループ」の検索結果と該当ページへの移動を
確認しました。このビューアーはPDF.jsが返す隣接する文字列を連結するため、今回の
文字単位の出力でも日本語の単語内に不要な空白が入りませんでした。
ビューアー自体の変更は不要でした。

以上はローカルのテスト資料での検証結果です。全PowerPoint資料での描画一致、
すべてのPDFビューアーとの互換性、Azure上の性能を保証するものではありません。

### 再検証と確認用ファイルの生成

```shell
cd functions/office2pdf
./mvnw -Dtest=PowerPointSearchablePdfTest -DsearchablePptDemo=true test
```

`target/searchable-ppt-demo/` に次のファイルが生成されます。

| ファイル | 内容 |
| --- | --- |
| `searchable-slides.pptx` | 日本語、装飾付き文字、表、回転グループ、非表示スライドを含む入力 |
| `searchable-slides.pdf` | 実変換した2ページのPDF |
| `extracted-text.txt` | PDFBoxで抽出したページごとの文字列 |
| `page-1.png` / `page-2.png` | 生成PDFを描画した確認用画像 |

PDFを対象ビューアーで開き、上の検索語と結果を確認します。
確認用ファイルはGit管理対象外の `target/` に置かれ、`clean` で削除されます。
全Javaテストと配布パッケージも確認する場合は、続けて `./mvnw package` を実行します。
