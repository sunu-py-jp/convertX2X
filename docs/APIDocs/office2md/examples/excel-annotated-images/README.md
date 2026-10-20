# Excelの画像・図形・表をまとめる実例

架空の申請画面を使った3シートの編集可能なExcelです。画像・図形・セルは別々の要素として保存し、実際のOffice2MD変換で合成しています。

| シート | 入力の構成 | 実際の出力 |
| --- | --- | --- |
| 画像と注釈の比較 | 同じ元画像に異なる赤枠・青枠・テキストボックス・保存済み接続 | 合成PNG2枚。接触だけの図形と離れたメモは独立した図形PNG2枚 |
| 画像同士の重なり | 同じ画面を2枚、位置と大きさを変えて重ねる | 図形がなくても重なり順を保った合成PNG1枚 |
| 表と画像と注釈 | 罫線表の上に画面画像・赤枠・はみ出す注釈を配置 | 表を含む合成PNG1枚。文字と表はMarkdownにも保持 |

出力は合成PNG4枚、独立した図形PNG2枚です。合成に使った元画像・図形の単独参照を重複出力しません。はみ出した内容全体を表示し、拡張した背景だけを透明にします。

`report.json` では合成ブロックの `type` とアセットの `sourceKind` が `compositeImage` です。Excelの表・セルを含めた範囲は `metadata.cellRanges`、元画像の情報は `sourceImages[]` に記録します。

## ファイル

| ファイル | 内容 |
| --- | --- |
| [input.xlsx](input.xlsx) | 元画像と編集可能な図形・表を含む3シート |
| [source-image.png](source-image.png) | 注釈のない元の架空画面 |
| [result.zip](result.zip) | 変換処理が生成したZIP |
| [document.md](result/document.md) | 表・本文・図形文字・接続・画像参照 |
| [report.json](result/report.json) | 出力と元要素の対応・警告 |
| [画像同士の合成PNG](result/images/image-0003.png) | 重なり順を保持した2枚の画像 |
| [表と画像と注釈の合成PNG](result/images/image-0004.png) | 表を背景に描き、画像と注釈を重ねた結果 |
| [run.json](run.json) | 入力・成果物のサイズ、SHA-256、処理時間、実行方法 |

`imageMode=ignore` で共通変換コアを実行した記録です。OCR通信や実Azure上の動作、Queue経由の性能を測定したものではありません。OCRを指定した場合も合成PNGを送信せず、元画像の可視領域だけを認識し、同じバイト列の結果を同一文書内で共有します。

## 再生成

JDK 21を使い、`functions/office2md/` で実行します。

```sh
./mvnw package
java -Djava.awt.headless=true \
  --class-path "target/classes:$(cat target/license-runtime-classpath.txt)" \
  examples/ExcelAnnotatedImages.java \
  ../../docs/APIDocs/office2md/examples/excel-annotated-images \
  --convert
```

`--convert` を省略すると入力Excelと元PNGだけを生成します。変換にはHTTP・Queueと共通の `OfficeMarkdownService` を使用します。`elapsedMs` は変換部分の実測で、Functionsの起動時間やHTTP通信は含みません。生成する画面・名称・数値は架空で、外部画像は使いません。
