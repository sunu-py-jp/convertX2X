# Excel → Markdownの実装

[マニュアルの入口](README.md) · [共通の作業手順](development.md)

`functions/excel2md` の変換処理を修正・拡張するための実装案内です。Java 21 と Apache POI を使う独立した Azure Functions アプリで、PowerPoint/PDF 変換機能の配置や実行環境を参照しません。

利用・配置手順は [機能README](../functions/excel2md/README.md)、API一覧は [HTTP API](../functions/excel2md/README.md#http-api)、環境変数は [上限](../functions/excel2md/README.md#上限) と [直接Queue投入](../functions/excel2md/docs/direct-queue.md) を参照してください。[初期設計メモ](designs/excel2md.md) は判断理由と確認観点を記録した参考資料です。

## 責務とソース

Javaのルートパッケージは `com.convertx2x.excel2md` です。`conversion` と `drawing` は Azure SDK に依存せず、呼び出し元から渡されたファイルだけを処理します。

| 責務 | 主な実装 |
| --- | --- |
| 入力検査、ブック寿命、処理順、Markdownの組み立て | [ExcelMarkdownService](../functions/excel2md/src/main/java/com/convertx2x/excel2md/conversion/ExcelMarkdownService.java) |
| 表示値、部分太字・取消線、数式キャッシュ、リンク | [CellMarkdown](../functions/excel2md/src/main/java/com/convertx2x/excel2md/conversion/CellMarkdown.java)、[Markdown](../functions/excel2md/src/main/java/com/convertx2x/excel2md/conversion/Markdown.java) |
| 直接罫線による表検出、結合範囲の参照 | [BorderTables](../functions/excel2md/src/main/java/com/convertx2x/excel2md/conversion/BorderTables.java)、[MergedRanges](../functions/excel2md/src/main/java/com/convertx2x/excel2md/conversion/MergedRanges.java) |
| 画像・図形の抽出、接続・重なりの判定、シート座標 | [DrawingExtractor](../functions/excel2md/src/main/java/com/convertx2x/excel2md/drawing/DrawingExtractor.java)、[SheetCoordinates](../functions/excel2md/src/main/java/com/convertx2x/excel2md/drawing/SheetCoordinates.java) |
| 図形の座標変換、画像合成、Java2D描画 | [DrawingScene](../functions/excel2md/src/main/java/com/convertx2x/excel2md/drawing/DrawingScene.java) |
| 図形文字の書式抽出・除外と、行の計測・配置 | [DrawingText](../functions/excel2md/src/main/java/com/convertx2x/excel2md/drawing/DrawingText.java)、[DrawingTextLayout](../functions/excel2md/src/main/java/com/convertx2x/excel2md/drawing/DrawingTextLayout.java) |
| 一時ファイル、上限、重複画像、report、ZIP | [ConversionWorkspace](../functions/excel2md/src/main/java/com/convertx2x/excel2md/conversion/ConversionWorkspace.java)、[ConversionResult](../functions/excel2md/src/main/java/com/convertx2x/excel2md/conversion/ConversionResult.java) |
| HTTP/Queueの入口、設定、サービスの共有 | [ConversionFunctions](../functions/excel2md/src/main/java/com/convertx2x/excel2md/ConversionFunctions.java)、[AppConfig](../functions/excel2md/src/main/java/com/convertx2x/excel2md/AppConfig.java)、[RuntimeServices](../functions/excel2md/src/main/java/com/convertx2x/excel2md/RuntimeServices.java) |
| ジョブ状態、重複配送、Blobへの入出力 | [AzureJobService](../functions/excel2md/src/main/java/com/convertx2x/excel2md/jobs/AzureJobService.java)、[AzureJobStore](../functions/excel2md/src/main/java/com/convertx2x/excel2md/jobs/AzureJobStore.java)、[JobStore](../functions/excel2md/src/main/java/com/convertx2x/excel2md/jobs/JobStore.java) |
| 静的UIの配信と安全なプレビュー | [PlaygroundFunctions](../functions/excel2md/src/main/java/com/convertx2x/excel2md/PlaygroundFunctions.java)、[playground/app.js](../functions/excel2md/src/main/resources/playground/app.js) |

## 変換パイプライン

1. `ExcelMarkdownService.validate` が拡張子・実データの形式・入力サイズを検査する。入力を変換専用の一時ディレクトリへ保存し、マクロ・暗号化などの対象外形式を拒否する。
2. `WorkbookFactory.create(file, null, true)` で読み取り専用のWorkbookを開く。シート数と、書式だけのセルも含む実体セル数を検査する。POIのWorkbookモデルはメモリーに展開される。
3. 表示シートをブック順に処理する。`BorderTables` は元の罫線から表を検出し、`CellMarkdown` は表示対象セルの文字列を生成する。非表示行・列と、結合アンカー以外のセルを除外する。
4. `DrawingExtractor` が表示対象の埋め込み画像と図形を読み、画像ファイルと配置用の `DrawingBlock` を生成する。
5. 本文・表・図形を行、列、同位置での優先順に並べる。表に重なる図形は、最後に重なる表の直後へ置く。内容があるシートだけH1を付け、H2以降や意味上の読む順番は推測しない。
6. `document.md` と `report.json` を確定して入力一時ファイルを削除する。戻り値の `ConversionResult` が成果物の寿命を引き継ぐ。

`ConversionResult` は `AutoCloseable` です。`files()` のファイルを読み取る・コピーする・Blobへ送る処理を終えてから閉じます。閉じた後の一時パスを保持しないでください。異常終了時はサービス側で一時ディレクトリを清掃します。

同じJavaプロセスの変換は静的 `Semaphore` で1件ずつ実行します。これは複数インスタンスをまたぐロックではなく、HTTPレスポンスのZIP生成やBlobアップロードまで全処理を直列化するものでもありません。Functionsホスト側の同時実行設定と合わせて容量を評価します。

## 表・本文・数式で維持するルール

`BorderTables` はセルの上下左右の罫線を境界集合へ変換します。隣接セルの片側にだけ線がある場合も利用し、結合セルを一つの区画として扱います。複数の閉じた区画が連結し、外接範囲を過不足なく埋め、外枠が閉じている候補を表にします。図形の線、画面のグリッド線、テーブルスタイル、条件付き書式を罫線として評価しません。

検出できない値は本文へ残します。`BORDER_NOT_TABLE` はシートの罫線範囲に対する診断であり、未採用の囲み枠すべてに個別の警告を生成するものではありません。表のヘッダー判定はサービス側にあり、検出範囲と有効なExcelテーブル定義が一致する場合だけ先頭行をヘッダーにします。それ以外は空ヘッダーを追加し、元の全行を保持します。

本文と表は同じ `CellMarkdown` を通ります。取消線を除去してから太字・リンクを生成し、全削除されたリンクをURL補完で復活させません。数式結果にもセル全体の取消線を適用します。元の文字列と表示形式の位置対応が失われる場合は、取消線を安全に除去できる文字列を優先します。削除内容や未検証のURLを診断メッセージへ追加しないでください。

**すべての数式を未評価のまま扱うことが、外部参照を取得しないための制約です。** `DataFormatter` は日本語ロケール・キャッシュ利用で構成し、`FormulaEvaluator` を渡しません。

- キャッシュがあれば、古い値やエラーもそのまま表示する。キャッシュ不足を再計算で補わない。
- `.xlsx` のキャッシュ欠落時はエスケープした式を残し、`FORMULA_CACHE_MISSING` を記録する。`.xls` では欠落を確実に区別できない場合がある。
- `IMPORTRANGE`、`IMPORTXML`、`WEBSERVICE` など、未知・入れ子を含め関数名を問わず実行しない。関数名の禁止リストだけに安全性を依存させない。
- 式全体が `IMAGE(...)` の場合は未対応表示にする。入れ子の式も評価せず、外部画像参照のMarkdownを生成しない。
- `HYPERLINK` は確定する文字列リテラルだけを構文解析する。通常のセル・図形リンクもURI文字列として扱い、変換中はアクセスしない。

外部ブック・データ接続を更新する処理や、数式結果を取得するHTTPクライアントを変換コアへ追加しないでください。画像はブックに埋め込まれたバイト列だけを扱います。元ファイルをExcel等で開いた際の再計算・通信は、そのアプリの動作であり、この変換で元数式を無効化しているわけではありません。

## 画像・図形・図形内文字

`DrawingExtractor` の形式別分岐がDrawingML（`.xlsx`）またはEscher（`.xls`）を読み、`DrawingScene.Item` へ渡します。通常のPNG/JPEGは原本を抽出し、その他形式は添付扱いにします。左上アンカーの行・列が非表示の場合も除外します。位置不明はシート末尾へ送り、診断を残します。

明示グループ、解決できる接続ID、描画範囲の重なりから合成単位を決めます。図形と一緒に描画するPNG/JPEGは同じキャンバスへ配置し、単独画像ブロックとして二重に出しません。背景のセル文字や罫線は描きません。単独で抽出する画像は元データなので、切り抜き前の領域も含みます。

図形文字は次の3段階で扱います。

| 段階 | 変更時の注意 |
| --- | --- |
| `DrawingText.readContent` | 取消線の実効書式を先に判定する。残すRunの色・フォント・下線・字間、Paragraphの配置・余白・間隔、Contentの上下配置・折返し・縮小率・文字回転を解決する |
| `DrawingTextLayout` | フォントを使って行を計測し、明示改行・空段落・段落別配置を保つ。保存済み縮小率を上限に、必要なら枠へ収まるまで追加縮小する |
| `DrawingScene` | 図形内余白と、図形・グループの座標変換を適用する。幾何形状と文字の回転・反転を混同しない |

座標・文字サイズ・字間は内部ではポイント単位、Runの色は透明度を含む `Color` です。行間にはポイント指定と割合指定があり、同じ値として扱わないでください。通常の文字色や配置に一律の近似警告を付けず、解釈できない指定だけを `Content.approximations` へ追加します。

実際のRunを表示できる利用可能な元フォントを優先します。不足時は同梱のNoto Sans/Serif・Regular/Boldへ置換します。[フォントの出典・ライセンス](../functions/excel2md/src/main/resources/fonts/noto/README.md) と4フォントを配布物に含めてください。Markdown本文の表示フォントは閲覧側が決めます。

Excelとは異なる組版なので、フォント置換後の字幅・改行位置の完全一致は保証しません。縦書きの組版、WordArt、箇条書き記号、特殊な文字効果、文字だけを正立させる指定、文字に合わせた図形枠の拡張は近似または未対応です。`vert270` は横書きの文字枠を270度回転して扱います。図形内リンクはPNG直後のMarkdownリンクになり、画像内のクリック領域にはなりません。

描画バッファは画素数を検査してから確保し、1枚ずつ保存・解放します。画像配置数と図形数は別の上限です。文字レイアウトにも図形ごとの文字数・Run数・段落数・縮小試算を含む行数の固定上限があり、超過は `DRAWING_TEXT_LIMIT` で失敗させます。数値は [上限の説明](../functions/excel2md/README.md#上限) と実装を参照してください。

## 成果物・HTTP・Queueの境界

`ConversionWorkspace` が出力ファイル名、SHA-256とバイト比較による同一カテゴリ内の重複排除、件数・バイト数の上限、診断を管理します。入力由来のシート名・画像名を出力パスに使いません。上限超過を警告へ落として途中成果物を成功扱いにする変更は避けてください。

`report.json` の `blocks` はシート・範囲・Markdown行との対応を持ち、表には元の行列番号・ヘッダー判定・結合範囲を付けます。`assets` はファイルのパス・MIME・サイズ・SHA-256です。画像の加工・図形IDなどは診断へ記録されるものもあり、専用の構造化フィールドが常に存在する前提で読み取らないでください。レポート形式を変える場合はUIとQueueの成果物取得も確認します。

- 同期HTTPは `ExcelMarkdownService.convert` の結果を `ConversionResult.zipBytes()` でZIP化する。最終レスポンスは上限付きのメモリーバッファで、HTTPストリーミングではない。
- HTTPからの非同期受付は入力と状態を保存してからQueueへ送る。直接Queueも同じ `AzureJobService.process` と変換コアへ入る。
- QueueはBlobリースで同じジョブの配送を直列化し、正規化した依頼が一致するか確認する。完了済みジョブを別の内容で上書きしない。
- `AzureJobStore` は試行ごとに成果物を個別Blobへ保存する。すべて保存できた試行だけを成功状態へ公開し、保存途中のBlobを結果一覧へ混ぜない。ZIPは取得要求時に作る。
- 入力はダウンロード中、完成成果物は保存時のETagに対して検査する。再試行をまたいで同じ入力Blobを差し替えてよいという意味ではない。

Queue JSONは [ConversionJobRequest](../functions/excel2md/src/main/java/com/convertx2x/excel2md/jobs/ConversionJobRequest.java) が検証し、Storageは [BlobStorageProfiles](../functions/excel2md/src/main/java/com/convertx2x/excel2md/jobs/BlobStorageProfiles.java) の入力用・出力用登録名で解決します。依頼側から任意URLや資格情報を受け取りません。登録名の分離とStorage側の実権限は別です。HTTPのホストキーもStorageの資格情報とは異なります。プロトコル・保存先・再送手順は [直接Queue投入](../functions/excel2md/docs/direct-queue.md) に集約しています。

`RuntimeServices` はコアを共有し、非同期サービスを必要時に生成します。Storage設定の有無だけでなく、FunctionsホストがJava起動前に読むQueue接続別名・無効化フラグも必要です。[起動・設定スクリプト](../functions/excel2md/scripts/async_settings.py) の導出処理と [設定手順](../functions/excel2md/README.md#非同期の設定と直接queue投入) を合わせて変更してください。

## 変更箇所と対応テスト

新しい挙動は入口ごとに実装せず、責務を持つコアへ追加します。既存のXLSX/XLS共通ケース、元値の保持、取消線・外部取得の禁止、上限と清掃を確認してからHTTP/Queueへ通します。

| 変更したい内容 | 主に触る箇所 | 対応テスト |
| --- | --- | --- |
| 表検出・結合・読み順 | `BorderTables`、`MergedRanges`、`ExcelMarkdownService` | [ExcelMarkdownServiceTest](../functions/excel2md/src/test/java/com/convertx2x/excel2md/conversion/ExcelMarkdownServiceTest.java) |
| 表示形式・太字・リンク・取消線 | `CellMarkdown`、`Markdown` | 同上の表示値・書式・リンク・削除優先ケース |
| 数式処理・外部参照 | `CellMarkdown`、ブック読み込み | [ExternalFormulaIsolationTest](../functions/excel2md/src/test/java/com/convertx2x/excel2md/conversion/ExternalFormulaIsolationTest.java) |
| 画像形式・図形種類・グループ・配置 | `DrawingExtractor`、`DrawingScene`、`SheetCoordinates` | [DrawingExtractorTest](../functions/excel2md/src/test/java/com/convertx2x/excel2md/drawing/DrawingExtractorTest.java) |
| 図形文字の書式継承・抽出 | `DrawingText` | [DrawingTextPropertiesTest](../functions/excel2md/src/test/java/com/convertx2x/excel2md/drawing/DrawingTextPropertiesTest.java) |
| 図形文字の配置・改行・縮小 | `DrawingTextLayout` | [DrawingTextLayoutTest](../functions/excel2md/src/test/java/com/convertx2x/excel2md/drawing/DrawingTextLayoutTest.java) |
| 成果物・上限・一時ファイル | `ConversionWorkspace`、`ConversionResult`、`ConversionLimits`、`AppConfig` | コア・描画テストの上限、ZIP内容、清掃ケース |
| HTTP契約・設定・非同期の有効化 | `ConversionFunctions`、設定と起動スクリプト | [ConversionFunctionsTest](../functions/excel2md/src/test/java/com/convertx2x/excel2md/ConversionFunctionsTest.java)、[test_settings_scripts.py](../functions/excel2md/scripts/test_settings_scripts.py) |
| Queue契約・再試行・Storage | `ConversionJobRequest`、`AzureJobService`、`AzureJobStore` | [jobs配下のテスト](../functions/excel2md/src/test/java/com/convertx2x/excel2md/jobs/)、[非同期E2E](../functions/excel2md/scripts/test_async_e2e.py) |
| ZIPプレビュー・画像取得・認証 | `playground/app.js`、`PlaygroundFunctions` | [ブラウザ検証](../functions/excel2md/scripts/test_playground_browser.cjs) |

図形の試験では、取消線を削除した参照入力との画像一致、色・位置・寸法などを使います。異なるOSのフォントやJava2Dで生成したPNGとの無条件なバイト一致を前提にせず、Linuxでも日本語・フォント置換を確認してください。

## ローカルでの確認

ビルド・Java/Pythonテスト・Functionsの起動は [共通の作業手順](development.md) に従い、`functions/excel2md` を作業ディレクトリにします。

`ExternalFormulaIsolationTest` は到達可能なループバックHTTPサーバーを立て、実変換中の取得要求が0件であることを確認します。インターネットやAzureへの接続は不要ですが、ローカルポートを使います。`AzureJobStoreIntegrationTest` は明示設定時だけ実Storage/Azuriteへ接続する試験なので、未設定によるskipを実接続の検証済みと扱わないでください。

Functionsホスト・Azuriteを含む試験、Playwrightでの画面検証は [開発・確認](../functions/excel2md/README.md#開発確認) に従います。既存ビルドの使い回しで変更前のコードを検証しないよう、必要なpackageを先に実行します。

[包括サンプルと検証手順](../functions/excel2md/samples/README.md)、[図形文字の比較用サンプル](../functions/excel2md/samples/shape-text.md) を再生成して確認できます。期待結果を変える際は、`report.json` の `source.sha256` と入力ファイル、`assets` のSHA-256と各画像を照合し、Markdown・report・画像を同じ変換結果から取得してください。仕様変更と不具合修正を区別して記録します。
