---
name: convertx2x
description: convertX2Xの変換APIを利用・変更する際に仕様と資料を揃え、html2pptx向けのHTMLスライドを生成する。このリポジトリまたはconvertX2Xを使う依頼に適用し、一般のOffice編集や別プロジェクトのスライド機能には適用しない。
---

# convertX2X

このスキルはリポジトリ内で使う。以下の相対リンクはこのファイルを起点とし、別の場所へSKILL.mdだけをコピーする運用は想定しない。対象機能の資料だけを読み、全機能の制限を一律に適用しない。

## 機能と参照先

公開リクエスト・戻り値は各API資料、起動・設定は各利用ガイドを参照する。実装と食い違う場合は該当する入口・設定・変換処理を確認して資料を修正する。機能名や同じAPIパスから契約を推測しない。

| 機能 | 用途と注意 | API / 利用ガイド |
| --- | --- | --- |
| ppt-pdf-to-images | PPTX・PPT・PDFのページ画像 | [API](../../../docs/APIDocs/ppt-pdf-to-images/index.html) / [利用](../../../functions/ppt-pdf-to-images/docs/usage.md) |
| office2md | Officeの本文・表・図形情報・画像参照。任意で元画像をOCR | [API](../../../docs/APIDocs/office2md/index.html) / [利用](../../../functions/office2md/docs/usage.md) |
| office2pdf | Word/Excelは内容を再配置、PowerPointはスライドを描画して文字検索層を付加 | [API](../../../docs/APIDocs/office2pdf/index.html) / [利用](../../../functions/office2pdf/docs/usage.md) |
| excel2img | Excelの配置を保ち、縮小と縦横分割でPNG/PDFへ | [API](../../../docs/APIDocs/excel2img/index.html) / [利用](../../../functions/excel2img/docs/usage.md) |
| md2pdf | Markdownと同梱画像から検索可能なPDF | [API](../../../docs/APIDocs/md2pdf/index.html) / [利用](../../../functions/md2pdf/docs/usage.md) |
| movie2audio | 既定はAACコピーのM4A、明示的なtranscodeでM4A/WAV | [API](../../../docs/APIDocs/movie2audio/index.html) / [利用](../../../functions/movie2audio/docs/usage.md) |
| html2pptx | HTML/CSSから編集可能なPPTX。同期HTTP・CLIのみ | [API](../../../docs/APIDocs/html2pptx/index.html) / [利用](../../../functions/html2pptx/docs/usage.md) |

## APIを使うとき

- HTTPの生バイト列・JSON・ZIP、単体成果物・成果物ZIPは機能とオプションで異なる。選んだAPIの例に従い、JSON/Base64の応答だと一律に仮定しない。
- HTML2PPTX以外の6機能は非同期にも対応する。接続文字列またはManaged Identity用の制御Storage設定を使い、起動前にQueueバインドの設定を導出する。詳細は[共通の配置手順](../../../docs/development.md)。HTML2PPTXへStorageを設定してもQueue機能は有効にならない。
- 直接Queueには入力Blob参照のJSONを送り、Base64を1回だけ適用する。SDKの自動エンコードと手動処理を重ねない。機能ごとの `docs/direct-queue.md` でversion・options・通知契約を確認する。Queue本文へファイル本体やSAS・接続文字列を入れない。
- Office2MDで画像と重なる描画要素を合成した場合、Markdownが指す合成PNGを使う。元画像が別成果物として残るとは限らない。OCRの処理状態・モデル・エラーコードは、合成元では `blocks[].sourceImages[].ocr`、独立画像では `blocks[].ocr` を参照する。認識した文字列はreport内にはなく、`document.md` の画像参照に続く「画像内の文字（OCR）:」以下に出力される。
- Office2MDとOffice2PDFは旧形式の `.doc`（Word 97-2003）も受け付け、Office2MDは `.ppt` も受け付ける。拡張子と中身が一致しないファイルは415になるため、改名で形式を合わせない。Office2MDの `.doc` は脚注・テキストボックス・コメント・図形を出力せず、`.ppt` の接続関係は線端の接触からの推定である点を、`.docx` / `.pptx` の結果と区別する。
- Excelの数式は保存済み値を使う。再計算や外部参照の取得を追加して不足を補わない。Office2PDFの旧 `.ppt` では非表示スライドも出力される制限を、PPTXの除外仕様と区別する。

## HTML2PPTX向けのHTMLを生成するとき

専用の [html2pptx-authoringスキル](../html2pptx-authoring/SKILL.md) を使う。入力条件、不要なセカンダリを減らすデザイン方針、画像・SVG・アニメーション、実変換の確認を扱う。この専用スキルはフォルダ単位で別プロジェクトへ渡せる。

仕様変更時は [利用ガイド](../../../functions/html2pptx/docs/usage.md) と専用スキルのreferencesを照合する。専用スキルは外部のデザインスキルをインストールせずに使え、Web用の実行コードやフォント配信を入力へ持ち込まない。

## 実装・資料を変更するとき

[CONTRIBUTING](../../../CONTRIBUTING.md)、対象の[開発者ガイド](../../../docs/README.md)、[API資料の更新手順](../../../docs/APIDocs/maintenance.md)を使う。対応形式・設定・report・アニメーション等を変えたら、利用ガイド、APIDocs、開発者ガイド、共通カタログとこのスキルの関連箇所を一緒に見直す。設定表やJSONスキーマをスキルへ複製せず、参照先を維持する。

実変換の例は実際の出力と警告を保存し、手直しした成果物を変換結果として提示しない。資料だけの変更はリンク・アンカー・例と実装の整合性を確認する。過去の日付付き実行記録を、今回実行した検証として扱わない。
