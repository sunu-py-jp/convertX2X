#!/usr/bin/env python3
"""Generate original one-second audio-format fixtures with a full FFmpeg build.

Usage: python3 generate_audio_formats.py /path/to/ffmpeg
The fixture generator requires libmp3lame, libopus, lavfi and a video encoder.
None of these external encoders is distributed with the production runtime.
"""
from pathlib import Path
import subprocess
import sys

destination = Path(__file__).resolve().parent
base = [sys.argv[1], "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=blue:s=160x90:r=10:d=1"]
for filename, source, codec, channels in [
    ("opus-video.mkv", "sine=frequency=440:duration=1:sample_rate=48000", "libopus", 1),
    ("mp3-video.mkv", "sine=frequency=440:duration=1:sample_rate=48000", "libmp3lame", 1),
    ("stereo-pcm-video.mkv", "sine=frequency=440:duration=1:sample_rate=48000", "pcm_s16le", 2),
    ("silent-pcm-video.mkv", "anullsrc=sample_rate=48000:channel_layout=mono", "pcm_s16le", 1),
]:
    subprocess.run(base + ["-f", "lavfi", "-i", source, "-t", "1", "-map", "0:v", "-map", "1:a",
                          "-c:v", "mpeg4", "-c:a", codec, "-ac", str(channels), "-shortest",
                          str(destination / filename)], check=True)
