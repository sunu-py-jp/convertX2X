# md2pdf 変換サンプル

[basic/input.md](basic/input.md) は、日本語の見出し・段落・装飾・表・リスト・引用・
コードと改ページを確認するサンプルです。

- [生成PDF](basic/document.pdf)
- [変換report](basic/report.json)

```shell
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @samples/basic/input.md \
  'http://localhost:7075/api/convert?filename=input.md' \
  --output document.pdf
```

画像を相対パスで参照する場合はMarkdownと画像をZIPにまとめます。
詳しくは[利用ガイド](../docs/usage.md#画像を含める)を参照してください。
