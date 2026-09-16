#!/usr/bin/env python3
"""Generate small, original regression fixtures. Requires a separate encoding-capable FFmpeg.

Usage: python3 generate.py /path/to/ffmpeg
The production FFmpeg deliberately contains no encoders and cannot generate these.
All pictures and sounds here are synthetic; no third-party sample media is used.
"""
import base64
from pathlib import Path
import struct
import subprocess
import sys

FFMPEG = sys.argv[1]
DESTINATION = Path(__file__).resolve().parent
BASE = [FFMPEG, "-hide_banner", "-loglevel", "error", "-y"]
VIDEO = ["-f", "lavfi", "-i", "color=c=blue:s=160x90:r=10:d=1"]
AUDIO = ["-f", "lavfi", "-i", "sine=frequency=440:duration=1:sample_rate=48000"]
for name, args in [
    ("aac-video.mp4", VIDEO + AUDIO + ["-c:v", "mpeg4", "-c:a", "aac", "-b:a", "64k", "-shortest"]),
    ("silent-video.mp4", VIDEO + ["-c:v", "mpeg4", "-an"]),
    ("pcm-video.mkv", VIDEO + AUDIO + ["-c:v", "mpeg4", "-c:a", "pcm_s16le", "-shortest"]),
    ("audio-only.m4a", AUDIO + ["-c:a", "aac", "-b:a", "64k"]),
    ("first-pcm-second-aac.mkv", VIDEO + AUDIO + AUDIO + [
        "-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "mpeg4",
        "-c:a:0", "pcm_s16le", "-c:a:1", "aac", "-shortest"]),
]:
    subprocess.run(BASE + args + [str(DESTINATION / name)], check=True)
for name in ["aac-video.mkv", "aac-video.avi", "aac-video.ts"]:
    subprocess.run(BASE + ["-i", str(DESTINATION / "aac-video.mp4"), "-map", "0", "-c", "copy",
                           str(DESTINATION / name)], check=True)


def atom(kind, payload):
    return struct.pack(">I4s", len(payload) + 8, kind) + payload


def append_cover(data, path, cover):
    """Add an original 1x1 PNG as iTunes artwork without creating a video track."""
    output = bytearray()
    offset = 0
    while offset < len(data):
        size, kind = struct.unpack_from(">I4s", data, offset)
        assert size >= 8 and offset + size <= len(data)
        payload = data[offset + 8:offset + size]
        if kind == path[0]:
            prefix = payload[:4] if kind == b"meta" else b""
            content = payload[len(prefix):]
            payload = prefix + (append_cover(content, path[1:], cover) if len(path) > 1 else content + cover)
        output.extend(atom(kind, payload))
        offset += size
    return bytes(output)


png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aL1sAAAAASUVORK5CYII=")
cover = atom(b"covr", atom(b"data", struct.pack(">II", 14, 0) + png))
data = (DESTINATION / "audio-only.m4a").read_bytes()
(DESTINATION / "audio-with-cover.m4a").write_bytes(append_cover(data, [b"moov", b"udta", b"meta", b"ilst"], cover))
print("Generated fixtures in", DESTINATION)
