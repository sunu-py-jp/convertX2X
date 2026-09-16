# Movie2Audio の実処理テスト用ファイル

すべてこのリポジトリ向けに生成した、1 秒の青い画面と 440 Hz の正弦波です。
外部の映像・音楽は含みません。リポジトリと同じ MIT License で利用できます。

| ファイル | 検証内容 |
| --- | --- |
| `aac-video.mp4` | MPEG-4 の映像 + 48 kHz / mono AAC、正常な抽出と圧縮パケットの一致 |
| `aac-video.mkv` / `.avi` / `.ts` | コンテナが異なる場合の同じ音声の抽出 |
| `silent-video.mp4` | 音声トラックなし |
| `pcm-video.mkv` | PCM 音声の拒否 |
| `first-pcm-second-aac.mkv` | 第 1 音声が PCM の場合、第 2 音声の AAC に勝手に切り替えない |
| `audio-only.m4a` | 映像なしの拒否 |
| `audio-with-cover.m4a` | アルバムアートを動画と誤判定しない |

エンコード機能のある通常の FFmpeg で次を実行すると再生成できます。
本番に同梱した FFmpeg にはエンコーダーがないため、生成用には使えません。

```sh
python3 test/fixtures/generate.py /path/to/encoding-capable/ffmpeg
node --test test/ffmpeg.test.js
```

初回は公式 FFmpeg 9.0.1 の同じソースを使い、生成用にだけ
`lavfi` 入力、`color,sine,aformat,format,anull,null,aresample,scale` フィルター、
`aac,mpeg4,pcm_s16le` エンコーダー、`aac,wrapped_avframe,pcm_s16le` デコーダー、
`mp4,ipod,matroska,mpegts,flv,avi,adts` muxer を有効にした別の実行ファイルを使用しました。
`generate.py` に実際の生成コマンドとカバーアートの構築処理を保存しています。

無劣化の検証は、元 MP4 と抽出後 M4A の各 AAC パケットに対して次の出力の
`data_hash` 列を比較します。コンテナのヘッダーが異なるため、ファイル全体の SHA256 は一致しません。

```sh
ffprobe -v error -select_streams a:0 -show_packets \
  -show_data_hash sha256 -show_entries packet=data_hash -of json input.mp4
ffprobe -v error -select_streams a:0 -show_packets \
  -show_data_hash sha256 -show_entries packet=data_hash -of json output.m4a
```
