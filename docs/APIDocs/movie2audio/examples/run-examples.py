#!/usr/bin/env python3
"""Run the real local HTTP API and verify the returned AAC packets.

Start Movie2Audio on localhost:7073, then run this script from any directory.
No media conversion is implemented here: POST /api/convert does all extraction.
"""
import hashlib
import json
import platform
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
PROBE = ROOT / 'functions/movie2audio/resources/ffmpeg/macos-aarch64/ffprobe'
if platform.system() == 'Linux':
    PROBE = ROOT / 'functions/movie2audio/resources/ffmpeg/linux-x86_64/ffprobe'
BASE = 'http://localhost:7073/api/'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def describe(path):
    result = json.loads(subprocess.check_output([str(PROBE), '-v', 'error', '-show_format', '-show_streams', '-show_chapters', '-of', 'json', str(path)]))
    result['format']['filename'] = path.name
    return result


def packets(path):
    result = json.loads(subprocess.check_output([str(PROBE), '-v', 'error', '-select_streams', 'a:0', '-show_packets', '-show_entries', 'packet=data_hash,size', '-show_data_hash', 'sha256', '-of', 'json', str(path)]))['packets']
    hashes = [packet['data_hash'] for packet in result]
    return {'count': len(hashes), 'payloadBytes': sum(int(packet['size']) for packet in result), 'orderedPacketHashesSha256': sha('\n'.join(hashes).encode())}


run = {'capturedAt': datetime.now(timezone.utc).isoformat(), 'environment': {'location': 'local macOS Functions Core Tools; not Azure', 'os': platform.system(), 'architecture': platform.machine(), 'python': platform.python_version(), 'ffprobe': subprocess.check_output([str(PROBE), '-version'], text=True).splitlines()[0]}, 'cases': []}
for name in ['basic', 'complex']:
    source = HERE / f'{name}-input.mp4'
    request = urllib.request.Request(BASE + 'convert', source.read_bytes(), {'Content-Type': 'application/octet-stream'}, method='POST')
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=210) as response:
        audio = response.read()
        status = response.status
        headers = {key.lower(): value for key, value in response.headers.items() if key.lower() in ['content-type', 'x-audio-codec', 'x-audio-mode', 'content-disposition']}
    elapsed = round((time.perf_counter() - started) * 1000, 2)
    target = HERE / f'{name}-audio.m4a'
    target.write_bytes(audio)
    input_probe, output_probe = describe(source), describe(target)
    (HERE / f'{name}-probe.json').write_text(json.dumps({'input': input_probe, 'output': output_probe}, ensure_ascii=False, indent=2) + '\n')
    source_packets, target_packets = packets(source), packets(target)
    assert source_packets == target_packets, 'Every selected AAC packet must be byte-identical.'
    assert len(output_probe['streams']) == 1 and output_probe['streams'][0]['codec_name'] == 'aac'
    assert not output_probe['chapters']
    run['cases'].append({'case': name, 'request': {'method': 'POST', 'path': '/api/convert', 'contentType': 'application/octet-stream'}, 'httpStatus': status, 'responseHeaders': headers, 'elapsedMsIncludingTransfer': elapsed, 'input': {'file': source.name, 'sizeBytes': source.stat().st_size, 'sha256': sha(source.read_bytes()), 'durationSeconds': float(input_probe['format']['duration']), 'streams': [{key: stream.get(key) for key in ['index', 'codec_type', 'codec_name', 'sample_rate', 'channels']} for stream in input_probe['streams']], 'chapters': len(input_probe['chapters'])}, 'output': {'file': target.name, 'sizeBytes': len(audio), 'sha256': sha(audio), 'durationSeconds': float(output_probe['format']['duration'])}, 'verification': {'inputFirstAudioPackets': source_packets, 'outputPackets': target_packets, 'allSelectedAacPacketPayloadsIdentical': True, 'oneAudioStreamOnly': True, 'noChapters': True}})

for name, fixture, expected in [('first-pcm-second-aac', 'first-pcm-second-aac.mkv', 'UNSUPPORTED_AUDIO_CODEC'), ('silent', 'silent-video.mp4', 'NO_AUDIO_STREAM')]:
    source = HERE / fixture
    shutil.copyfile(ROOT / 'functions/movie2audio/test/fixtures' / fixture, source)
    request = urllib.request.Request(BASE + 'convert', source.read_bytes(), {'Content-Type': 'application/octet-stream'}, method='POST')
    started = time.perf_counter()
    try:
        urllib.request.urlopen(request)
        raise AssertionError('Expected HTTP422')
    except urllib.error.HTTPError as error:
        body = json.loads(error.read())
        assert error.code == 422 and body['error']['code'] == expected
        (HERE / f'{name}-error.json').write_text(json.dumps(body, indent=2) + '\n')
        run['cases'].append({'case': name, 'request': {'method': 'POST', 'path': '/api/convert'}, 'input': {'file': fixture, 'sizeBytes': source.stat().st_size, 'sha256': sha(source.read_bytes())}, 'httpStatus': error.code, 'elapsedMsIncludingTransfer': round((time.perf_counter() - started) * 1000, 2), 'response': body})
(HERE / 'run.json').write_text(json.dumps(run, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(run, ensure_ascii=False, indent=2))
