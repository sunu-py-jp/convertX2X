#!/usr/bin/env python3
"""Rebuild the packaged, network-disabled FFmpeg tools from verified official source.

Requires Python 3, curl, make and a C compiler. Docker is used for signature
verification and the Linux x64 static build. No runtime downloads are performed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import tarfile
import tempfile

VERSION = "9.0.1"
SOURCE_SHA256 = "cf38e0e28c7e5605942c4a77755349b0145804a397af37eb1fb4c77cb237f635"
KEY_SHA256 = "397b3becedcd5a98769967ff1ff8501ddc89f8368b8f766e4701377d7dbaabe5"
KEY_FINGERPRINT = "FCF986EA15E6E293A5644F10B4322F04D67658D8"
ALPINE = "alpine:3.22.2@sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412"
MODULE = Path(__file__).resolve().parents[1]
THIRD_PARTY = MODULE / "third-party" / "ffmpeg"
RESOURCES = MODULE / "resources" / "ffmpeg"
FLAGS = [
    "--disable-autodetect", "--disable-network", "--disable-everything",
    "--disable-doc", "--disable-ffplay", "--disable-avdevice", "--disable-debug",
    "--disable-asm", "--disable-iamf", "--enable-small", "--enable-protocol=file",
    "--enable-demuxer=mov,matroska,avi,mpegts,flv,wav", "--enable-muxer=ipod,wav",
    "--enable-parser=aac,aac_latm,mpegaudio,opus",
    "--enable-decoder=aac,opus,mp3,mp3float,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_u8,pcm_f32le,pcm_f64le",
    "--enable-encoder=aac,pcm_s16le", "--enable-filter=aresample,aformat,anull",
    "--enable-bsf=aac_adtstoasc",
]
NOTICES = [
    ("LICENSE-musl.txt", "https://git.musl-libc.org/cgit/musl/plain/COPYRIGHT?h=v1.2.5",
     "f9bc4423732350eb0b3f7ed7e91d530298476f8fec0c6c427a1c04ade22655af"),
    ("COPYING.GCC-RUNTIME", "https://raw.githubusercontent.com/gcc-mirror/gcc/releases/gcc-14.2.0/COPYING.RUNTIME",
     "9d6b43ce4d8de0c878bf16b54d8e7a10d9bd42b75178153e3af6a815bdc90f74"),
    ("COPYING.GPLv3", "https://raw.githubusercontent.com/gcc-mirror/gcc/releases/gcc-14.2.0/COPYING3",
     "8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903"),
]


def run(args, **kwargs):
    print("+", " ".join(str(arg) for arg in args), flush=True)
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def download(name, url, expected=None):
    path = THIRD_PARTY / name
    if not path.exists():
        temporary = path.with_suffix(path.suffix + ".partial")
        try:
            run(["curl", "--fail", "--location", "--proto", "=https", "--tlsv1.2",
                 "--max-time", "180", "--silent", "--show-error", url, "--output", temporary])
            if expected and sha256(temporary) != expected:
                raise RuntimeError("Source download checksum mismatch: " + name)
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    if expected and sha256(path) != expected:
        raise RuntimeError("Source checksum mismatch: " + name)
    return path


def docker_run(work, shell):
    run(["docker", "run", "--rm", "--platform", "linux/amd64",
         "--mount", f"type=bind,source={work},target=/work", "-w", "/work",
         ALPINE, "sh", "-ec", shell])


def verify_signature(work, source, signature, key):
    for path in (source, signature, key):
        shutil.copy2(path, work / path.name)
    docker_run(work, f"""apk add --no-cache gnupg > /work/signature-packages.log
