# WordのRAG向け業務フロー

自作の編集可能なDOCXを、実際のローカルFunctions HTTP APIとPlaygroundで変換した記録です。

- `input.docx`：DrawingMLのグループ、4個の表示図形、5本の接続線、前後の段落と表。
- `result.zip`：HTTPから返った変換結果。
- `output/document.md`：図形文字・4件の確定した接続・1件の接続不明・確認用画像。
- `output/report.json`：ノード、接続、描画内の座標、段落位置、警告、アセット情報。
- `screenshots/`：同じ出力を表示したPlaygroundのスクリーンショット。
- `run.json`：実行環境、HTTP結果、入力・出力・スクリーンショットのSHA-256。

入力には、非表示の図形と取消線の検証用文字列を含めています。それらは出力本文・JSON・確認用画像から除外します。最下段の独立した矢印には接続先を保存しておらず、近くの図形へ推測で接続しません。画像を読まなくても、本文に図形の文字と確定した関係が残ります。

Wordのページ全体を画像化する例ではありません。同じ描画グループを一つの確認用画像にし、本文は段落順で保持します。形や文字の描画は対応範囲の近似です。

生成コードは [WordRagSample.java](../../../../../functions/office2md/examples/WordRagSample.java) です。Java 21とOffice2MDの依存JARで再生成できます。入力・生成コードは本リポジトリと同じMITライセンスです。

```sh
# リポジトリのルートから。先にfunctions/office2mdで ./mvnw package を実行します。
mkdir -p functions/office2md/target/word-rag-generator
javac -cp 'functions/office2md/target/azure-functions/office2md-local/lib/*' \
  -d functions/office2md/target/word-rag-generator functions/office2md/examples/WordRagSample.java
java -cp 'functions/office2md/target/word-rag-generator:functions/office2md/target/azure-functions/office2md-local/lib/*' \
  WordRagSample docs/APIDocs/office2md/examples/word-rag-flow/input.docx
OFFICE_EXAMPLE_CASE=word-rag-flow node docs/APIDocs/office2md/examples/capture.cjs
```
