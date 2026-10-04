# md2pdf 利用ガイド

## 対応範囲

UTF-8の `.md` / `.markdown`、またはMarkdownと画像を含む `.zip` を送ります。
CommonMark構文とGFMの表に対応し、日本語を検索可能なPDFとして出力します。

- 見出し、段落、太字・斜体、番号付き・番号なしリスト、表、引用
- コードブロック、インラインコード、リンク、水平線
- PNG / JPEG / GIF / BMP画像（GIFは静止画）
- `<!-- pagebreak -->` による明示改ページ、自動改ページ、ページ番号

PDFは内容を読みやすく再配置する形式です。ブラウザーのCSSや任意のHTMLレイアウトは
再現しません。Mermaidブロックはコードとして、HTMLは文字として表示し、reportに
警告を残します。単純な `<br>`（`<br/>`・`<br />` を含む）は改行として扱い、
`<!-- pagebreak -->` は改ページにします。この2つにはHTML警告を出しません。

## 画像を含める

単独のMarkdownでは `data:image/png;base64,...` などのBase64データURIを使えます。
相対パスの画像はZIPに同梱してください。

```text
input.zip
├── document.md
└── images/
    └── diagram.png
```

```markdown
![処理の流れ](images/diagram.png)
```

ZIPのルートに `document.md` があればそれを使います。それ以外の場合、ZIP内の
Markdownは1ファイルにします。画像の相対パスはMarkdownの位置を基準に解決します。
Office → MarkdownのZIPも、ルートの `document.md` と `images/` を保てば入力できます。

HTTPS画像やリンク先を外部から取得しません。未取得・未対応・欠損画像は代替ラベルを
表示し、非同期のreportに警告を残します。ローカルPCの絶対パスも読みません。

## ローカル起動

必要なものはJava 21、Python 3.10以上、Azure Functions Core Tools v4、Azuriteです。
WindowsではMaven WrapperとCore Toolsの `.cmd` を明示的に解決します。

```shell
cd functions/md2pdf
python scripts/run_local.py        # Windows
# または
python3 scripts/run_local.py       # macOS / Linux
```

既定ポートは `7075`、Playgroundは `http://localhost:7075/api/playground` です。
ビルド済み成果物を再利用する場合は `--skip-build` を付けます。

```shell
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @samples/basic/input.md \
  'http://localhost:7075/api/convert?filename=input.md' \
  --output document.pdf
```

## 非同期をローカルで使う

`local.settings.example.json` を `local.settings.json` へコピーして次を設定します。

```json
{
  "Values": {
    "AzureWebJobsStorage": "UseDevelopmentStorage=true",
    "CONVERSION_STORAGE_CONNECTION_STRING": "UseDevelopmentStorage=true",
    "CONVERSION_CREATE_RESOURCES": "true"
  }
}
```

`UseDevelopmentStorage=true` の場合、起動スクリプトはBlob `10000`、Queue `10001` を
確認し、Azuriteが停止中なら起動します。既存プロセスがあればそのまま使います。
Functions起動前に `md2pdf-jobs` コンテナー、処理Queue、毒Queueを作成します。
非同期設定が空ならQueue関数と保守タイマーを無効化して同期HTTPだけ起動します。

## HTTP API

| メソッド | パス | 内容 |
| --- | --- | --- |
| `POST` | `/api/convert?filename=...` | MarkdownまたはZIPを送り、PDFを受け取る |
| `POST` | `/api/jobs?filename=...` | 非同期ジョブを登録する |
| `GET` | `/api/jobs/{id}` | 状態と成果物URLを取得する |
| `GET` | `/api/jobs/{id}/result` | `document.pdf` を取得する |
| `GET` | `/api/jobs/{id}/report` | `report.json` を取得する |
| `GET` | `/api/jobs/{id}/archive` | PDFとreportのZIPを取得する |
| `GET` | `/api/capabilities` | 対応形式と公開上限を取得する |

入力はmultipartではなくファイルのバイト列です。Azureでは変換・ジョブAPIに
`x-functions-key` を付けます。Playgroundとcapabilitiesは匿名で開けます。
同期APIは警告件数を `X-Warning-Count` で返し、警告の詳細は非同期のreportで確認します。

## 上限

段落、表、行・列、画像の個数に固定上限はありません。リソースに関係する境界だけを
設定します。既定の生成ページ数は無制限です。

| 設定 | 既定 | 用途 |
| --- | ---: | --- |
| `CONVERSION_MAX_INPUT_BYTES` | 20 MiB | HTTP・Blob入力とZIP展開後の合計 |
| `CONVERSION_MAX_OUTPUT_BYTES` | 100 MiB | PDF・report・ZIPの出力境界 |
| `CONVERSION_MAX_PAGES` | `0` | 生成ページ数。`0`は無制限 |
| `CONVERSION_MAX_IMAGE_PIXELS` | 20,000,000 | 1画像の画素数 |

1インスタンス内のHTTP同時処理とQueueバッチ、変換コアの同時実行は1件です。
大きい入力は非同期Queueで処理し、Azure側のインスタンス数で並列化します。

## Azureへの配置

Linux / Java 21の従量課金Function Appを作成して配置します。

```shell
./mvnw -B -ntp package
./mvnw -DfunctionAppName='<function-app-name>' azure-functions:deploy
```

接続文字列の代わりに `CONVERSION_STORAGE__blobServiceUri` と
`CONVERSION_STORAGE__queueServiceUri` を設定するとManaged Identityを使えます。
別Storage・結果通知の設定は[直接Queueガイド](direct-queue.md)を参照してください。

## ライセンス

アプリコードはMIT、CommonMarkはBSD 2-Clause、PDFBoxはApache License 2.0、
BIZ UDPゴシックはSIL Open Font License 1.1です。商用利用できます。
フォントの出典、ハッシュ、ライセンス本文は `src/main/resources/fonts/bizud/` にあります。
