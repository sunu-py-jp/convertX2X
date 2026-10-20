# Node.js 依存パッケージのライセンス

`npm run package` は lockfile に固定した production 依存をインストールし、各パッケージのライセンス全文と通知ファイルを検査します。`node_modules/` 内の原文を保持し、配布物に `THIRD-PARTY-NOTICES.txt` と `third-party/node/inventory.json` を生成します。後者には全依存の名前・版・配置先、原文のパスと SHA-256 を記録します。配布物には検査スクリプトも含まれます。

```sh
# 機能ディレクトリから、生成済み配布物を再検査
node scripts/licenses.mjs dist --check
# 配布物のディレクトリだけを受け取った場合
node scripts/licenses.mjs . --check
```

検査・通知生成はネットワークを使いません。`npm run package` 内の依存インストールには通常どおり npm レジストリーへの接続または npm キャッシュが必要です。

## npm アーカイブに含まれないライセンス

`@nodable/entities@3.0.0` の公開 npm アーカイブには `LICENSE` が含まれません。`licenses/nodable-entities-3.0.0-LICENSE` は、公式リポジトリーの固定コミットから取得した無変更の MIT ライセンスです。著作権表示は `Copyright (c) 2026 Nodable` です。

取得 URL、コミット、npm アーカイブ URL と integrity、対象の版、原文 SHA-256 は [overrides.json](overrides.json) に記録します。通常のパッケージ作成時にオンライン取得や `latest` への追従は行いません。

## 依存更新時

ライセンス本文がない依存、空の通知、見出しや SPDX 識別子だけのライセンス、lockfile と実際の依存の不一致があれば作成を停止します。補完対象の版・npm integrity・ライセンス識別子・原文 SHA-256 の不一致や、不要になった補完定義も停止理由です。

依存の版を更新する際は、公開アーカイブと公式ソースのライセンス・著作権表示を確認します。新しい版で原文が含まれるようになった場合は補完定義と補完ファイルを削除し、含まれない場合は対応する公式ソースを固定して取得し、対象の版とハッシュを更新してください。`npm run package` の後に上記検査を実行し、生成された一覧と全文を確認します。`test/licenses.test.js` は欠落、本文欠落、補完原文の改変、版の変化を検証します。
