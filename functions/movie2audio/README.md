# Movie → AAC（Movie2Audio）

動画の最初の音声トラックからAACを再エンコードせずに抽出し、`audio.m4a`（`audio/mp4`）として返すAzure Functionsです。Node.jsのFunctions v4モデルで実装し、FFmpeg・FFprobeを同梱しています。他のJava製変換機能とは別のFunction Appへ配置します。

入力には動画ストリームが必要で、最初の音声がAAC以外なら拒否します。既定の入出力上限は各100MiB、処理期限は180秒です。環境変数で変更できます。

## 利用できる方式

| 方式 | 入口・出力 |
| --- | --- |
| 同期HTTP | `/api/convert`・`/api/convert-url` でファイルまたはURLを受け付け、M4Aを返す |
| 同期SAS保存 | `/api/convert-to-blob` で指定Blobへ保存し、保存結果を返す |
| 非同期HTTP | `/api/jobs`・`/api/jobs-url` で受け付け、状態確認・結果取得APIを使う |
| 直接Queue | `movie2audio-jobs` にBlob参照JSONを送り、登録済みStorageへ保存する |

すべて共通の抽出処理を使います。非同期は `CONVERSION_STORAGE_CONNECTION_STRING` 設定時に有効です。URL入力と同期SAS保存は、それぞれ許可ホストを設定して有効にします。

HTTPは入力を一時ディスクへ保存し、抽出を完了したM4Aをストリームで返します。抽出途中から配信する方式ではありません。再生・保存・Azure Speechなどへの送信は利用側が行います。

## ドキュメント

| 知りたいこと | 資料 |
| --- | --- |
| 全体像・ローカル起動・設定・テスト・配置 | [利用・運用ガイド](docs/usage.md) |
| HTTPでファイル／URLを変換 | [ファイルAPI](../../docs/APIDocs/movie2audio/http.html) · [URL API](../../docs/APIDocs/movie2audio/url.html) |
| SAS URLを指定してBlobへ保存 | [Blob保存API](../../docs/APIDocs/movie2audio/storage.html) |
| 非同期受付・状態確認・結果取得 | [ジョブAPI](../../docs/APIDocs/movie2audio/jobs.html) |
| 外部システムからQueueへ直接依頼 | [接続・Producerガイド](docs/direct-queue.md) · [Queue API](../../docs/APIDocs/movie2audio/queue.html) |
| 上限・環境変数・エラー | [設定リファレンス](../../docs/APIDocs/movie2audio/settings.html) |
| 共通処理・実装の保守 | [開発者ガイド](../../docs/movie2audio.md) |
| ライセンス・同梱FFmpeg | [MIT](LICENSE) · [FFmpegのライセンス・ソース](third-party/ffmpeg/README.md) |

API資料全体は [Movie → AAC APIリファレンス](../../docs/APIDocs/movie2audio/index.html) から参照できます。
