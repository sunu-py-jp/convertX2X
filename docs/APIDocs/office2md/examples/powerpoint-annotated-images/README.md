# PowerPointの画像同士・表・図形・テキストボックスを合成する例

架空の申請画面と表に、別の画像・編集可能な赤枠・テキストボックスを重ねた入力です。

- 赤枠は画像内、ピンクのテキストボックスは画像の右下へはみ出します。
- はみ出した注釈全体を表示するよう画像サイズを広げ、拡張領域の背景だけ透明にします。
- 画像から離れた緑のメモは、別の参考PNGとMarkdownの文字として残します。
- 元画像・赤枠・テキストボックスを単独画像として重複出力しません。
- 画像だけを重ねた場合も、元の前後関係で1枚のPNGへ合成します。
- 表に画像・図形・テキストボックスが重なる場合は表を含めて合成し、表の値はMarkdownの表にも残します。

3スライドで比較できます。

| スライド | 入力と確認点 |
| --- | --- |
| 1 | 画面画像、赤枠、テキストボックス、離れた緑の図形。はみ出す部分を描画し、余白は透明 |
| 2 | 画面全体の画像に、状態欄・送信ボタンの拡大画像を重ねた例。図形がなくても合成 |
| 3 | 3列4行の表に、赤枠・テキストボックス・補足画面画像を重ねた例。Markdownの表と合成PNGを両方出力 |

重なるテキストボックスは合成PNGへ描画し、その文字は通常本文へ1回だけ残しています。合成対象を除いた緑の図形は、従来のスライド寸法の参考PNGへ残します。見た目の重なりを理由に、業務フローの接続関係や矢印の方向を追加することはありません。

## 実変換結果

入力 `62,153` bytes、ZIP `165,170` bytes。
合成PNG3枚と独立図形PNG1枚、`document.md`、`report.json` を出力しました。
変換時間 `426` ms は共通コアだけの実測で、Functions起動やHTTP通信の時間は含みません。

今回の3スライド版はHTTP・Queueと同じ `OfficeMarkdownService` を直接実行しました。記録は `run.json` にあります。

この実行の警告は0件です。通常のテキストボックスの文字は本文に1回だけ残し、合成PNGにも描画しています。

画像モードは `ignore` です。実AzureのOCR通信は行っていません。
OCRモードでも対象は合成PNGではなく元画像の可視領域です。

| ファイル | 内容 |
| --- | --- |
| `input.pptx` | 編集可能な元ファイル |
| `source-image.png` | 注釈を付ける前の架空画面 |
| `result.zip` | 共通変換処理が生成したZIP |
| `result/document.md` | 実際のMarkdown全文 |
| `result/report.json` | 合成画像と元画像・図形の対応、警告 |
| `result/images/image-0001.png` | 赤枠・テキストボックスと画像を合成した透過PNG |
| `result/images/image-0002.png` | 画面全体とその拡大画像を重ねたPNG |
| `result/images/image-0003.png` | 表・画像・赤枠・テキストボックスを合成したPNG |
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
  --convert --format=powerpoint
```

`--format=powerpoint` を省略するとWord・PowerPointの両方を生成します。`--convert` を省略すると入力ファイルと元PNGだけを生成します。
元PNGは `ExcelAnnotatedImages.java` で作成したオリジナルの架空画面を使います。
変換にはHTTP・Queueと共通の `OfficeMarkdownService` を使用します。
