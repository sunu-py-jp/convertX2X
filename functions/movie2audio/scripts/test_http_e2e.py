#!/usr/bin/env python3
"""Exercise a running Functions host with real videos and compare AAC packet hashes.

Start scripts/run_local.py first. URL success is opt-in via MOVIE_TEST_VIDEO_URL;
its host must be configured in that server's CONVERSION_URL_ALLOWED_HOSTS.
No Azure deployment or upload to any third-party service is performed.
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import platform
import struct
import subprocess
import tempfile
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "test/fixtures"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:7073/api")
    parser.add_argument("--large-input", action="store_true", help="Also send a valid video padded to exactly 100 MiB")
    args = parser.parse_args()
    base = urlsplit(args.base)
    if base.scheme not in ("http", "https") or not base.hostname or base.query or base.fragment or base.username:
        parser.error("--base must be an HTTP(S) API base URL without credentials or a query")
    target = "macos-aarch64" if platform.system() == "Darwin" else "linux-x86_64"
    probe = ROOT / "resources/ffmpeg" / target / "ffprobe"
    results = []

    def request(method, route, body=None, content_type="application/octet-stream", *, chunked=False):
        connection_type = http.client.HTTPSConnection if base.scheme == "https" else http.client.HTTPConnection
        connection = connection_type(base.hostname, base.port, timeout=220)
        headers = {"Content-Type": content_type}
        if os.environ.get("FUNCTIONS_HOST_KEY"):
            headers["x-functions-key"] = os.environ["FUNCTIONS_HOST_KEY"]
        if isinstance(body, Path):
            headers["Content-Length"] = str(body.stat().st_size)
            source = body.open("rb")
        else:
            source = body
        started = time.monotonic()
        try:
            connection.request(method, base.path.rstrip("/") + route, body=source, headers=headers, encode_chunked=chunked)
            response = connection.getresponse()
            status = response.status
            response_headers = {k.lower(): v for k, v in response.getheaders()}
            data = response.read()
            return status, response_headers, data, round((time.monotonic() - started) * 1000)
        finally:
            if isinstance(body, Path):
                source.close()
            connection.close()

    def hashes(path):
        output = subprocess.check_output([str(probe), "-v", "error", "-select_streams", "a:0",
                                          "-show_packets", "-show_data_hash", "sha256",
                                          "-show_entries", "packet=data_hash", "-of", "json", str(path)], text=True)
        return [p["data_hash"] for p in json.loads(output)["packets"]]

    def error(route, body, content_type, status, code):
        actual, _, data, _ = request("POST", route, body, content_type)
        assert actual == status, f"{route}: expected {status}, received {actual}"
        assert json.loads(data)["error"]["code"] == code, f"{route}: expected {code}"
        results.append({"case": code, "status": actual})

    status, _, raw, _ = request("GET", "/capabilities")
    assert status == 200
    capabilities = json.loads(raw)
    assert set(capabilities) == {"urlEnabled", "asyncEnabled", "maxInputBytes", "maxOutputBytes", "timeoutSeconds",
                                 "outputFormat", "audioCodec", "audioMode"}
    assert capabilities["audioMode"] == "copy" and capabilities["audioCodec"] == "aac"
    with tempfile.TemporaryDirectory(prefix="movie2audio-http-test-") as directory:
        directory = Path(directory)
        source = FIXTURES / "aac-video.mp4"
        status, headers, data, elapsed = request("POST", "/convert", source)
        assert status == 200, f"AAC upload returned {status}"
        assert headers["content-type"] == "audio/mp4"
        assert headers["x-audio-codec"] == "aac" and headers["x-audio-mode"] == "copy"
        assert headers["content-disposition"] == 'attachment; filename="audio.m4a"'
        output = directory / "audio.m4a"
        output.write_bytes(data)
        original = hashes(source)
        assert original and hashes(output) == original, "AAC packets changed"
        streams = json.loads(subprocess.check_output([str(probe), "-v", "error", "-show_entries",
                                                      "stream=codec_type,codec_name", "-of", "json", str(output)], text=True))["streams"]
        assert streams == [{"codec_name": "aac", "codec_type": "audio"}]
        results.append({"case": "upload", "inputBytes": source.stat().st_size, "outputBytes": len(data),
                        "elapsedMs": elapsed, "identicalAacPackets": len(original)})
        raw_video = source.read_bytes()
        status, headers, data, elapsed = request("POST", "/convert",
                iter(raw_video[i:i + 4096] for i in range(0, len(raw_video), 4096)), chunked=True)
        assert status == 200, f"Chunked upload returned {status}"
        output.write_bytes(data)
        assert hashes(output) == original
        results.append({"case": "chunked upload without Content-Length", "outputBytes": len(data),
                        "elapsedMs": elapsed, "responseTransferEncoding": headers.get("transfer-encoding")})
        error("/convert", FIXTURES / "silent-video.mp4", "application/octet-stream", 422, "NO_AUDIO_STREAM")
        error("/convert", FIXTURES / "pcm-video.mkv", "application/octet-stream", 422, "UNSUPPORTED_AUDIO_CODEC")
        error("/convert", FIXTURES / "audio-only.m4a", "application/octet-stream", 422, "NO_VIDEO_STREAM")
        error("/convert", b"not a video", "application/octet-stream", 422, "INVALID_MEDIA")
        error("/convert", b"", "application/octet-stream", 400, "EMPTY_INPUT")
        error("/convert", b"not-a-form", "multipart/form-data; boundary=test", 415, "RAW_BODY_REQUIRED")
        error("/convert-url", b'{"url":"https://example.com/a","url":"https://example.com/b"}',
              "application/json", 400, "INVALID_URL_REQUEST")
        error("/convert-url", b'{"url":"https://example.com/a","headers":{}}',
              "application/json", 400, "INVALID_URL_REQUEST")
        error("/convert-url", b'{"url":"https://example.com/a","Headers":{"Content-Type":"text/plain"}}',
              "application/json", 400, "INVALID_URL_REQUEST")
        error("/convert-url", b'{"url":"https://example.com/a"} {}',
              "application/json", 400, "INVALID_URL_REQUEST")
        error("/convert-url", b"x" * (16 * 1024 + 1), "application/json", 413, "REQUEST_TOO_LARGE")
        error("/convert-to-blob", b"video", "application/octet-stream", 415, "STORAGE_REQUEST_TYPE_REQUIRED")
        status, _, data, _ = request("POST", "/convert-to-blob", b"{}", "application/json")
        storage_enabled = status == 400 and json.loads(data)["error"]["code"] == "INVALID_STORAGE_REQUEST"
        if not storage_enabled:
            assert status == 503 and json.loads(data)["error"]["code"] == "OUTPUT_STORAGE_DISABLED"
            results.append({"case": "OUTPUT_STORAGE_DISABLED", "status": status})
        else:
            results.append({"case": "Blob API enabled; no external upload", "status": status})
            for body in (b'{"output":{"sasUrl":"invalid"},"headers":{}}',
                         b'{"input":{"url":"https://example.com/a"},"output":{"sasUrl":"invalid"},"Headers":{}}',
                         b'{"input":{"url":"https://example.com/a"},"output":{"sasUrl":"first","sasUrl":"second"}}'):
                error("/convert-to-blob", body, "application/json", 400, "INVALID_STORAGE_REQUEST")
            error("/convert-to-blob", b"x" * (32 * 1024 + 1), "application/json", 413, "REQUEST_TOO_LARGE")
            error("/convert-to-blob", json.dumps({"input": {"url": "https://example.com/a"},
                  "output": {"sasUrl": "not-a-url"}}).encode(), "application/json", 400, "INVALID_OUTPUT_SAS_URL")
            boundary = "movie2audio-http-storage-test"
            metadata = json.dumps({"output": {"sasUrl": "not-a-url"}}).encode()
            # An intentionally invalid destination exercises actual multipart parsing
            # without uploading to Azure or fetching any external input URL.
            parts = (f'--{boundary}\r\nContent-Disposition: form-data; name="request"; filename="request.json"\r\n'
                     'Content-Type: application/json\r\n\r\n').encode() + metadata + (
                     f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="video.mp4"\r\n'
                     'Content-Type: video/mp4\r\n\r\n').encode() + raw_video + f'\r\n--{boundary}--\r\n'.encode()
            error("/convert-to-blob", parts, f"multipart/form-data; boundary={boundary}", 400, "INVALID_OUTPUT_SAS_URL")
            status, _, data, _ = request("POST", "/convert-to-blob",
                iter(parts[i:i + 4096] for i in range(0, len(parts), 4096)),
                f"multipart/form-data; boundary={boundary}", chunked=True)
            assert status == 400 and json.loads(data)["error"]["code"] == "INVALID_OUTPUT_SAS_URL"
            results.append({"case": "chunked multipart with invalid destination", "status": status})
            error("/convert-to-blob", b"not-a-form", f"multipart/form-data; boundary={boundary}", 400, "INVALID_MULTIPART")
        if not capabilities["urlEnabled"]:
            error("/convert-url", b'{"url":"https://example.com/a"}', "application/json", 503, "URL_FETCH_DISABLED")
        else:
            error("/convert-url", b'{"url":"http://example.com/a"}', "application/json", 400, "INVALID_URL")
        video_url = os.environ.get("MOVIE_TEST_VIDEO_URL")
        if video_url:
            assert capabilities["urlEnabled"], "The server must enable the test video's URL host"
            status, headers, data, elapsed = request("POST", "/convert-url",
                    json.dumps({"url": video_url}).encode(), "application/json")
            assert status == 200, f"URL extraction returned {status}"
            assert headers["content-type"] == "audio/mp4" and headers["x-audio-mode"] == "copy"
            remote_audio = directory / "remote.m4a"
            remote_audio.write_bytes(data)
            assert hashes(remote_audio)
            results.append({"case": "url", "outputBytes": len(data), "elapsedMs": elapsed,
                            "sha256": hashlib.sha256(data).hexdigest()})
        if args.large_input:
            target_bytes = 100 * 1024 * 1024
            assert capabilities["maxInputBytes"] >= target_bytes, "Input limit is below the boundary test size"
            padded = directory / "100MiB.mp4"
            with padded.open("wb") as file:
                file.write(source.read_bytes())
                file.write(struct.pack(">I4s", target_bytes - source.stat().st_size, b"free"))
                file.truncate(target_bytes)
            status, _, data, elapsed = request("POST", "/convert", padded)
            assert status == 200, f"100 MiB upload returned {status}"
            output.write_bytes(data)
            assert hashes(output) == original
            results.append({"case": "100MiB upload", "inputBytes": target_bytes, "outputBytes": len(data), "elapsedMs": elapsed})
    print(json.dumps({"passed": True, "environment": "running Functions host", "results": results}, indent=2))


if __name__ == "__main__":
    main()
