# HTML → PowerPoint（HTML2PPTX）

HTMLの見た目を基に、編集可能な文字・表・図形を持つPowerPointを生成するAzure Functionsです。基本的なCSSアニメーションはPowerPointの動きへ変換し、対応できない動きは固定状態と警告を出力します。SVGの体制図・構成図も図形・線・文字へ変換し、元のPNG・JPEGは画像として保持します。

Node.js 24で実装し、Azure Functions Flex Consumption・4GB向けにChromiumと必要なライブラリを同梱します。同期HTTPとCLIに対応します。非同期HTTP・直接Queueは未実装です。

| 知りたいこと | 資料 |
| --- | --- |
| 入力形式・起動・設定・配置 | [利用・運用ガイド](docs/usage.md) |
| HTTP API・編集可能な要素 | [APIリファレンス](../../docs/APIDocs/html2pptx/index.html) |
| 企業発表・決算・複雑な体制図など | [実際の変換例](../../docs/APIDocs/html2pptx/examples.html) |
| 検証用の入力HTML | [サンプル一覧](examples/catalog.json) |
| ライセンス | [MIT](LICENSE)・[第三者の通知](NOTICE.md) |

JavaScriptや外部URLの取得を伴うHTMLは受け付けません。任意のCSSの完全再現は保証せず、未対応表現はエラーやreportの警告で確認できます。

日本語フォントはNoto Sans JPを同梱し、HTML・SVGの文字を同じフォントへ統一して変換します。PPTXへのフォント埋め込みは行いません。Azureの検証環境では変換の成功とは別にHTTP入口の断続的な503を確認しています。[制約・Azure検証状況](docs/usage.md#azureで実行する)を確認してください。
