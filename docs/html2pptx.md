# HTML → PowerPoint 開発者ガイド

利用仕様は [利用ガイド](../functions/html2pptx/docs/usage.md)、実例は [HTML API資料](APIDocs/html2pptx/examples.html) を参照してください。

## 実装の役割

`functions/html2pptx/` は独立したNode.js 24のFunction Appです。現在の入口は同期HTTP・CLIです。Queueの接続処理は含みません。

| ファイル | 役割 |
| --- | --- |
| `src/input.js` | HTML/CSS構文解析、ZIP展開と上限、相対素材のインライン化、実行コード・外部参照の拒否 |
| `src/image-sources.js` | 検査済み画像を共有し、同じ画像を配置ごとに繰り返さずブラウザーへ渡す |
| `src/service.js` / `worker.js` | 資格情報を引き継がない別プロセス、時間・ヒープ上限、ブラウザーと一時領域の後片付け |
| `src/browser.js` | ローカルChrome/EdgeまたはLinux同梱Chromium、共有ライブラリ・日本語フォント、通信制御 |
| `src/fonts.js` | Noto Sans JPの共通定義、計測前にHTML・SVG・疑似要素のフォントを統一、通常・太字の読込確認 |
| `src/css-native.js` / `exporter.js` | CSSの輪郭・枠線・累積変形を汎用SVGへ渡す、固定ハッシュで確認した変換器フック |
| `src/pseudo-elements.js` | 空のCSS疑似要素を座標検査付きで通常要素へ置換（スタイル展開32MiB制限） |
| `src/text-layout.js` | ブラウザーで文字の行・書式区間・位置を計測し、折り返しと親の回転・等倍比の拡縮を編集可能な文字へ反映 |
| `src/svg-placement.js` | SVGのviewBoxと親を含む画面座標変換を正規化。CSS変形の二重適用を避け、描画設定を固定 |
| `src/engine.js` | スライド境界、配置検査、変換器の実行、レポート |
| `src/svg-native.js` | SVG計測・座標変換、編集可能な図形・文字・曲線・矢印・線形グラデーション |
| `src/pptx.js` | SVG画像の置換、描画順、表の行高・余白・行間、ID一意化、画像の配置・切り抜き・透明度・実体共有、未使用画像の除去、出力検査 |
| `src/handlers.js` / `index.js` | 同期HTTP・同時実行制限・Function認証・ダウンロード |
| `src/config.js` | 環境変数と上限。入力から上限を変更できない |
| `src/playground.js` | 入力HTMLを実行せず送信する軽量画面 |

## 変換と編集可能性

ChromiumでCSS適用後の座標を確定し、dom-to-pptxでHTML本文・表を生成します。
CSS由来の装飾もブラウザーが確定した輪郭・塗り・線・変形へ正規化し、そのライブラリがSVG画像にする部分とともにネイティブ図形へ置換します。形状名のallowlistやHTML用の独自図形属性は要求しません。
SVG内の文字をテキストボックス、基本図形をプリセット図形、曲線や矢印形状を編集可能なパスとして保持します。
SVGはグループ化されるため、PowerPointでグループ内の要素を選択するか、グループ解除して編集します。接続先の意味やSmartArtは推測しません。

元の画像はPNG/JPEGのバイト列を保持し、拡大縮小・縦横比・回転・反転・切り抜き・角丸・透明度をPowerPointの画像オブジェクトへ反映します。同じ元画像の実体は共有し、各配置は個別に移動・編集できます。
画像単体のCSS `filter: blur(...)` は、透明な余白を持つ加工PNGとして出力し、`IMAGE_BLUR_APPLIED` を記録します。ぼかし量は画像に焼き込まれ、変更には再変換が必要です。重ねた文字や枠線は別の編集可能な要素として残ります。
図形・文字の意図しない画像化や元画像の欠落は `UNSUPPORTED_LAYOUT` で止めます。画像のskew・3D変形・一般のfilter、画像自身の枠線や背景とblurの同時指定、画像を切る親のクリッピングなどは非対応です。重なる子要素を親のopacityで一括合成するケースも、SVGを含めて明示的に拒否します。

