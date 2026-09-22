# office2md

Excel（`.xlsx` / `.xls`）、Word（`.docx`）、PowerPoint（`.pptx`）からMarkdown・画像・変換情報を取り出す、Java 21のAzure Functionsアプリです。ブラウザ用の簡単なPlayground、同期HTTP、非同期Queueが同じ変換処理を使います。このディレクトリだけでビルド・起動・デプロイできます。

開発・保守向けの構成と変更箇所は [Officeの開発者ガイド](../../../docs/office2md.md)、環境構築・検証・デプロイは [共通の作業手順](../../../docs/development.md) を参照してください。このガイドはAPI・設定・変換ルールの利用手順です。

## ローカルで試す

Java 21 JDK、Python 3.10以上、Azure Functions Core Tools v4（`func`）を用意します。Mavenは同梱のWrapperを使います。

```sh
cd functions/office2md
cp local.settings.example.json local.settings.json
python3 scripts/run_local.py
```

WindowsのPowerShellでは次を使います。`local.settings.json` がすでにある場合はコピーを省略します。

```powershell
cd functions/office2md
Copy-Item local.settings.example.json local.settings.json
python scripts/run_local.py
```

起動スクリプトはWindowsでは `mvnw.cmd`、macOS/Linuxでは `mvnw` を選びます。Windowsの `func.cmd` と `func.exe` の両方に対応します。起動に失敗した場合は、処理名と `WinError` / `errno` または終了コードを表示します。接続設定や例外の本文は表示しません。

<http://localhost:7072/api/playground> を開き、Officeファイルを選んで「Markdownに変換」を押します。Markdownのプレビュー・ソース・警告を確認し、ZIPを保存できます。画面用のNode.js、npm、CDNは不要です。既存のビルドを使う場合は `--skip-build`、ポート変更は `--port 7082` を指定します。

Storage接続を設定しなければ同期だけが有効です。非同期を使う場合は、実行環境の `CONVERSION_STORAGE_CONNECTION_STRING` または `local.settings.json` の同名設定に接続文字列を設定して起動します。Azuriteなら、先にBlob・Queueサービスを起動し、値を `UseDevelopmentStorage=true` にします。接続文字列やキーをリポジトリに保存しないでください。

起動スクリプトは接続設定からQueueバインド用の別名 `CONVERSION_QUEUE_CONNECTION_STRING` と `AzureWebJobs.ProcessConversion.Disabled` / `AzureWebJobs.PoisonConversion.Disabled` を生成します。Javaの設定だけでは、Javaの起動前にAzure Functionsホストが読み込むQueueリスナーを無効にできません。Storageなしのときも、無効化したバインドを読み込めるよう別名にはエミュレーター形式の値を入れます。ローカルの `AzureWebJobsStorage` が空なら、非同期用Storageをホスト用にも使います。

[Wordサンプル](../samples/office-sample.docx)・[PowerPointサンプル](../samples/office-sample.pptx) と、文章・罫線表・書式・数式キャッシュ・画像・基本図形・グループと未対応時の警告をまとめて試す [包括テスト用Excel](../samples/full-feature.xlsx) を用意しています。[サンプルの内容と再生成・実HTTP/Queue検証手順](../samples/README.md) も参照してください。

## 出力と形式ごとのルール

同期HTTPは常に `document.zip` を返します。画像がなくても `document.md` と `report.json` が入ります。

```text
document.md
report.json
images/image-0001.png     # Excel・Wordの貼り付け画像、または添付ファイル
images/diagram-0001.png   # Excel・Wordの個別図形、PPTXのスライド参考画像
```

Markdownからは `![画像](images/image-0001.png)` のような相対パスで参照します。ZIPを展開した後も、`document.md` と `images/` を同じ場所に置いて利用できます。

次のルールを形式ごとに適用します。ファイル別のルールJSONや範囲指定はありません。

| 項目 | Excel | Word | PowerPoint |
| --- | --- | --- | --- |
| 対応形式 | XLSX・XLS | DOCX | PPTX |
| 区切り・見出し | 表示シートごとに `# [シート名] シート` | 文書先頭と保存済み・明示改ページに `[page n]`。明示した見出し階層も保持 | 表示スライドごとに `[page n]`、続けてタイトルのH1 |
| 表 | 直接セル罫線から通常表を検出し、同じ行範囲の左右の値・表を取り込む | 罫線の有無によらず表オブジェクトを変換 | 罫線の有無によらず表オブジェクトを変換 |
| 本文の順序 | セル・ブロックの位置順 | 文書に保存された本文順 | タイトル、本文・表、図中の項目・接続関係、参考画像 |
| 追加の扱い | 保存済み数式結果のみ | 本文の挿入・削除履歴を反映、脚注・文末脚注を末尾へ | 図形文字・保存された接続を本文とJSONに残し、全体の参考画像を添える |
| 除外 | 非表示シート・行・列 | 削除履歴・移動元・非表示文字・コメント・ヘッダー／フッター | 非表示スライド・図形、発表者ノート、コメント、ヘッダー／フッター |

