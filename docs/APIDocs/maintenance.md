# HTML API資料の更新手順

- 実装のルート、認証、入力検証、戻り値を確認してから、該当するHTMLを更新します。同名パスでも、受付形式と戻り値は機能ごとに確認してください。
- 利用ガイド・直接Queueガイドも同時に更新します。資料末尾の最終確認日を更新してください。
- ページを追加する場合は、機能フォルダーへHTMLを置き、`assets/app.js` の `groups` にリンクと検索キーワードを追加します。
- 各ページに一意の `body[data-page]` を設定し、各見出しの `id` は既存リンクを壊さないよう維持します。
- 本文はHTMLに直接記述します。共通JavaScriptはUIのみで、本文の読み込み・外部取得は行いません。
- コード例の `<` と `&` はHTMLとしてエスケープします。実際の接続文字列やキーは書き込みません。
- デスクトップ・モバイルの表示、アコーディオン、検索、目次、コードコピー、相対リンクをブラウザーで確認します。

Java機能の仕様の基準は、各機能の `ConversionFunctions`、`PlaygroundFunctions`、`AppConfig`、変換処理とQueueリクエスト型です。Node.jsのMovie → AACでは、機能フォルダー内の次の実装と照合します。

| 対象 | 実装 |
| --- | --- |
| ルート・認証・非同期の有効化 | `src/index.js`、`src/config.js` |
| HTTP受付・レスポンス・ストリーム終了条件 | `src/handlers.js`、`src/admission.js` |
| Playground・公開設定 | `src/playground.js`、`resources/playground/` |
| AAC抽出・入力URLの取得先制限 | `src/ffmpeg.js`、`src/url.js` |
| SAS保存の契約・接続先検証 | `src/storage-request.js`、`src/blob.js` |
| Queue JSON・Storage別名・状態・再試行・結果取得 | `src/job-request.js`、`src/jobs.js`、`src/job-store.js`、`host.json` |

各ページの最終確認日を参照してください。資料更新後は相対リンクとアンカー、JSON・コマンド例を検査します。実装や依存を変更していない場合に、変換処理やAzureへの配置を再実行する必要はありません。
