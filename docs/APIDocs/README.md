# HTML APIリファレンス

[index.html](index.html) をブラウザーで開くと、左メニューから各APIの仕様・リクエスト例を確認できます。静的HTML・CSS・JavaScriptのみで、ビルドやAPIサーバーの起動は不要です。

| 機能 | 資料 |
| --- | --- |
| PowerPoint / PDF → 画像 | [概要・同期HTTP・非同期HTTP・直接Queue](ppt-pdf-to-images/index.html) |
| Office → Markdown | [概要・同期HTTP・非同期HTTP・直接Queue](office2md/index.html) |
| Movie → AAC | [概要](movie2audio/index.html)・[非同期HTTP](movie2audio/jobs.html)・[直接Queue](movie2audio/queue.html)・[SAS保存](movie2audio/storage.html) |

`file://` で直接表示するか、リポジトリルートから静的サーバーを起動します。

```sh
python3 -m http.server 7080 --bind 127.0.0.1
```

[API資料を開く](http://localhost:7080/docs/APIDocs/)。このサーバーは資料の表示用です。

資料を変更するときは [更新手順](maintenance.md)、実装を変更するときは [利用・開発ガイド](../README.md) を参照してください。
