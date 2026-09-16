# 利用・開発ガイド

APIの呼び出し方は [HTML APIリファレンス](APIDocs/index.html) を参照してください。起動・設定・配置や、実装を変更するための資料は以下にまとめています。

## 機能別の資料

| 機能 | 起動・設定・検証・配置 | 直接Queue | 実装・拡張 |
| --- | --- | --- | --- |
| PowerPoint / PDF → 画像 | [利用ガイド](../functions/ppt-pdf-to-images/docs/usage.md) | [依頼形式](../functions/ppt-pdf-to-images/docs/direct-queue.md) | [開発者ガイド](ppt-pdf-to-images.md) |
| Office → Markdown | [利用ガイド](../functions/office2md/docs/usage.md) | [依頼形式](../functions/office2md/docs/direct-queue.md) | [開発者ガイド](office2md.md) |
| Movie → Audio | [利用ガイド](../functions/movie2audio/docs/usage.md) | [依頼形式](../functions/movie2audio/docs/direct-queue.md) | [開発者ガイド](movie2audio.md) |

## 共通の資料

- [全体構成](architecture.md)：独立したFunction App、HTTPとQueueの関係、機能ごとの違い
- [開発環境と作業手順](development.md)：必要なツール、ローカル実行、テスト、Azureへの配置
- [機能追加・変更のルール](../CONTRIBUTING.md)
- [API資料の表示方法](APIDocs/README.md)と[更新手順](APIDocs/maintenance.md)

[Officeの初期設計メモ](designs/office2md.md)は検討経緯、[HTML → PowerPointの設計案](designs/html2pptx.md)は未実装の参考資料です。現在の仕様は上記のAPI資料・利用ガイドを基準にしてください。
