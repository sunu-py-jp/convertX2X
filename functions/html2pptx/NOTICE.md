# HTML2PPTX third-party notices

convertX2X HTML2PPTXのアプリケーションコードは、このディレクトリの `LICENSE` に記載したMITライセンスです。依存ソフトウェア・ブラウザー・フォントには、それぞれの条件が適用されます。

- npmの直接・推移依存を、実際の `package-lock.json` と配布先のインストール済みファイルに照合します。原文がnpmアーカイブにないものは、公開時の上流コミットにある原文と出典・ハッシュを `third-party/node/` に保存しています。
- `@sparticuz/chromium` のラッパーはMITですが、同梱Chromium全体をMITとは扱いません。Chromium 153.0.8010.0のLinux headless shell用ライセンス全文、SwiftShader/Vulkan等の第三者表示、別途同梱されるNSS・NSPR・Expat・Open Sansの原文と対応ソースの取得先を `third-party/chromium/` に保存しています。Chromium側にはBSD・Apache・MIT・MPL・LGPL等のコンポーネントが含まれます。
- 日本語フォントNoto Sans JPの著作権表示とSIL Open Font License 1.1は `assets/fonts/OFL.txt` にあります。Google Fonts公式の固定コミットにある可変TTFから、Regular（400）/Bold（700）の静的TTFを生成しています。出典・生成手順・元ファイルと生成物のハッシュは `third-party/fonts/noto-sans-jp/` に保存しています。フォントはOFLのまま配布し、アプリのMITへ変更しません。PPTXへのフォント埋め込みは行いません。
- npmの `https@1.0.0` は実装ファイルを持たないメタデータだけのパッケージです。Node.js組み込みの `https` を使用するため、このnpmディレクトリは配布物から除外します。除外対象のバージョン・完全性・ファイルハッシュも固定して検査します。

`npm run package` は、全ライセンス原文・NOTICE・出典・インベントリーを含む `dist/` を生成します。そこに生成される `THIRD-PARTY-NOTICES.txt` と `third-party/` 全体、`LICENSE`、このファイル、フォントのOFLを、機能単独で配布するときも保持してください。バイナリーの更新・別ビルドへの差し替えでは、対応するライセンスとソース情報も再確認して更新してください。


`src/exporter.js` は固定したdom-to-pptx 2.1.2の完全性を検査した上で、実行時のメモリ内だけにCSS図形の変換フックを追加します。元の著作権・ライセンス表示は保持しています。CSS図形の輪郭・変形・枠線を共通のベクター処理へ渡すためのアプリケーション側の変更です。