描画順はDOM順だけでなくz-indexと親のスタッキングコンテキストを考慮します。画像・図形・文字の交差、DOM順とz-indexの逆転、子のz-indexが親の順序を越えないこと、親背景と負のz-indexの関係を生成PPTXの要素順と実描画で検証します。

表はブラウザーで行高・セル余白・行間を計測し、結合セルとともに反映します。PPTXの行高は最小値として扱われるため、行高だけでなく余白と行間も補正し、表が縦へ膨らむ差を抑えています。

非対応のCSS/SVGは明示的なエラー、保持できない線の細部等はレポート警告になります。
フォント埋め込み・長文の自動改ページ・SVGの自動接続・JavaScript実行は行いません。

現在同梱する日本語フォントはNoto Sans JP（Regular/Bold、OFL）です。HTML/CSSのフォント指定にかかわらず、HTML・SVGの文字をNoto Sans JPに統一して計測・変換します。検証用の描画でも同じフォントを使いますが、PPTXへフォントファイルは埋め込みません。閲覧環境に同じフォントがない場合の置換・改行差は残ります。reportの `FONTS_NOT_EMBEDDED` はこの制約を示すもので、各端末の代替フォントを検出した結果ではありません。

## 入力境界とリソース制限

ZIPはメモリー内で、展開中の実バイト数・ファイル数・パスを検査します。
HTML/CSSはparse5/css-treeで解析し、許可した構文のみブラウザーへ渡します。
素材はインライン化し、CSP、通信フック、オフライン設定で外部取得を拒否します。検査済みの構文木を変換でも使い、画像は重複をまとめてブラウザーへ渡します。同一画像を多数配置したHTML文字列の再解析・再転送を抑えますが、入力の展開サイズと配置ごとの画素数の上限は適用します。
SVGの参照展開・文字数・パス長にも上限があります。

ブラウザーへAzureの接続情報を渡しません。sandbox無効化へのフォールバックもありません。
入力コードは拒否したうえで、変換器自身の固定コードをDevToolsから実行します。
アプリ側の通信制御はOSのネットワーク隔離と同一ではありません。利用者・受付量・ネットワーク方針は配置先でも管理します。

HTTPは同一プロセスで1件ずつ処理し、混雑時は429です。複数プロセスを使う場合は、その分のメモリーも必要です。
検証用Flexは4GB、最大1インスタンス、HTTP同時実行1、always-readyなしで確認します。

## 検証

```shell
cd functions/html2pptx
npm ci --ignore-scripts --no-audit --no-fund
npm test
npm run convert -- examples/organization-governance.html output/organization
```

ブラウザーテストにはChrome/Edgeが必要です。`HTML2PPTX_CHROMIUM_PATH` で実行ファイルを指定できます。
SVG単体テストで明示する場合は `HTML2PPTX_TEST_BROWSER` を指定します。
企業発表・決算・体制図・API構成・プロジェクト計画の5資料に、図形の比較と画像の比較を加えた7資料・20スライドを用意しています。文字の欠落、画像数、SVG画像が残らないこと、表行高、ID一意性を確認します。画像例は5スライド・28配置で、拡縮・反転・切り抜き・角丸・透過・ぼかし・重なり順を比較します。
外部参照、実行コード、ZIP逸脱、上限、未対応描画、タイムアウトは別のテストで確認します。
2026-10-30の最終確認では119テストが成功し、失敗・スキップはありませんでした。作業用の結果は `functions/html2pptx/output/final/`、公開用の例はAPI資料に置きます。

実例ページのPPTXプレビューは生成ファイルを外部レンダラーで描画し、使用したソフトを各ページに明記します。ピクセル比較はLibreOffice→PDF→PDFBoxを主比較、POIを副比較とします。HTMLのスクリーンショットとは区別します。PowerPoint本体の表示・編集を直接検証したことにはなりません。
Azureでの確認結果とローカルの時間・メモリーは混同しないでください。

### Azure HTTP入口の確認（2026-10-30）

