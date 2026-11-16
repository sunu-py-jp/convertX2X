# HTML表現と入力契約

対象はconvertX2Xのhtml2pptx。異なる版では接続先の資料・実装と照合する。ブラウザーで表示できるだけではPPTX変換の成功を保証しない。

## ページ・文字・表

| 内容 | 使い方・制約 |
| --- | --- |
| ページ | 同寸法のsectionなどにdata-slide。幅・高さのmetaはCSS px。複数ページでは区切りを明示する |
| レイアウト | Flex/Grid、通常フロー、ページ内の絶対配置。ページはposition:relativeとし、寸法・余白を明示 |
| 文字 | HTML本文またはSVGのtext/tspan。画像やパスにしない。文字のskew・非等方拡縮・反転、縦書きに依存しない |
| 表 | table/thead/tbody/tr/th/td、必要ならrowspan/colspan。セル幅と余白を確保。表や親に回転・拡縮をかけない |
| 編集後 | 位置を確定して出力するため、PowerPoint編集後にFlex/Gridの再配置は行われない |

基礎CSSにbox-sizing:border-boxを使い、行間と図表の幅を明示する。Flex/Grid内の意図しない拡張はmin-width:0等で防ぐ。重要な文字をoverflow:hidden・line-clamp・text-overflow:ellipsisで隠して収まったことにしない。

## 図解・線・重なり

- 複雑な図はインラインSVGにまとめ、width/heightとviewBoxを整合させる。rect、circle、ellipse、polygon、polyline、path、text/tspan、gなどを使える。図形名の固定リストに制限する必要はない。
- 文字は実テキスト。改行はtspanへ分け、背景とラベルを別要素として保つ。foreignObjectや画像内文字へ逃がさない。
- SVGのtextのyは上端ではなくベースライン。大きな数字・見出しはフォントの上下の範囲を含めてviewBox内へ収め、上端ぎりぎりに置かない。表示上の字面だけでなくgetBBox()でも確認する。
- stroke/stroke-dasharrayで実線・破線、marker-start/endや輪郭パスで矢印を作る。線幅・先端までページ内に置く。PowerPointの自動接続先やSmartArtの意味は付かない。
- SVGのidはs2-flow-arrowのようにページを含む一意な名前にする。defsの参照先は同じ入力内へ置く。
- 単色・対応する線形グラデーションを使える。SVGのradialGradient・filter・mask、複雑なクリッピングには依存しない。
- CSSの基本図形、角丸、図形の2D変形、輪郭用clip-pathを扱える。文字・画像にも同じ変形やクリッピングを適用できるとは限らない。
- 重なりはDOM順・z-index・親のスタッキングコンテキストで決まる。親をまたぐ大きなz-indexで順序を推測しない。
- 重なる子を持つ親全体のopacityは避け、必要な要素に個別指定。3D・mix-blend-mode・backdrop-filterは使わない。
- 擬似要素は空の装飾に限られる。意味のある文字・番号・箇条書き記号はDOM内の実要素にする。
- SVGでグラフを作っても、Excelデータ付きのネイティブチャートにはならない。尺度・単位・対象期間は保持する。

## 元画像

| 要求 | 入力方法と結果 |
| --- | --- |
| 写真・画面 | PNG/JPEGをimgへ。width/heightを明示。元画像は差し替え可能な画像として保持 |
| 切り抜き | img自身へobject-fit/object-position。親のoverflowで切らない |
| 円形・角丸 | img自身へborder-radius。画像のclip-pathは未対応 |
| 拡縮・回転・反転・透過 | 画像の配置・加工として扱える。非等方拡縮は意図した歪みの場合だけ使う |
| ぼかし | img自身の単独filter:blur(px)。その画像だけ加工PNGになる。PPTXでぼかし量は変更できない |
| 画像上の文字・枠 | 別のHTML/SVG要素として重ね、編集可能に保つ |

画像のskew・3D・影、blur以外や複数filter、親filterには依存しない。blurを使う画像の背景・枠は別要素にし、ぼかしの余白を確保する。写真はbackground-imageでなく位置を指定したimgにする。同じ画像は同じファイルを参照し、解像度を用途に合わせる。画像内の文字はOCR・編集可能テキスト化されない。

外部画像URL・srcset・picture・WebP・GIFをそのまま入力しない。必要なら生成側でPNG/JPEGへ準備して同梱。素材がなければ壊れた参照を納品せず、依頼に応じて代替を用意するか不足を伝える。

## 動きが必要な場合だけ

静的資料へ装飾目的のアニメーションを自動追加しない。指定された場合はCSS @keyframesを使う。

- フェードイン、中心を基準とする2D移動・拡縮・回転は、対応条件を満たせばPowerPointの動きになる。基本は1回再生、終端は読める状態にする。
- animation-delay/durationはスライド開始からの時間。クリック待ちやホバー・スクロール操作へ対応付けない。
- 同じ要素の複数指定は、すべて対応可能な場合だけ合成。色・角丸など未対応が1つでもあれば、要素全体が終端で固定される。
- 有限は再生方向を考慮した終端、無限は最初の1回分の終端。fill-mode:noneでも元のスタイルへ戻る前を採用。終端が透明なら見えなくなる。
- 変形済みの親や親子両方の動きなどは固定になる場合がある。静止画だけでネイティブ変換の成否を判断しない。
- 固定後に未対応の3D・filter等が残れば変換自体が失敗する。reportのnativeEffects/staticEffectsと理由を確認する。

## ファイルのまとめ方

HTML単体ならCSSはstyle、SVGはインライン、PNG/JPEGは完全なdata URI。素材が複数なら次の構造でZIP化する。

```text
input.zip
├── index.html
├── styles.css
└── assets/
    ├── screen.png
    └── diagram.svg
```

ルートHTMLはindex.htmlかindex.htmの1つ。相対パスで参照し、上位フォルダで包まない。ZIPに入れるのはHTML・CSS・PNG・JPEG・SVGのみ。フォント、node_modules、スクリプト、README、隠しファイル、リンク、重複パス、暗号化エントリは除く。SVGをimgで参照しても、検査後に図形・文字へ変換される。

上限はAPIのcapabilities/利用資料で確認する。同期HTTP・CLI用であり、QueueやジョブAPIを仮定しない。
