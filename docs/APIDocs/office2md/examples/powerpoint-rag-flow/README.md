# RAG向け業務フローの実変換デモ

- [編集可能なPowerPoint](input.pptx)
- [実際のHTTP変換結果ZIP](result.zip)
- [検索対象にできるMarkdown本文](output/document.md)
- [図形・接続のJSON](output/report.json)
- [実行記録とハッシュ](run.json)

1枚目は購入申請の分岐・差戻し・再申請。2枚目はグループ内外の接続、双方向、始点矢印。3枚目は接続先IDのない矢印と独立した「承認」「至急」の文字です。文字の近さだけでは接続先や分岐条件を判定しません。

14本のコネクターのうち13本を保存データで解決し、1本は「接続関係不明」とします。画像を読み込まなくても、図形の文字と確認できた接続関係はMarkdown本文から取得できます。図形IDはスライドごとのIDです。検索チャンクにはスライドタイトルと出典を含め、接続関係と対応する項目を一緒に扱ってください。

このデモは変換結果を検証するもので、ベクトルインデックスの作成や検索精度の測定は行っていません。

入力の再生成（リポジトリルートから、Java21、ビルド済みの場合）：

```sh
mkdir -p functions/office2md/target/sample-tools
javac -encoding UTF-8 -cp 'functions/office2md/target/azure-functions/office2md-local/lib/*' \
  -d functions/office2md/target/sample-tools functions/office2md/examples/RagFlowSample.java
java -Djava.awt.headless=true \
  -cp 'functions/office2md/target/sample-tools:functions/office2md/target/azure-functions/office2md-local/lib/*' \
  RagFlowSample docs/APIDocs/office2md/examples/powerpoint-rag-flow/input.pptx
```

Functionsホストを起動し、[共通の撮影手順](../README.md)で `OFFICE_EXAMPLE_CASE=powerpoint-rag-flow` を指定すると再変換・撮影できます。入力はこのリポジトリのオリジナル資料で、MITライセンスです。
