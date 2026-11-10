# HTML API資料の更新手順

- 実装のルート、認証、入力検証、戻り値を確認してから、該当するHTMLを更新します。同名パスでも、受付形式と戻り値は機能ごとに確認してください。
- 利用ガイド・直接Queueガイド・開発者ガイドも同時に確認し、変更する仕様に関係する説明と図表を更新します。新しい説明を追記した場合も古い断定が残らないよう確認し、変更した資料末尾の照合日を更新してください。実測日は再実行した場合だけ変更します。
- ページを追加する場合は、機能フォルダーへHTMLを置き、`assets/app.js` の `groups` にリンクと検索キーワードを追加します。
- 各ページに一意の `body[data-page]` を設定し、各見出しの `id` は既存リンクを壊さないよう維持します。
- 本文はHTMLに直接記述します。共通JavaScriptはUIのみで、本文の読み込み・外部取得は行いません。
- コード例の `<` と `&` はHTMLとしてエスケープします。実際の接続文字列やキーは書き込みません。
- デスクトップ・モバイルの表示、アコーディオン、検索、目次、コードコピー、相対リンクをブラウザーで確認します。

Java機能の仕様の基準は、各機能の `ConversionFunctions`、`PlaygroundFunctions`、`AppConfig`、変換処理とQueueリクエスト型です。Node.jsのMovie → Audioでは、機能フォルダー内の次の実装と照合します。

| 対象 | 実装 |
| --- | --- |
| ルート・認証・非同期の有効化 | `src/index.js`、`src/config.js`、`src/storage-settings.js`、`scripts/configure_azure_async.py` |
| HTTP受付・レスポンス・ストリーム終了条件 | `src/handlers.js`、`src/admission.js` |
| Playground・公開設定 | `src/playground.js`、`resources/playground/` |
| AACコピー・M4A/WAV変換・入力URLの取得先制限 | `src/ffmpeg.js`、`src/audio-options.js`、`src/url.js` |
| SAS保存の契約・接続先検証 | `src/storage-request.js`、`src/blob.js` |
| Queue JSON・Storage別名・状態・再試行・結果取得 | `src/job-request.js`、`src/jobs.js`、`src/job-store.js`、`host.json` |

Node.jsのHTML → PowerPointは同期HTTP・CLIのみです。MovieのQueue契約や起動方法を転用せず、次の実装と照合します。

| 対象 | 実装 |
| --- | --- |
| ルート・認証・出力形式・上限 | `src/index.js`、`src/handlers.js`、`src/config.js` |
| HTML/ZIP・素材・外部参照の検査 | `src/input.js`、`src/image-sources.js` |
| レイアウト・フォント・SVG・画像 | `src/engine.js`、`src/fonts.js`、`src/text-layout.js`、`src/svg-native.js`、`src/pptx.js` |
| CSSアニメーション・複数指定・固定とreport | `src/animations.js`、`src/pptx-animations.js`、`src/engine.js` |
| HTTPとCLIの共通処理・実行環境 | `src/service.js`、`src/browser.js`、`scripts/convert.mjs`、`scripts/package.mjs` |
| 実例数・入力・期待する内容 | `examples/catalog.json`、`examples/`、`test/` |

共通の紹介も仕様変更の影響を受けます。対応形式・入口・設定方式を変えたら、ルートREADME、[資料カタログ](README.md)、[全体構成](../architecture.md)、[開発手順](../development.md)、[CONTRIBUTING](../../CONTRIBUTING.md)、[convertX2Xスキル](../../.agents/skills/convertx2x/SKILL.md)を確認します。スキルに全設定表を複製せず、維持する資料を参照させます。過去の日付付き検証は現在の再検証と区別します。

Queue資料はメッセージJSONだけでなく、転送時のBase64を1回だけ適用する手順・結果通知の復号も確認します。非同期の有効条件は接続文字列とManaged Identityの両方、Queueバインドの派生設定、リソース事前作成、保持・通知のTimer設定を含めます。reportの構造が変わる場合は、合成画像の `sourceImages[].ocr` のように参照場所が変わる説明やサンプルも確認します。

各ページの資料照合日・実測日を参照してください。資料更新後は相対リンクとアンカー、JSON・コマンド例を検査します。実装や依存を変更していない場合に、変換処理やAzureへの配置を再実行する必要はありません。

GitHub Pages用の相対ファイルリンクと配布準備は、リポジトリルートから次で確認できます。出力先には作業用の新しいディレクトリを使ってください。このコマンドは指定先を作り直します。アンカーとブラウザー表示は別途確認します。

```sh
python3 .github/scripts/build_apidocs.py --repository sunu-py-jp/convertX2X --ref main --output /tmp/convertx2x-apidocs-check
```

## 実際の変換例を更新する

各機能の `examples.html` に説明を、同じフォルダーの `examples/` に入力ファイル・実際のAPI出力・スクリーンショット・再実行用のコードと記録を置きます。閲覧だけならAPIサーバーは不要です。

- 変換例を更新する場合は、対象のローカルFunction Appに入力を送り、返されたファイルを保存します。Markdown・画像・音声を手作業で修正したものを変換結果として掲載しません。
- 入力と出力のハッシュ、リクエスト、HTTPステータス、実行環境、所要時間を記録します。所要時間はそのローカル実行の記録として扱い、Azureの性能保証や計測結果として紹介しません。
- スクリーンショットは実際のPlaygroundや保存済み成果物の表示を撮影します。スクロール領域の展開や部分撮影を行った場合は説明し、APIが生成した画像ファイルと画面のスクリーンショットを区別します。
- 元ファイル・成果物・警告を残し、対応していない要素や見た目の変化も説明します。画像リンクは元サイズで開けるようにし、音声は再生とダウンロードを確認します。
- サンプルは再配布できる自作素材を優先し、実データ・接続文字列・キー・有効なSASを含めません。各機能の再実行手順は「実際の変換例」ページから参照できます。

共通メニューの「実際の変換例」、トップページからのリンク、モバイル表示、`file://` での相対リンクも確認してください。既存の成果物を再撮影するだけの場合でも、画像や文章の差し替えで変換内容を変更しないようにします。
