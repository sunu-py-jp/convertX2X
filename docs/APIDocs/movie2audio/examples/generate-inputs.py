#!/usr/bin/env python3
"""Generate original Movie2Audio samples; requires Pillow and an encoding FFmpeg.

Usage: python3 generate-inputs.py /path/to/encoding-ffmpeg
The production FFmpeg is extraction-only and intentionally cannot generate media.
No third-party video, music, voice, or external network resource is used.
"""
import math
import struct
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

DEST = Path(__file__).resolve().parent
ROOT = DEST.parents[3]
FFMPEG = sys.argv[1]
FONT = ROOT / 'functions/ppt-pdf-to-images/src/main/resources/fonts/noto/NotoSansCJKjp-Regular.otf'
BOLD = FONT.with_name('NotoSansCJKjp-Bold.otf')


def frame(complex_case, second, duration):
    image = Image.new('RGB', (960, 540), '#101e36')
    draw = ImageDraw.Draw(image)
    regular = lambda size: ImageFont.truetype(str(FONT), size)
    bold = lambda size: ImageFont.truetype(str(BOLD), size)
    draw.text((52, 38), 'convertX2X  /  ORIGINAL TEST MEDIA', font=regular(19), fill='#9ab1d1')
    draw.text((52, 98), '複数トラックの抽出確認' if complex_case else 'AAC音声の抽出確認', font=bold(42), fill='white')
    chapter = int(second // 24)
    colors = ['#41d3ba', '#85baff', '#d4a4ff']
    accent = colors[chapter % 3]
    draw.rounded_rectangle((52, 182, 908, 382), 18, fill='#1b3150')
    draw.text((82, 207), '72 seconds / stereo + mono / 3 chapters' if complex_case else '4 seconds / stereo / single audio track', font=regular(26), fill=accent)
    draw.text((82, 265), 'A1  AAC / 48 kHz / stereo / original test melody', font=regular(23), fill='white')
    draw.text((82, 311), 'A2  AAC / 44.1 kHz / mono / different test tones' if complex_case else 'No speech, music recording, or third-party media', font=regular(23), fill='#b5c5dc')
    draw.text((52, 415), f'CHAPTER {chapter + 1:02d}     {second:02d} / {duration:02d} sec', font=bold(25), fill='white')
    draw.rounded_rectangle((52, 480, 908, 491), 5, fill='#314766')
    draw.rounded_rectangle((52, 480, 53 + int(855 * (second + 1) / duration), 491), 5, fill=accent)
    return image


def write_audio(path, duration, sample_rate, channels, alternate=False):
    notes = [261.63, 329.63, 392.0, 523.25, 440.0, 392.0, 329.63, 293.66]
    with wave.open(str(path), 'wb') as out:
        out.setparams((channels, 2, sample_rate, duration * sample_rate, 'NONE', 'not compressed'))
        for second in range(duration):
            data = bytearray()
            for index in range(sample_rate):
                t = second + index / sample_rate
                phase = (t % 0.5) / 0.5
                envelope = min(1, phase * 16) * min(1, (1 - phase) * 8)
                frequency = 659.25 if alternate else notes[int(t * 2) % len(notes)]
                left = int(5000 * math.sin(2 * math.pi * frequency * t) * envelope)
                data.extend(struct.pack('<h', left))
                if channels == 2:
                    right = int(3800 * math.sin(2 * math.pi * frequency * 1.5 * t) * envelope)
                    data.extend(struct.pack('<h', right))
            out.writeframes(data)


def remove_dangling_chapter_reference(path):
    """Keep Nero chpl metadata when this FFmpeg build omits the QT chapter track.

    A free atom of identical size replaces only a reference to absent track IDs;
    all media packets, chapter metadata, and chunk offsets stay unchanged.
    """
    data = bytearray(path.read_bytes())

    def atoms(start, stop):
        while start + 8 <= stop:
            size, kind = struct.unpack_from('>I4s', data, start)
            if size < 8 or start + size > stop:
                raise ValueError('Unexpected MP4 atom')
            yield start, size, kind
            start += size

    moov = next(atom for atom in atoms(0, len(data)) if atom[2] == b'moov')
    tracks = [atom for atom in atoms(moov[0] + 8, moov[0] + moov[1]) if atom[2] == b'trak']
    track_ids = set()
    references = []
    for offset, length, _ in tracks:
        for at, size, kind in atoms(offset + 8, offset + length):
            if kind == b'tkhd':
                track_ids.add(struct.unpack_from('>I', data, at + (28 if data[at + 8] == 1 else 20))[0])
            if kind == b'tref':
                references.extend(child for child in atoms(at + 8, at + size) if child[2] == b'chap')
    for at, size, _ in references:
        referenced = struct.unpack_from('>' + 'I' * ((size - 8) // 4), data, at + 8)
        if referenced and all(track_id not in track_ids for track_id in referenced):
            data[at + 4:at + 8] = b'free'
    path.write_bytes(data)


with tempfile.TemporaryDirectory(prefix='movie-api-examples-') as temporary:
    work = Path(temporary)
    for complex_case, name, duration in [(False, 'basic', 4), (True, 'complex', 72)]:
        raw = work / f'{name}.rgb'
        with raw.open('wb') as output:
            for second in range(duration):
                picture = frame(complex_case, second, duration)
                output.write(picture.tobytes())
                if second == 0:
                    picture.save(DEST / f'{name}-source.png')
        write_audio(work / 'first.wav', duration, 48000, 2)
        arguments = [FFMPEG, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'rawvideo', '-pixel_format', 'rgb24', '-video_size', '960x540', '-framerate', '1', '-i', str(raw), '-i', str(work / 'first.wav')]
        if complex_case:
            write_audio(work / 'second.wav', duration, 44100, 1, True)
            subtitles = work / 'captions.srt'
            subtitles.write_text('1\n00:00:00,000 --> 00:00:24,000\nFirst track should be extracted.\n\n2\n00:00:24,000 --> 00:00:48,000\nSecond AAC track should be excluded.\n\n3\n00:00:48,000 --> 00:01:12,000\nSubtitles and chapters should be excluded.\n', encoding='utf-8')
            metadata = work / 'chapters.txt'
            metadata.write_text(';FFMETADATA1\ntitle=複数トラック検証 / convertX2X\nartist=convertX2X original synthetic media\n' + ''.join(f'[CHAPTER]\nTIMEBASE=1/1000\nSTART={i*24000}\nEND={(i+1)*24000}\ntitle=Chapter {i+1}\n' for i in range(3)), encoding='utf-8')
            arguments += ['-i', str(work / 'second.wav'), '-i', str(subtitles), '-i', str(metadata), '-map', '0:v', '-map', '1:a', '-map', '2:a', '-map', '3:s', '-map_metadata', '4', '-map_chapters', '4', '-c:s', 'mov_text', '-metadata:s:a:0', 'language=jpn', '-metadata:s:a:0', 'title=Main stereo test melody', '-metadata:s:a:1', 'language=eng', '-metadata:s:a:1', 'title=Alternate mono test tones', '-metadata:s:s:0', 'language=eng']
        else:
            arguments += ['-map', '0:v', '-map', '1:a']
        arguments += ['-c:v', 'mpeg4', '-q:v', '3', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a:0', '160k']
        if complex_case:
            arguments += ['-b:a:1', '96k']
        arguments += ['-movflags', '+faststart', str(DEST / f'{name}-input.mp4')]
        subprocess.run(arguments, check=True)
        if complex_case:
            remove_dangling_chapter_reference(DEST / f'{name}-input.mp4')
    print('Created original input media and source posters in', DEST)