太字・HTTP/HTTPS/mailtoリンクを保持し、取消線の文字は装飾・リンク・画像altを生成する前に除去します。Word・PowerPointの箇条書きは保持し、自動番号は元の番号を優先します。番号をMarkdown閲覧側が振り直すのを避けるため、番号付き項目を文字列の接頭辞として出す場合があります。

Wordの変更履歴は挿入・移動先を残し、削除・移動元を除外します。削除された段落記号による段落連結や、結合セルの変更履歴から構造を復元する処理は対象外です。

PowerPointの `n` は元のスライド番号なので、非表示スライドを除外すると番号が飛ぶ場合があります。Wordは先頭を `[page 1]` とし、明示改ページ、段落の「改ページ前」、Wordが保存した最終レンダリング改ページを順に `[page 2]` 以降として出力します。変換時にページ組版は再計算しないため、保存されていない自動改ページの位置は追加しません。

Word・PowerPointの表も、明示ヘッダーがなければ空のヘッダーを補って元の全行を保持します。結合セルはアンカーに値を一度だけ出し、続きは空欄です。ネストした表や完全には表現できない構造は警告を確認してください。

画像はブック・文書に埋め込まれたものだけを扱います。各形式とも図形内の文字と確認できた接続関係をMarkdown本文に出し、JSONと確認用の画像を添えます。画像やaltを解釈しなくても図形文字を検索できます。

### 図を検索できる文字で残す

図中の文字は「図中の項目：」、接続は「接続関係（保存情報）：」として出力します。本文・表の扱いはそのままに、画像をまとめる単位と配置を入力形式に合わせます。

| 形式 | 参考画像の単位 | 本文への配置 |
| --- | --- | --- |
| Excel | 明示グループ、または保存された接続先を確認できたコネクターでつながる図。独立した図形は単独 | 図のアンカーに沿ってセル本文・表の間へ。表に重なる図は表の直後 |
| Word | 一つの本文内・浮動描画オブジェクトに属するグループ・描画キャンバス。独立したオブジェクトは合成しない | 元の描画が属する本文位置 |
| PowerPoint | 図を含むスライド全体 | 本文・表の後 |

近さ・重なり・横並びだけでは図をまとめません。Excelのセルや罫線、Wordのページ全体は画像へ取り込まず、従来どおり本文・表として出力します。

各スライドは `[page n]` とH1の後に、通常の本文・表、「図中の項目：」「接続関係（保存情報）：」、スライド全体の参考画像の順で出力します。H2以降は追加しません。図形内の文字は太字・リンクを保ったMarkdown本文に残り、画像のaltを抽出しなくても検索できます。接続先になった通常テキストボックスも図中の項目へまとめ、本文と二重に出力しません。関係のない本文・表は通常どおり残します。本文と表、および図中の項目はそれぞれ上→下・左→右の位置順です。

```md
図中の項目：

- shape-3（長方形）：申請
- shape-4（ひし形）：確認

接続関係（保存情報）：

- shape-5：shape-3「申請」 → shape-4「確認」
```

接続はコネクターに保存された接続先IDと端点の矢印だけから生成します。`triangle / stealth / arrow` で向きを判定し、丸（●、`oval`）・ひし形（◆、`diamond`）は方向判定では矢印なしとして扱います。開始点が `oval`、終端が `triangle` なら `start-to-end`、両端が丸・ひし形なら `undirected` です。JSONの `startArrow / endArrow` には元の端点種類を残します。近くの文字を分岐ラベルへ結び付けたり、座標から処理順や接続を推測したりしません。双方向と向きなしも区別します。未知の端点記号の `direction` は `unknown` とし、保存情報がない・参照先が除外済み・IDが重複・端点記号の向きが不明な場合は、本文に「接続関係不明」、レポートに `DIAGRAM_CONNECTION_UNRESOLVED` を残します。旧Excel形式 `.xls` とWordのVMLの線は描画できますが、接続先を推測せず未解決として扱います。

Wordの未対応の基本形状も、読み取れる文字・IDはノードとして残します。参考画像へ描けなくても保存された接続は解決できます。折れ線・曲線コネクターは画像では直線で近似し、警告を記録します。グラフ・SmartArtの内部構造を図形として展開する処理は行いません。

`report.json` の `type: "diagram"` ブロックは、次の構造を持ちます。図形IDはExcelではシート内、Wordでは描画ブロック内、PowerPointではスライド内で有効です。全体では `section / range` と組み合わせて扱ってください。

