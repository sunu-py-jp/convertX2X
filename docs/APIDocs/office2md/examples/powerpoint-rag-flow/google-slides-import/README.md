# Google スライドへの取り込みと再変換

元の [PowerPoint](../input.pptx) を Google スライドに取り込み、Google スライドから PPTX として書き出した後、ローカルの Office2MD `POST /api/convert?filename=exported.pptx` で実際に再変換した結果です。

- [Google スライドから書き出した PPTX](exported.pptx)
- [Office2MD の変換結果 ZIP](result.zip)
- [変換後の Markdown](output/document.md)
- [図形・接続関係の JSON](output/report.json)
- [変換後のスライド画像](output/images/diagram-0001.png)（[2枚目](output/images/diagram-0002.png)、[3枚目](output/images/diagram-0003.png)）
- [実行記録と比較結果](run.json)

再変換後も、3スライドの図形16個、線14本、図形の文字、確定した接続関係、接続状態、9分割位置は元データと一致しました。接続関係を確定できない線も1本のままです。

差分は、Google スライドの再書き出しによる図形 ID の再採番と、3枚目の表の Markdown に空のヘッダー行が1行追加されたことです。画像のピクセル一致は確認対象にしていません。Markdown から図形 ID と追加された空ヘッダー行を除くと、元の変換結果と一致します。