画像の変形・ぼかし・重なりを含む最終版もFlex 4GBへデプロイしました。素材入りZIPをHTTPへ送り、5スライド・28画像配置をHTTP 200（再試行なし）で変換できました。変換処理約11.7秒、HTTP全体約28.1秒、PPTX約2.63MBです。元画像のバイト列保持、画像実体が元画像1つ＋ぼかし2種の計3つにまとまること、編集可能な図形・文字、Noto Sans JP指定を検査しました。実行環境はNode.js 24.22.1 / HeadlessChrome 153.0.8010.0です。記録は `functions/html2pptx/output/azure-images/validation.json` にあり、この成功だけで下記の断続的な503が解消したとは判断しません。

Noto Sans JPへ統一した版も同じFlex 4GBへデプロイし、PNG画像・SVG工程図・日本語本文を含む企業発表2枚を変換しました。HTTP 200、変換処理約5.8秒、PPTX内に元画像1枚と編集可能な図形・文字、`fontFamily: Noto Sans JP`を確認しています。記録は `functions/html2pptx/output/azure-noto/validation.json` です。

表の余白・行間を補正した最終版でも、Flex 4GB上で決算資料2枚と体制図2枚の変換を確認しました。処理内の変換時間は約5.1秒・3.9秒で、文字・図形の存在、元画像以外の画像化がないこと、PPTXのSHA-256を確認しています。体制図は初回503、再試行200でした。再試行を含むHTTP全体の時間とは区別し、入口の安定性が確認できた結果としては扱いません。作業時の記録は `functions/html2pptx/output/azure-final/validation.json` です。 同じ環境で入力JavaScriptを `UNSUPPORTED_HTML`、外部画像参照を `EXTERNAL_RESOURCE_FORBIDDEN`（いずれも422）で拒否することも確認しました。これらも初回503後の再試行で得た応答です。

検証用のFlex 4GB環境（最大1インスタンス、HTTP同時実行1、always-readyなし）では、変換とは別に、HTTP入口から約60秒後に本文 `The service is unavailable.` の503が返る現象を確認しました。キーなしの存在しないURLへのGETと、認証キーなしのPOSTを計6回送ると、404と503が交互に発生しました。同じ存在しないGET URLを連続して呼ぶ場合も、404（0.12秒）の次が503（60.07秒）になりました。変換ハンドラーを通らない経路でも再現するため、PPTX変換やHTTP応答のバッファ処理が原因と断定していません。観測結果は作業時の `functions/html2pptx/output/azure/validation-infra.json` に保存しています。

Azure公式リポジトリには、別の利用者による[同様の交互503の報告](https://github.com/Azure/azure-functions-host/issues/11632#issuecomment-6219966771)と、[always-readyで改善したという追記](https://github.com/Azure/azure-functions-host/issues/11632#issuecomment-6289827261)があります。これは本環境の原因が確定したことを意味しません。スケールトゥゼロを維持するため、本検証ではalways-readyを設定していません。最大インスタンス数を2へ一時的に変更しても連続GETの3回目で503（60.07秒）が発生したため、1へ戻しました。SDKへの対症的なパッチも適用していません。実運用前に入口の連続呼び出しを再検証する必要があります。

### 見た目の回帰検査

`node scripts/visual-compare.mjs examples/visual-fidelity.html output/visual-comparison` で同解像度の原本・PPTX描画・差分・半透明重ね合わせと `metrics.json` を生成します。図形の種類ごとに入力規則を増やす検証ではなく、任意の多角形・パス、回転、線種、CSSクリップ、疑似要素の共通処理を確認します。

比較用のLibreOffice、Java 21＋POI/PDFBox、Python＋Pillowは開発端末だけに必要です。本番の変換はNode.js＋同梱Chromiumで実行し、これらの比較ツールを呼び出しません。`npm run package` は比較スクリプト、Javaプレビュー実装、テスト、実例や作業用出力を `dist/` に含めません。

## 配布

`npm run package` で単独配置用の `dist/` を作成します。依存はlockfileに固定し、インストール時のスクリプトを実行しません。
ライセンス・NOTICE・Chromiumと共有ライブラリの出典を機能内の `third-party/` に保持します。
2026-10-30の配布確認では89件のnpmパッケージのライセンス資料と、ネイティブ依存・フォントの資料を検査しました。依存数は固定仕様ではなく、更新時は配布スクリプトの検査結果を使います。
依存やブラウザーを更新した場合は、対応するバージョンの原文・ハッシュと実機起動を再確認してください。
