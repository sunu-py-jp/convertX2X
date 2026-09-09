# ダウンロードしたPowerPointテンプレート

2026-09-09に公式配布元から取得した、ローカルで試すためのファイルです。

| ファイル | デザイン | 実ファイルの枚数 | サイズ |
| --- | --- | ---: | ---: |
| `bubbler-modern-16x9.pptx` | 紫と白、抽象的なバブル、図表・地図などを含む16:9 | 26 | 1,829,940 bytes（約1.8 MB） |
| `luxury-consulting-tool.pptx` | 緑を基調としたシンプルなビジネス向け | 23 | 42,596,317 bytes（約42.6 MB） |

環境変数が未設定の場合、入力上限は20 MiB、ページ数上限は50です。`bubbler-modern-16x9.pptx` は既定値で受付対象になります。約42.6 MBのLuxury Consulting Toolを試す場合は、例えば `CONVERSION_MAX_INPUT_BYTES=104857600`（100 MiB）を設定してホストを再起動してください。ページ数上限は `CONVERSION_MAX_PAGES` で変更できます。

## 出典・利用条件

- Bubbler: [Showeet公式配布ページ](https://www.showeet.com/15/03/2018/templates/bubbler-modern-powerpoint-template/)、[利用条件](https://www.showeet.com/terms-of-use/)。Designed by Showeet.com。無料利用には帰属表示が必要です。テンプレート自体の再配布は禁止されています。同梱の `bubbler-original-readme.txt` も参照してください。紹介ページの写真は一部のみPPTXに含まれ、その他は画像の差し替え用プレースホルダーです。
- Luxury Consulting Tool: [SlidesCarnival公式配布ページ](https://www.slidescarnival.com/template/luxury-consulting-toolkit/69367)、[利用条件](https://www.slidescarnival.com/terms-of-use)。Template by SlidesCarnival。帰属表示・サイトへのリンクが必要です。写真を利用する場合はCreditsスライドを保持してください。未変更のテンプレート自体の再配布は禁止されています。

PPTXは配布されたものを変更せず保存しています。ローカルのダウンロードファイルはGit管理とFunctionsへのデプロイ対象から除外しています。