| フィールド | 内容 |
| --- | --- |
| `nodes[]` | `id / type / text / x / y / width / height`。形状種類、取消線除去後の文字、変形後の外接矩形 |
| `edges[]` | `id / startId / endId / startArrow / endArrow / direction / status / reason`。未解決の端点キーは省略 |
| `direction` | `start-to-end / end-to-start / bidirectional / undirected / unknown` |
| `status / reason` | `resolved` なら理由は空文字。`unresolved` なら `MISSING_ENDPOINT / TARGET_UNAVAILABLE / AMBIGUOUS_TARGET / UNKNOWN_ARROWHEAD` |
| `fromId / toId` | 解決済みの片方向の接続だけに追加する、向きを反映した始点・終点 |
| `path / metadata` | 参考画像の相対パスと、そのaltと同じJSON |

Wordはコネクター自身が持つ文字を本文へ出し、該当する `edges[]` にも `type / text / x / y / width / height` を追加します。近くの独立した文字を接続のラベルへ割り当てる処理は行いません。

画像の `nodes[].text` には、読み取れる保存済みの説明を使います。画像内に焼き込まれた文字にはOCRを行わず、説明がなければ空文字です。図形の文字・説明の改行はJSON内で保持します。回転・反転・グループ変形・重なり順を反映し、PNG/JPEGも図形と同じ参考画像に含めます。その他の画像形式は原本添付と警告にします。

PowerPointは対応する表示要素をスライド全体の1枚へ描画します。保存・継承された単色背景はテーマ参照を含めて反映し、背景指定がなければ白にします。画像・グラデーションなど未対応の背景は白へ置き換え、`UNSUPPORTED_SLIDE_BACKGROUND` を記録します。外部の背景画像は取得しません。

各形式の参考画像のaltは次の6項目のJSONです。同じ情報を `blocks[].metadata` にも出力します。まとめた図の `type` は「図」、`text` は空文字で、図形ごとの種類・文字は `nodes` にあります。Wordの単独図形・画像はaltにもその種類・文字を残します。下記はスライド全体を表すPowerPointの例です。Excel・Wordは、描画できた要素を囲む範囲になります。回転角はJSONに含めません。

```md
![{"type":"図","text":"","x":0,"y":0,"width":960,"height":540}](images/diagram-0001.png)
```

右をXの正方向、下をYの正方向とし、単位はpt（1/72インチ）で固定します。原点・単位のフィールドは出力しません。原点は下記の形式別ルールで解釈してください。個別図形の寸法・文字は `nodes` にあり、参考画像のaltにはまとめ直しません。添付ファイルのリンクには個別の種類・文字・外接矩形のJSONを付けます。

Markdownから取得する場合はaltをそのまま `JSON.parse(alt)` に渡せます。文字中の角括弧・引用符・バックスラッシュなどは `\u005B`・`\u0022`・`\u005C` のようなJSONのUnicodeエスケープを使うため、Markdownのエスケープを別途解除する必要はありません。Excel・WordもこのJSON形式に統一しており、従来の日本語の説明文を解析する必要はありません。

RAGへの取り込みではシート・見出し・`[page n]` を区切りにし、図中の項目と接続関係を同じチャンクへ残すと関係を保てます。長いため分割する場合も、接続だけを切り離さず参照先の文字を含め、未解決の関係を確定情報として扱わないでください。LLMによる説明やOCRは使いません。[業務フローのデモPPTX](../../../docs/APIDocs/office2md/examples/powerpoint-rag-flow/input.pptx) と [実変換Markdown](../../../docs/APIDocs/office2md/examples/powerpoint-rag-flow/output/document.md) で確認できます。

### 図形の座標と順序

Excelはシート左上、PowerPointはスライド左上を原点とし、右向きをXの正方向、下向きをYの正方向にします。単位はpt（1/72インチ）、表示は小数3桁までです。座標・幅・高さは回転・反転・グループ変形後の外接矩形で、線の太さやPNGの描画余白を含みません。回転前の図形サイズや出力PNGのピクセル寸法とは異なります。Excelのシート座標はセルの幅・高さから計算するため、実際のExcel表示と丸めの差が出る場合があります。

Wordの数値座標は描画オブジェクト内のローカル座標です。本文内・浮動配置の保存指定は図の `placement` 文字列として別に残します。ページ組版を実行していないため、別の図との距離やページ上の絶対位置を表すものではありません。位置を読み取れない図形は座標・寸法を `null` にし、描画用の仮座標をメタデータへ流用しません。

Excelは図の位置を使い、本文との上下順を保ち、表と重なる図はその表の後へ置きます。Wordは本文XMLの順番です。PowerPointは本文・表の後に図中の項目と接続をまとめます。各図の配置は参考画像で確認できます。通常のMarkdownビューアーやPlaygroundは座標による自由配置を行いません。