mkdir -m 700 /work/gnupg
gpg --homedir /work/gnupg --batch --import /work/{key.name}
gpg --homedir /work/gnupg --batch --status-fd 1 --verify /work/{signature.name} /work/{source.name} > /work/signature-status.txt
grep -q '^\\[GNUPG:\\] VALIDSIG {KEY_FINGERPRINT} ' /work/signature-status.txt
""")
    shutil.copy2(work / "signature-status.txt", THIRD_PARTY / "signature-status.txt")


def package(work, target, tools, flags, build_metadata):
    destination = RESOURCES / target
    destination.mkdir(parents=True, exist_ok=True)
    manifest = {"version": VERSION, "sourceSha256": SOURCE_SHA256,
                "target": target, "configure": flags, "build": build_metadata, "binaries": {}}
    for name in ("ffmpeg", "ffprobe"):
        shutil.copy2(tools / name, destination / name)
        (destination / name).chmod(0o755)
        digest = sha256(destination / name)
        (destination / (name + ".sha256")).write_text(digest + "\n")
        manifest["binaries"][name] = {"sha256": digest, "bytes": (destination / name).stat().st_size}
    (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("Packaged", target, json.dumps(manifest["binaries"]), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["all", "linux-x86_64", "macos-aarch64"], default="all")
    parser.add_argument("--jobs", type=int, default=min(os.cpu_count() or 2, 8))
    args = parser.parse_args()
    if not 1 <= args.jobs <= 64:
        parser.error("--jobs must be 1–64")
    THIRD_PARTY.mkdir(parents=True, exist_ok=True)
    RESOURCES.mkdir(parents=True, exist_ok=True)
    source = download(f"ffmpeg-{VERSION}.tar.xz", f"https://ffmpeg.org/releases/ffmpeg-{VERSION}.tar.xz", SOURCE_SHA256)
    signature = download(source.name + ".asc", f"https://ffmpeg.org/releases/{source.name}.asc")
    key = download("ffmpeg-devel.asc", "https://ffmpeg.org/ffmpeg-devel.asc", KEY_SHA256)
    for name, url, digest in NOTICES:
        download(name, url, digest)
    with tempfile.TemporaryDirectory(prefix="movie2audio-ffmpeg-build-") as temporary:
        work = Path(temporary)
        verify_signature(work, source, signature, key)
        with tarfile.open(source, "r:xz") as archive:
            archive.extractall(work, filter="data")
        src = work / ("ffmpeg-" + VERSION)
        for name in ["COPYING.LGPLv2.1", "LICENSE.md"]:
            shutil.copy2(src / name, THIRD_PARTY / name)
        if args.target in ("all", "macos-aarch64"):
            if platform.system() != "Darwin" or platform.machine() not in ("arm64", "aarch64"):
                raise RuntimeError("macos-aarch64 must be built on an Apple Silicon Mac")
            build = work / "macos-aarch64"
            build.mkdir()
            run([src / "configure", *FLAGS], cwd=build)
            run(["make", f"-j{args.jobs}", "ffmpeg", "ffprobe"], cwd=build)
            run([build / "ffmpeg", "-hide_banner", "-protocols"])
            package(work, "macos-aarch64", build, FLAGS,
                    {"os": platform.platform(), "compiler": subprocess.check_output(["clang", "--version"], text=True).splitlines()[0]})
        if args.target in ("all", "linux-x86_64"):
            # All configure arguments are fixed constants, not input parameters.
            # musl defaults to small pthread stacks. AAC's ADTS bitstream filter
            # needs more stack when the FFmpeg demux worker processes MPEG-TS.
            flags = FLAGS + ["--extra-ldflags=-static -Wl,-z,stack-size=8388608"]
            fixture = MODULE / "test/fixtures/aac-video.ts"
            if fixture.is_file():
                shutil.copy2(fixture, work / "smoke-input.ts")
            shell = "\n".join([
                "apk add --no-cache build-base linux-headers > /work/linux-packages.log",
                "mkdir -p /work/linux-x86_64",
                "cd /work/linux-x86_64",
                "/work/ffmpeg-" + VERSION + "/configure " + shlex.join(flags),
                f"make -j{args.jobs} ffmpeg ffprobe",
                "./ffmpeg -hide_banner -protocols",
                "./ffmpeg -L > /work/linux-license.txt",
                "cc --version > /work/linux-compiler.txt",
                "apk list --installed musl gcc > /work/linux-toolchain.txt",
                "if [ -f /work/smoke-input.ts ]; then ./ffmpeg -nostdin -hide_banner -loglevel error -xerror -protocol_whitelist file -i /work/smoke-input.ts -map 0:a:0 -c:a copy -vn -sn -dn -f ipod /work/smoke-output.m4a; ./ffprobe -v error -show_entries stream=codec_name,sample_rate,channels -of json /work/smoke-output.m4a; fi",
            ])
            docker_run(work, shell)
            package(work, "linux-x86_64", work / "linux-x86_64", flags,
                    {"image": ALPINE, "compiler": (work / "linux-compiler.txt").read_text().splitlines()[0],
                     "packages": (work / "linux-toolchain.txt").read_text().splitlines()})
    print("Rebuild complete; source archive and matching notices are retained in", THIRD_PARTY)


if __name__ == "__main__":
    main()
