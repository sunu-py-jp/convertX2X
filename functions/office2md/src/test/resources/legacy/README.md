# 旧Word形式（.doc）のテスト用ファイル

`word-sample.doc` は、このリポジトリのために書いた `word-sample.rtf` を macOS 標準の `textutil` で
Word 97-2003形式へ変換したものです。外部の資料やテンプレートは含みません。

```bash
textutil -convert doc word-sample.rtf -stdout > word-sample.doc
```

含まれる要素は、太字だけの段落、太字と取消線を含む段落、段落内改行、3行2列の表、改ページです。
`textutil` は見出しスタイル・箇条書き・隠し文字・フィールド・画像を `.doc` へ書き出さないため、
それらの解釈は `LegacyWordInlineTest` と `LegacyWordMarkdownConverterTest` の単体テストで確認します。