### Excel固有の変換

- 表示シートを元の順序で出力し、`# [シート名] シート` をH1にします。本文からH2やタイトルを推測しません。非表示のシート・行・列は除外します。
- セルに直接設定された罫線から、外周と内部分割を確認できる矩形の通常表を最初に検出します。通常表がある行範囲では、その左右にある表示値と、開始行・終了行が同じ横並びの表を一つのMarkdown表へ取り込みます。間の空列は省きますが、元の罫線表内の空列は残します。通常表がない単なる横並びの値から表を新規作成することはありません。
- 実際のセル結合は左上の値を一度出力し、続きは空欄にします。横に連続する区画で外枠と上下線があり、中の縦線だけがない場合も見た目上の1セルとみなし、複数セルの値を左側へまとめます。Markdownには列結合の構文がないため、続きの列は空欄です。
- 検出した通常表の範囲とExcelテーブル定義が一致し、ヘッダー行が有効な場合だけ、その先頭行をヘッダーにします。それ以外は空のヘッダーを追加し、元の全行をデータとして残します。曖昧な罫線範囲は本文として残し、警告を記録します。
- 太字・リッチテキストの太字、HTTP/HTTPS/mailtoリンクを保持します。取消線が付いた文字は削除し、削除内容は変換情報にも残しません。セル内改行は保持し、Markdownの記号やHTMLはエスケープします。
- 数式は保存済みの計算結果を使い、再計算しません。表示形式を日本語ロケールで適用します。`HYPERLINK` の固定文字列リンクは扱いますが、動的なリンク先は計算しません。
- 貼り付けPNG/JPEGは図形と同じ参考画像に描き、回転・反転・親グループの変形を反映します。切り抜きや複雑な効果は反映せず、`IMAGE_EFFECTS_IGNORED` を記録します。それ以外の画像形式は添付ファイルとして残し、未対応を記録します。図はアンカー位置に沿って並べ、表に重なるものは表の後に置きます。
- 長方形・角丸長方形・楕円・線・一部の矢印・テキストボックスをJava2Dで近似描画します。グラフ、SmartArt、WordArt、自由曲線、複雑な効果やExcelのレイアウト全体は再現しません。未対応の図形は警告を確認してください。

マクロを含むブック、マクロ有効形式・テンプレート、`.xlsb`、暗号化ファイルは対象外です。外部リンクの取得、外部画像のダウンロード、マクロの実行は行いません。図形内の文字も画像に含めます。Excelに保存されたフォント・サイズ・太字・斜体・文字色（RGB/テーマ色）・通常の下線・文字間隔、段落ごとの左右配置・行間・段落間隔・余白、上下配置・折り返し・縮小設定・文字の回転を反映します。取消線部分は描画前に除去します。利用可能な元フォントを優先し、必要に応じて同梱NotoのSans/Serif・Regular/Boldへ置き換えます。縦書きの組版、WordArt、箇条書き記号、文字だけを正立に保つ反転・回転、文字に合わせた図形枠の拡張などは近似または未対応として警告します。フォントの置換やExcelとは異なる組版処理により、完全に同じ字幅・改行位置を保証するものではありません。旧形式 `.xls` の数式では、保存済み計算結果が欠落しているかを確実に区別できない場合があります。

`report.json` は `specVersion: 2`、入力のファイル名・SHA-256、`sectionCount`・`sectionKind`、`warnings`、`information`、元の範囲と対応する `blocks`、出力画像のパス・種類・サイズ・SHA-256を持つ `assets` を含みます。各形式の `diagram` ブロックには `nodes / edges`、参考画像があれば `path / metadata` があり、添付ファイルにも `path / metadata` を付けます。画面に表示しきれない内容もファイルから確認できます。

[図形内文字の比較用Excel](../samples/shape-text.xlsx) と [指定内容の一覧](../samples/shape-text.md) で、文字色・3×3の上下左右配置・段落別配置・余白・折り返し・行間・縮小などを確認できます。

Word・PowerPointの図形も対応する基本形状を描画します。Word図形の文字は本文とJSONにも残しますが、図形内の太字装飾・リンク先は保持せず、リンクの表示文字だけを残します。通常の本文・表の太字・リンクは保持します。図形PNGは代表書式を用いる簡易描画で、段落ごとの混在書式・独自余白・文字だけの回転は近似として警告します。WordのPNG/JPEGも回転・反転・グループ内配置を参考画像へ反映します。PowerPointはこれらに加えて保存された切り抜きを反映します。それ以外の画像形式は原本添付のままで、回転などの加工は適用しません。グラフ、SmartArt、数式オブジェクトは未対応として警告し、外部データを取得して補完しません。参考画像にも対応要素だけを描画します。PowerPointの複雑な描画効果はPOIによる再現範囲に限られ、個々の見た目の差をすべて警告で検出するものではありません。旧形式の `.doc`・`.ppt`、PDF、マクロ有効形式・テンプレート、暗号化ファイルは対象外です。

