# 同梱する日本語フォント

Noto Sans CJK JP（ゴシック）とNoto Serif CJK JP（明朝）のRegular・Boldを、文書画像変換の代替フォントとして同梱します。元のフォントが利用できる場合とPDFの埋め込みフォントを優先し、不足する日本語フォントを補います。元の書体と字幅・改行・レイアウトが完全に一致するものではありません。

公式の [notofonts/noto-cjk](https://github.com/notofonts/noto-cjk) から取得した静的OpenType/CFFファイルを、変更せずに収録しています。可変フォント・フォントコレクションではありません。取得日は2026-09-09です。

## バージョンと取得元

| ファミリー | バージョン／タグ | 固定コミット |
| --- | --- | --- |
| Noto Sans CJK JP | [2.004 / Sans2.004](https://github.com/notofonts/noto-cjk/releases/tag/Sans2.004) | [`523d033d6cb47f4a80c58a35753646f5c3608a78`](https://github.com/notofonts/noto-cjk/commit/523d033d6cb47f4a80c58a35753646f5c3608a78) |
| Noto Serif CJK JP | [2.003 / Serif2.003](https://github.com/notofonts/noto-cjk/releases/tag/Serif2.003) | [`9b0f1436e455d902de067a2501422e5dc71ad16b`](https://github.com/notofonts/noto-cjk/commit/9b0f1436e455d902de067a2501422e5dc71ad16b) |

ファイル名のリンクは固定コミットからの取得URLです。バージョンとウェイトは各フォントの`name`テーブルでも確認しています。

| ファイル／取得元 | サイズ（bytes） |
| --- | ---: |
| [NotoSansCJKjp-Regular.otf](https://raw.githubusercontent.com/notofonts/noto-cjk/523d033d6cb47f4a80c58a35753646f5c3608a78/Sans/OTF/Japanese/NotoSansCJKjp-Regular.otf) | 16,467,736 |
| [NotoSansCJKjp-Bold.otf](https://raw.githubusercontent.com/notofonts/noto-cjk/523d033d6cb47f4a80c58a35753646f5c3608a78/Sans/OTF/Japanese/NotoSansCJKjp-Bold.otf) | 17,032,620 |
| [NotoSerifCJKjp-Regular.otf](https://raw.githubusercontent.com/notofonts/noto-cjk/9b0f1436e455d902de067a2501422e5dc71ad16b/Serif/OTF/Japanese/NotoSerifCJKjp-Regular.otf) | 24,573,864 |
| [NotoSerifCJKjp-Bold.otf](https://raw.githubusercontent.com/notofonts/noto-cjk/9b0f1436e455d902de067a2501422e5dc71ad16b/Serif/OTF/Japanese/NotoSerifCJKjp-Bold.otf) | 25,552,244 |

フォント4ファイルの合計は83,626,464 bytes（約79.75MiB）です。各フォントとライセンス原文のSHA-256は [SHA256SUMS](SHA256SUMS) に記録しています。このディレクトリで `sha256sum -c SHA256SUMS`（macOSでは `shasum -a 256 -c SHA256SUMS`）を実行すると照合できます。

## 著作権とライセンス

各フォントはSIL Open Font License 1.1（SPDX: `OFL-1.1`）です。著作権表示とライセンスを保持したソフトウェアへの同梱・再配布・商用利用が認められています。フォント単体の販売は認められていません。詳細は以下の原文を参照してください。

著作権表示は配布フォントの`name`テーブルからそのまま転記しています。フォント内の著作権・ライセンス情報も変更していません。

### Noto Sans CJK JP Regular / Bold

> © 2014-2021 Adobe (http://www.adobe.com/).

ライセンス原文: [LICENSE-NotoSansCJK.txt](LICENSE-NotoSansCJK.txt)。[固定コミットの上流原文](https://raw.githubusercontent.com/notofonts/noto-cjk/523d033d6cb47f4a80c58a35753646f5c3608a78/LICENSE)をそのまま保存しています。

### Noto Serif CJK JP Regular / Bold

> © 2017-2024 Adobe (http://www.adobe.com/).

ライセンス原文: [LICENSE-NotoSerifCJK.txt](LICENSE-NotoSerifCJK.txt)。[固定コミットの上流原文](https://raw.githubusercontent.com/notofonts/noto-cjk/9b0f1436e455d902de067a2501422e5dc71ad16b/Serif/LICENSE)をそのまま保存しています。

このREADME、SHA256SUMS、ライセンス原文とフォントは、`src/main/resources`からJARの`fonts/noto/`へ一緒に収録されます。プロジェクト本体のMIT Licenseは、これらのフォントのライセンスを変更しません。
