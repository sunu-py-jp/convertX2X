# 同梱 FFmpeg 9.0.1

Movie2Audio はこのディレクトリの公式ソースからビルドした FFmpeg / ffprobe を
Node.js から独立した子プロセスとして呼び出します。アプリケーションと FFmpeg ライブラリはリンクしていません。
アプリケーション起動時のダウンロード、OS パッケージインストール、任意コマンドの指定は行いません。

| 配布物 | 場所・内容 |
| --- | --- |
| 対応する完全なソース | `ffmpeg-9.0.1.tar.xz`（未変更の公式リリース） |
| 公式署名 / 公開鍵 | `ffmpeg-9.0.1.tar.xz.asc` / `ffmpeg-devel.asc` |
| 署名確認結果 | `signature-status.txt` |
| FFmpeg ライセンス | `COPYING.LGPLv2.1` / `LICENSE.md` |
| Linux C ランタイムの通知 | `LICENSE-musl.txt` |
| GCC ランタイムの通知 | `COPYING.GCC-RUNTIME` / `COPYING.GPLv3` |
| MinGW-w64 ランタイムの通知 | `COPYING.MinGW-w64-runtime.txt` |
| 実行ファイル | `resources/ffmpeg/{linux-x86_64,macos-aarch64,windows-x86_64}/` |
| 再ビルド手順 | `scripts/build_ffmpeg.py` |

ソースアーカイブの SHA256:

```text
cf38e0e28c7e5605942c4a77755349b0145804a397af37eb1fb4c77cb237f635
```

署名鍵の固定 fingerprint:

```text
FCF986EA15E6E293A5644F10B4322F04D67658D8
```

公式の取得元は [FFmpeg のリリース](https://ffmpeg.org/releases/ffmpeg-9.0.1.tar.xz)、
[署名検証手順](https://ffmpeg.org/download.html#release-verification)、
[ライセンス指針](https://ffmpeg.org/legal.html)です。

## ビルド構成

Linux x64 は固定 digest の Alpine 3.22.2 上で GCC 14.2.0 / musl 1.2.5 を使い、
ライブラリを静的リンクしています。glibc や別途配置する `.so` ファイルは不要です。
musl の小さい既定スレッドスタックでは MPEG-TS の AAC ADTS 処理が失敗するため、
ELF の `GNU_STACK` を 8 MiB に設定しています。Windows x64版は同じAlpine環境の
MinGW-w64でFFmpegとコンパイラーランタイムを静的リンクするクロスビルドです。OS標準DLL以外の
追加DLLは不要です。macOS Apple Silicon版はApple clangで
ビルドし、OS標準の `libSystem` を使います。

両者とも次の構成です。各バイナリ横の `manifest.json` に実際の configure 引数、
ビルド環境、サイズ、SHA256 を記録しています。

- ネットワーク機能、外部ライブラリ自動検出、不要なエンコーダーを無効化
- 入出力 protocol は `file` のみ
- 入力demuxerはMOV/MP4、Matroska/WebM、AVI、MPEG-TS、FLVと、出力検証用のWAV
- 出力muxerは`ipod`、その依存の`mov`、`wav`
- AAC・Opus・MP3・一般的なPCMのデコーダー、必要なparser、ADTSからMP4へのbitstream filterを有効化
- FFmpeg内蔵の`aac`・`pcm_s16le`エンコーダーと、音声のリサンプル・形式調整用フィルターのみ有効化
- GPL オプション、nonfree オプション、version3 オプションは有効にしていません

既定はAACパケットのコピーです。明示したtranscodeモードでは、対応する音声を
AAC/M4Aまたは16bit PCM/WAVへ変換できます。外部の音声エンコーダーはリンクしていません。
`file` protocol の許可はファイルシステムのサンドボックスではないため、
このアプリは playlist demuxer を含めず、MOV の外部参照オプションも既定の無効状態を維持します。

## 再ビルド

Python 3.12 以降、curl、Docker が必要です。Mac 版には Apple Silicon の macOS と
Command Line Tools（clang / make）も必要です。Linux版とWindows版はDocker上でビルドします。

```sh
python3 scripts/build_ffmpeg.py --target all
# Linux x64 のみ
python3 scripts/build_ffmpeg.py --target linux-x86_64
# Windows x64 のみ
python3 scripts/build_ffmpeg.py --target windows-x86_64
```

ソース・鍵・通知ファイルは固定 SHA256 を検証し、公式署名も固定 fingerprint で検証します。
ビルドは一時ディレクトリで行い、完成した実行ファイルと manifest をリソースに配置します。
コンパイラーや Alpine パッケージの更新によりバイナリの SHA256 が変わる場合があるため、
更新時は manifest と実処理テストの結果を確認してください。

Linux の MPEG-TS → M4A の smoke test は fixture がある場合にビルド内でも実行します。
全体の変換・タイムアウト・上限・パケット一致テストは次で実行します。

```sh
node --test test/ffmpeg.test.js
```

## 配布時の扱い

FFmpeg 部分は LGPL 2.1-or-later です。対応するソース、ビルドスクリプト、著作権・ライセンス通知を
本ディレクトリごと保持してください。`npm run package` は `dist/third-party/ffmpeg/` にこれらを、
`dist/scripts/build_ffmpeg.py` にビルドスクリプトを含めます。実行ファイルは `dist/resources/ffmpeg/` に配置します。
MIT のアプリケーションコードと FFmpeg の著作権・ライセンスは区別します。
ソースは未変更のため差分はありません。

Linux の musl 部分は `LICENSE-musl.txt` の MIT と付随する通知に従います。
GCC のランタイムコードには GCC Runtime Library Exception 3.1 が適用されるため、
通知として GPLv3 本文と例外の本文を同梱しています。これは FFmpeg の GPL オプションを有効にした
ビルドという意味ではありません。GCC はコンパイル時だけ使用し、実行ファイルとして同梱しません。
Windows版に含まれるMinGW-w64ランタイムの著作権・ライセンス通知は
`COPYING.MinGW-w64-runtime.txt` に保持します。