`sectionCount` は出力に含むExcelシート数・PowerPointスライド数、Wordでは出力文書数（通常1）です。`sectionKind` はそれぞれ `sheet` / `slide` / `document`、`source.format` は入力拡張子です。警告・ブロックの位置は `section` と `range` で記録します。

## 数式と外部参照

変換処理は、関数名によらずすべての数式を再計算しません。POIの `FormulaEvaluator` を使わず、[保存済み計算結果を読む設定](https://poi.apache.org/apidocs/dev/org/apache/poi/ss/usermodel/DataFormatter.html#setUseCachedValuesForFormulaCells-boolean-)で表示値を取り出します。`IMPORTRANGE`、`IMPORTXML`、`IMPORTHTML`、`IMPORTDATA`、`IMPORTFEED`、`WEBSERVICE` なども、未知の関数や入れ子の式も同じ扱いです。ブック内のURLや外部ブック参照を取得せず、欠落した値を外部取得で補完することもありません。

| 入力 | 出力時の扱い |
| --- | --- |
| 保存済み計算結果がある数式 | 保存済みの値を表示。古い値やエラーも更新しない |
| 計算結果がない `.xlsx` の数式 | エスケープした数式文字列を残し、`FORMULA_CACHE_MISSING` を記録 |
| セルの式全体が `IMAGE(...)` | 画像を取得せず、未対応の表示と `CELL_IMAGE_UNSUPPORTED` を記録 |
| 固定URLの `HYPERLINK` / セルや図形のリンク | 許可されたHTTP/HTTPS/mailtoリンクを保持。変換中はアクセスしない |
| 動的な `HYPERLINK` のリンク先 | 計算せず、表示文字と警告を残す |
| 外部画像や外部データ接続 | ダウンロード・更新しない。画像として取り出すのはブックに埋め込まれたデータのみ |

Playgroundも数式を実行せず、画像は変換結果に含まれるファイルだけを表示します。通常のリンクはユーザーがクリックしたときに開きます。同期HTTPと非同期Queueでこの方針は共通です。Queue・Blobへの通信は管理者が設定したStorage接続で行います。

元ファイルをOfficeやGoogleの各アプリで開いた際の再計算・外部アクセスは、そのアプリ側の動作です。この変換処理は元ファイルの数式を削除・無効化するものではありません。WordのフィールドやPowerPointの外部リンクも更新せず、保存済みの表示だけを扱います。

## HTTP API

既定のルート接頭辞は `/api` です。Playgroundと `/api/capabilities` は匿名で取得できます。それ以外はFunction認証で、Azure上では `x-functions-key` ヘッダーにFunction Appのホストキーを渡します。複数のFunctionにまたがる非同期処理には、アプリ全体で利用できるホストキーを使います。

| メソッド・パス | 内容 |
| --- | --- |
| `POST /api/convert?filename=sample.xlsx` | Officeファイルの生バイト列を受け、ZIPを返す |
| `POST /api/jobs?filename=sample.xlsx` | 非同期を登録。202と `job` / `statusUrl`、`Retry-After` を返す |
| `GET /api/jobs/{id}` | `queued` → `running` → `succeeded` / `failed` を確認 |
| `GET /api/jobs/{id}/result` | 完成した `document.md` |
| `GET /api/jobs/{id}/report` | 完成した `report.json` |
| `GET /api/jobs/{id}/images/{assetName}` | 完成した結果に含まれる画像・添付ファイル1件 |
| `GET /api/jobs/{id}/archive` | 完成した成果物から、その場でZIPを生成 |
| `GET /api/capabilities` | `asyncEnabled`、対応拡張子、設定された11種類の上限 |

```sh
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @sample.xlsx \
  'http://localhost:7072/api/convert?filename=sample.xlsx' \
  -o document.zip
```

`filename` には入力と一致する拡張子を指定します。省略時の名前は従来の `workbook.xlsx` のため、Word・PowerPointでは必ず指定してください。`multipart/form-data` やJSONではなく、`application/octet-stream` でファイル本体を送ります。ページや幅、変換ルールなどのオプションはありません。同期レスポンスには `X-Section-Count` と `X-Warning-Count` が付きます。失敗時は成功したZIPの代わりにHTTPエラーと `error.code` / `error.message` を返します。

非同期の成功状態には `resultUrl`、`reportUrl`、`archiveUrl`、`assetsBaseUrl` が加わります。`assetsBaseUrl` は `/api/jobs/{id}/images/` を指します。返されたURLを使い、各取得リクエストにも認証ヘッダーを付けます。Blobの保存場所や資格情報はHTTP状態APIに含めません。保存するのはMarkdown・変換情報・画像それぞれで、ZIPを常時保存しません。非同期が無効ならジョブAPIは503を返します。

PlaygroundはキーをURLやブラウザストレージに保存せず、結果URLの同一オリジンとジョブのパスを検証します。プレビューは生成されるMarkdownの基本構文に限定し、入力HTMLを実行せず、外部画像を取得しません。表示は先頭20万文字、ブロック・表セルの合計1万件、警告一覧は300件までです。取得・展開は合計100 MiB、Markdownと変換情報各20 MiB、添付1件20 MiBを上限とし、サーバーの設定が小さければそちらを適用します。添付数に画面独自の固定上限はなく、サーバーで画像配置数と図形数の両方を制限した場合だけ、その合計を上限とします。ZIPの展開には `DecompressionStream` の `deflate-raw` に対応したブラウザが必要です。「待機を停止」は画面の通信を止める操作で、サーバージョブを取り消しません。

## 非同期の設定と直接Queue投入

制御用StorageにQueue `office2md-jobs` と同名の状態管理用Blobコンテナーを作ります。入力と成果物には、同じStorageまたは環境変数で登録した別Storageを指定できます。Queue JSONには接続文字列や任意URLを入れません。プロトコル、別Storageの設定、Java送信例は [直接Queue投入](../docs/direct-queue.md) を参照してください。

既存のAzure Function Appへ設定を反映する場合は、環境変数から次のスクリプトを実行できます。接続設定が空なら非同期を無効にし、既存の無関係なApp Settingsは保持します。

```sh
python3 scripts/configure_azure_async.py --resource-group YOUR_RESOURCE_GROUP --name YOUR_FUNCTION_APP
```

このスクリプトは既存のアプリを設定するだけで、Azureリソースの作成やコードのデプロイは行いません。Azure側のホスト用 `AzureWebJobsStorage` は別途有効な設定を用意します。接続設定を追加・削除するときはQueue用の別名と無効化フラグも一緒に更新してください。失敗したジョブ、入力、一時的な試行の成果物を含むBlobには、用途に合わせて保持期間を設定してください。

## Azureへ配置する

既存のLinux・Java 21・Functions v4のFunction Appへ配置します。Flex Consumptionでは、まずインスタンスメモリーを4,096 MB、HTTPのインスタンス当たり同時実行数を1に設定して確認する構成を想定しています。設定コマンドは [MicrosoftのFlex Consumption手順](https://learn.microsoft.com/en-us/azure/azure-functions/flex-consumption-how-to#set-http-concurrency-limits) に沿っています。

```sh
az functionapp scale config set --resource-group YOUR_RESOURCE_GROUP --name YOUR_FUNCTION_APP --instance-memory 4096
az functionapp scale config set --resource-group YOUR_RESOURCE_GROUP --name YOUR_FUNCTION_APP --trigger-type http --trigger-settings perInstanceConcurrency=1
```

Azure側に有効なホスト用Storage設定と `JAVA_OPTS=-Djava.awt.headless=true` を用意し、前節の `configure_azure_async.py` で接続設定とQueueリスナーの有効・無効を反映します。既存の `JAVA_OPTS` がある場合は、その内容を保持してheadless設定を加えてください。別Storageの登録や上限の変更も、必要に応じてApp Settingsに設定します。

Azure CLIへログインした状態で、Mavenが生成した配布用ディレクトリをCore Toolsで配置します。ソースディレクトリから直接publishせず、JAR・依存ライブラリ・生成された `function.json` が揃ったディレクトリを使います。

```sh
# functions/office2md で実行
./mvnw package
cd target/azure-functions/office2md-local
func azure functionapp publish YOUR_FUNCTION_APP --no-build
```

`--no-build` はコンパイル済みの配布物を使う指定です。公開方法とオプションは [Core Toolsのpublish手順](https://learn.microsoft.com/en-us/azure/azure-functions/functions-run-local#publish-to-azure) を参照してください。`local.settings.json` の設定を自動転送するオプションは付けていません。配置後はアプリの `/api/playground` を開き、ホストキーを入力して小さなExcelから確認します。ここに記載したコマンドは配置手順であり、このリポジトリでAzureへのデプロイやAzure実機検証を実行済みという意味ではありません。

## 上限

以下は1回の変換に対する設定です。シート・スライド数、読み取り項目数、表セル数、画像配置数、図形数は既定で無制限です。これら5種類は `0` で制限なし、正の整数で任意の上限を指定できます。既存の環境変数や `local.settings.json` に正の値があれば引き続き適用されるので、解除する場合は削除するか `0` に変更して再起動してください。旧名の設定も対象です。`GET /api/capabilities` も制限なしを `0` で返します。

件数制限は処理量の目安として設けていましたが、同じ画像の再配置など軽い処理まで止めるため、既定では適用しません。容量制限と、展開後の画素数・再帰の深さ・1図形の過大な文字レイアウトを抑える保護は維持します。圧縮されたOfficeファイルの容量だけでは、画像の展開メモリーや処理時間を判断できないためです。POIはOffice文書をメモリーに読み込み、HTTPのZIPレスポンスも上限付きのバイト配列として生成します。

1つのJavaプロセスでは変換を1件ずつ実行します。Queueのホスト設定も `batchSize=1`・動的同時実行無効です。ただしスケールアウトした別インスタンスは並列に動き、アプリ全体の同時実行数1や全ジョブのFIFO順序を保証するものではありません。

| 環境変数 | 既定値 | 対象 |
| --- | ---: | --- |
| `CONVERSION_MAX_INPUT_BYTES` | 20,971,520 | 入力20 MiB |
| `CONVERSION_MAX_SECTIONS` | 0（制限なし） | Excelのシート数／PPTXのスライド数。Wordは1文書 |
| `CONVERSION_MAX_READ_ITEMS` | 0（制限なし） | Excelは実体セル数、Wordは本文・参照注のXML要素数、PPTXは文字数＋段落・Run数 |
| `CONVERSION_MAX_TABLE_CELLS` | 0（制限なし） | 検出した表の展開セル合計 |
| `CONVERSION_MAX_MARKDOWN_BYTES` | 20,971,520 | UTF-8 Markdown |
| `CONVERSION_MAX_IMAGES` | 0（制限なし） | 貼り付け画像の配置数 |
| `CONVERSION_MAX_IMAGE_BYTES` | 20,971,520 | 画像・添付ファイル1件 |
| `CONVERSION_MAX_OUTPUT_BYTES` | 104,857,600 | 成果物合計と生成ZIP、それぞれ100 MiB |
| `CONVERSION_MAX_SHAPES` | 0（制限なし） | 図形の数 |
| `CONVERSION_MAX_GROUP_DEPTH` | 16 | 図形グループの深さ |
| `CONVERSION_MAX_IMAGE_PIXELS` | 20,000,000 | 描画画像の画素数 |

容量・画素数・グループ階層は正の整数を指定します。表セル数を無制限にしても、空セルだけでMarkdownまたは成果物の容量を超える表は展開前に `MARKDOWN_BYTES_LIMIT` で停止します。画像配置・図形のいずれかが無制限なら、合算した添付ファイル数にも件数上限を設けません。成果物合計の容量制限は引き続き適用します。

Excel・Wordで使う共有Java2Dの図形文字レイアウトには追加の固定上限があります。1図形につき20万文字・2万書式区間・1万段落まで、縮小の試算を含む行レイアウトは合計2万行までです。Word XMLの入れ子も128階層までです。過大な文字レイアウトや再帰で実行環境を使い尽くさないための保護で、超過時は切り捨てず `413 DRAWING_TEXT_LIMIT` / `DOCUMENT_DEPTH_LIMIT` / `GROUP_DEPTH_LIMIT` を返します。PowerPointの文書全体の読み取り件数は、任意に `CONVERSION_MAX_READ_ITEMS` を設定した場合だけ制限します。

HTTPタイムアウトに収まることをファイルサイズだけで判定する機能はありません。時間のかかる資料は非同期を使い、実際の資料で時間とメモリーを確認してください。

## excel2mdからの移行

プロジェクトは `functions/office2md`、Javaパッケージは `com.convertx2x.office2md`、共通入口は `OfficeMarkdownService` に変わりました。配布先は `target/azure-functions/office2md-local`、Queueと状態コンテナーは `office2md-jobs` です。Azureの既存リソースや旧Queue内の依頼を自動移行する処理はありません。旧ワーカーで処理を完了させてから送信先を切り替えるか、新しいジョブIDで新Queueへ依頼してください。

HTTPのパスとQueueの依頼JSON（version 1）は維持します。結果のreportはversion 2に更新し、`sheetCount` / `sheet` は `sectionCount` / `section`、HTTPヘッダー `X-Sheet-Count` は `X-Section-Count` に変わります。状態APIの件数も `sectionCount` を使います。既存の結果を読むクライアントは両形式を扱うか、更新後の結果に切り替えてください。

上限の環境変数は `CONVERSION_MAX_SECTIONS` と `CONVERSION_MAX_READ_ITEMS` に一般化しました。従来の `CONVERSION_MAX_SHEETS` / `CONVERSION_MAX_READ_CELLS` も別名として使え、新旧両方を設定した場合は新しい名前を優先します。他の接続・上限設定は共通です。

## 開発・確認

```sh
./mvnw test
python3 -m unittest discover -s scripts -p 'test_*.py'
```

`ExternalFormulaIsolationTest` は、テストが所有する到達可能なループバックHTTPサーバを数式の参照先に指定し、実際にExcelを変換して取得リクエストが0件であることを確認します。保存済み値、未計算の式、入れ子の `IMAGE`、通常のリンクの扱いも検証します。インターネットへの接続は不要ですが、テスト中のローカルポートの利用は必要です。

実際のFunctionsホストとAzuriteを使う非同期E2Eは、macOS/Linuxで次のコマンドから明示的に実行します。Java 21・`func`・`azurite` がPATHに必要です。テスト専用のStorageアカウントと入力を生成し、HTTP・直接Queue投入・別Storage・重複配送・成果物を確認します。Azureクラウドへの接続は不要です。

```sh
./mvnw package
python3 scripts/test_async_e2e.py
```

既定ポートはFunctionsが7073、Azuriteが12200/12201/12202です。`--port` / `--blob-port` / `--queue-port` / `--table-port` で変更できます。終了時に起動プロセスと一時ファイルを片付けます。ログ・成果物を残す場合は `--keep-artifacts` を付けます。このE2Eは通常のunittest discoveryでは起動しません。

画面の検証はPlaywrightを利用する開発用スクリプトです。実行環境に `playwright` と対応するChromiumが必要で、製品の配信ファイルには含まれません。既存のChromeを使う場合は `PLAYWRIGHT_CHANNEL=chrome` を指定します。

```sh
node scripts/test_playground_browser.cjs --out /tmp/office2md-browser
# 実ホストと sample.xlsx / sample.xls / sample.docx / sample.pptx でも確認
node scripts/test_playground_browser.cjs --base http://localhost:7072 --fixtures target/fixtures --out /tmp/office2md-live
```

既定では隔離したテストAPIで、同期・非同期、ZIP内パス、認証、HTML・外部画像の抑止、エラー、画面幅を確認します。`./mvnw test` は日本語の `target/fixtures/sample.xlsx` と `sample.xls` も生成します。Word・PowerPoint用の `sample.docx` / `sample.pptx` は [生成手順](../samples/README.md#wordpowerpointを試す) で用意します。起動済みのローカルホストに `--base` とこのディレクトリを指定すると、実APIで変換し、スクリーンショット・ZIP・JSON形式の検証レポートを指定先に保存します。Storageが有効なら非同期も確認します。これはローカル検証で、Azure上の動作確認を代替しません。

## ライセンス

このプロジェクトのコードは [MIT License](../LICENSE) です。Apache POIなどの依存ライブラリには各ライブラリのライセンスが適用されます。同梱のNoto Sans CJK JP / Noto Serif CJK JP（Regular・Bold）はSIL Open Font License 1.1です。[フォントの出典・ライセンス](../src/main/resources/fonts/noto/README.md) と原文・SHA-256をJARにも含めています。Playgroundは独自のHTML/CSS/JavaScriptで、外部のMarkdown・ZIPライブラリやWebフォントを含みません。

## Managed Identity・結果通知・保持期間

接続文字列に加えて、制御用Storageの `CONVERSION_STORAGE__blobServiceUri`・`CONVERSION_STORAGE__queueServiceUri` と任意の `__clientId` でManaged Identityを使えます。入力・出力の登録先にも `CONVERSION_INPUT_STORAGE_<ALIAS>__blobServiceUri` / `CONVERSION_OUTPUT_STORAGE_<ALIAS>__blobServiceUri` を使えます。同じ登録で接続文字列とMIを混在させません。

`CONVERSION_CREATE_RESOURCES=false` はコンテナー・Queueの自動作成を省略します。必要なリソースは配置前に用意してください。結果Queueは事前登録し、version 2の `notification.queue` で選択します。通知の送信待ちは永続化し、重複通知をeventIdで識別できます。

`CONVERSION_RESULT_RETENTION_DAYS`・`CONVERSION_STATE_RETENTION_DAYS` は既定0（自動削除無効）です。状態保持を有効にする場合は、結果保持も有効にし、それより長く設定します。期限切れ結果は清掃後に `410 JOB_RESULT_EXPIRED` になります。未完了のHTTP受付が残った場合も、保持設定に従って期限切れとして処理します。通常のQueue待ち・実行中のジョブを期限切れ成果物として削除しません。

結果通知先または保持を設定すると5分ごとのメンテナンス実行が発生します。未設定では定期処理は無効です。ホスト認証・RBAC・閉域DNS・保持と重複判定の関係・利用側の版照合は[共通の配置・運用手順](../../../docs/development.md#8-managed-identity閉域storage結果通知)、JSONの拡張は[直接Queueのversion 2](direct-queue.md#version-2入力版付加情報結果通知)を参照してください。
