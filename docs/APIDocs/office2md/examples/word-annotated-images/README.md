# Wordの画像・図形・固定浮動表を合成する例

架空の申請画面を使った3ページの入力です。画像、赤枠、テキストボックス、表はWord上で編集できます。

| ページ | 入力と出力 |
| --- | --- |
| 1 | 画面画像に赤枠とテキストボックスを重ねます。離れた緑のメモは別のPNGとして残します。 |
| 2 | 画面画像の右下に、同じ画像を縮小して重ねます。画像だけの重なりを1枚のPNGにまとめます。 |
| 3 | 固定位置の浮動表に画像と注釈を重ねます。表をMarkdownの表として残し、重なった見た目もPNGへまとめます。 |

はみ出した内容全体を含むよう画像サイズを広げ、広がった領域の空白だけを透明にします。
合成に含まれた元画像・図形・テキストボックスは、単独画像として重複出力しません。
取り出せる図形の文字はMarkdownにも保持します。

## Wordで位置を比較できる条件

通常の浮動画像・図形は、同じ段落・同じ保存ページ区切りの範囲で、
同じ座標基準と数値オフセットが保存されているものを比較します。
元から同じ描画グループ・キャンバスに入っている要素は、そのローカル座標を使います。
辺に触れるだけのものは重なりとは扱いません。

3ページ目の表では、以下の条件をすべて満たしています。

- `tblpPr` の水平・垂直基準はページまたは余白で、X/Y位置は数値として保存されています。
- `tblLayout` は `fixed`、`tblGrid` に正の列幅があり、全行の高さは `exact` です。
- 表内に明示的な改ページや保存済みページ区切りがありません。
- 比較する画像・図形はその表のセル内にアンカーを持ち、表と同じ座標基準です。
- セルへの自動位置補正を避けるため、`layoutInCell=false` のアンカーだけを表との合成対象にします。`allowOverlap=false` は対象外です。

`gridSpan` による横結合と `vMerge` による縦結合も、固定列幅・行高から範囲を計算します。
旧形式の `hMerge`、自動行高、幅の自動調整、位置を中央などの文字列で指定した表は合成対象外です。
この場合もMarkdownの表と画像・図形は保持し、`report.json` に位置を確定できない理由を記録します。
別段落の通常本文や、ページ内の組版位置が未確定な表との重なりは推測しません。

表のPNGは直接指定された塗り・罫線・代表的な文字書式を使う簡易描画です。
Wordのスタイル継承・段落余白・厳密なページ組版の再現は行いません。

## 実変換結果

共通の `OfficeMarkdownService` で実際に変換した結果を保存しています。
入力サイズ・実行時間・出力サイズ・SHA-256は `run.json` に記録しています。
実行時間は共通コアだけの実測で、Functions起動やHTTP通信の時間を含みません。

画像モードは `ignore` です。実AzureのOCR通信は行っていません。
OCRモードでも対象は元画像の可視領域であり、表・図形を含む合成PNGはOCRへ送りません。
同じ画像を縮尺違いで重ねても、元画像のOCR結果を再利用します。
表と図形だけの合成では `sourceImages` は空で、OCR処理は発生しません。

| ファイル | 内容 |
| --- | --- |
| `input.docx` | 編集可能な元ファイル |
| `source-image.png` | 注釈を付ける前の架空画面 |
| `result.zip` | 共通変換処理が生成したZIP |
| `result/document.md` | 実際のMarkdown全文 |
| `result/report.json` | 合成画像と元画像・図形の対応、警告 |
| `result/images/image-0001.png` | 赤枠・テキストボックスと画像を合成した透過PNG |
| `result/images/image-0002.png` | 重なった画像2枚を合成した透過PNG |
| `result/images/image-0003.png` | 浮動表・画像・テキストボックスを合成した透過PNG |
| `result/images/diagram-0001.png` | 離れた図形の参考PNG |
| `run.json` | 入力・成果物のSHA-256と実行記録 |

## 再生成

JDK 21を使い、`functions/office2md/` で実行します。

```sh
./mvnw package
java -Djava.awt.headless=true \
  --class-path "target/classes:$(cat target/license-runtime-classpath.txt)" \
  examples/OfficeAnnotatedImages.java \
  ../../docs/APIDocs/office2md/examples \
  ../../docs/APIDocs/office2md/examples/excel-annotated-images/source-image.png \
  --convert --format=word
```

`--format=word` でWordだけを生成します。形式指定を省略するとWord・PowerPointの両方を生成します。
`--convert` を省略すると入力ファイルと元PNGだけを生成します。
元PNGは `ExcelAnnotatedImages.java` で作成したオリジナルの架空画面を使います。
変換にはHTTP・Queueと共通の `OfficeMarkdownService` を使用します。
