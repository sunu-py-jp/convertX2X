#!/usr/bin/env python3
"""Exercise the browser playground against isolated Functions and Azurite hosts.

Build with ./mvnw package first. Requires Node, Playwright, and a browser; set
NODE_PATH when Playwright is installed outside this repository. Set
PLAYWRIGHT_CHANNEL=chrome to use an installed Chrome instead of bundled Chromium.
"""

import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import struct
import subprocess
import tempfile
import zipfile

from async_settings import derive_settings
from test_async_e2e import FIXTURE_SOURCE, ROOT, port_ready, request, stop, wait_until


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=7081)
    parser.add_argument("--disabled-port", type=int, default=7082)
    parser.add_argument("--blob-port", type=int, default=12000)
    parser.add_argument("--queue-port", type=int, default=12001)
    parser.add_argument("--table-port", type=int, default=12002)
    args = parser.parse_args()
    for tool in ("azurite", "func", "java", "node"):
        if not shutil.which(tool):
            parser.error(f"Required command not found: {tool}")
    package = ROOT / "target" / "azure-functions" / "slide2image-local"
    if not (package / "host.json").exists():
        parser.error("Build the Functions package with ./mvnw package first.")
    for port in (args.port, args.disabled_port, args.blob_port, args.queue_port, args.table_port):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))

    work = Path(tempfile.mkdtemp(prefix="slide2image-playground-e2e-"))
    print(f"Isolated playground E2E workspace: {work}", flush=True)
    fixture = work / "CreateFixtures.java"
    fixture.write_text(FIXTURE_SOURCE, encoding="utf-8")
    subprocess.run(["java", "-Djava.awt.headless=true", "--class-path", str(package / "lib" / "*"),
                    str(fixture), str(work)], check=True, stdout=subprocess.DEVNULL)
    account = "playgroundtest"
    emulator_key = base64.b64encode(secrets.token_bytes(64)).decode("ascii")
    connection = (f"DefaultEndpointsProtocol=http;AccountName={account};AccountKey={emulator_key};"
                  f"BlobEndpoint=http://127.0.0.1:{args.blob_port}/{account};"
                  f"QueueEndpoint=http://127.0.0.1:{args.queue_port}/{account};"
                  f"TableEndpoint=http://127.0.0.1:{args.table_port}/{account};")
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        properties = subprocess.run(["java", "-XshowSettings:properties", "-version"],
                                    capture_output=True, text=True, check=True)
        for line in properties.stderr.splitlines():
            key, separator, value = line.strip().partition(" = ")
            if separator and key == "java.home":
                java_home = value
                break
    if not java_home:
        raise RuntimeError("JAVA_HOME could not be determined.")

    children, handles, settings_files = [], [], []
    try:
        azurite_log = (work / "azurite.log").open("w")
        handles.append(azurite_log)
        emulator_env = os.environ.copy()
        emulator_env["AZURITE_ACCOUNTS"] = f"{account}:{emulator_key}"
        children.append(subprocess.Popen([
            "azurite", "--location", str(work / "storage"), "--silent", "--disableTelemetry",
            "--skipApiVersionCheck", "--blobHost", "127.0.0.1", "--queueHost", "127.0.0.1",
            "--tableHost", "127.0.0.1", "--blobPort", str(args.blob_port),
            "--queuePort", str(args.queue_port), "--tablePort", str(args.table_port)],
            env=emulator_env, stdout=azurite_log, stderr=subprocess.STDOUT, start_new_session=True))
        wait_until(lambda: port_ready(args.table_port), children, timeout=20)

        for label, port, enabled in (("enabled", args.port, True), ("disabled", args.disabled_port, False)):
            stage = work / f"functions-{label}"
            shutil.copytree(package, stage)
            settings = derive_settings(connection if enabled else "")
            settings.update({"FUNCTIONS_WORKER_RUNTIME": "java", "AzureWebJobsStorage": connection,
                             "JAVA_OPTS": "-Djava.awt.headless=true", "JAVA_HOME": java_home,
                             "AZURE_CORE_COLLECT_TELEMETRY": "false"})
            settings_file = stage / "local.settings.json"
            settings_file.write_text(json.dumps({"IsEncrypted": False, "Values": settings}), encoding="utf-8")
            settings_file.chmod(0o600)
            settings_files.append(settings_file)
            host_log = (work / f"functions-{label}.log").open("w")
            handles.append(host_log)
            host_env = os.environ.copy()
            host_env.update(settings)
            children.append(subprocess.Popen(["func", "start", "--port", str(port)], cwd=stage,
                            env=host_env, stdout=host_log, stderr=subprocess.STDOUT, start_new_session=True))
            base = f"http://127.0.0.1:{port}"
            wait_until(lambda: request(base + "/api/playground/config")[0] == 200, children)

        subprocess.run(["node", str(ROOT / "scripts" / "test_playground_browser.cjs"),
                        f"http://127.0.0.1:{args.port}", f"http://127.0.0.1:{args.disabled_port}", str(work)],
                       cwd=ROOT, check=True)
        for filename in ("sync-pptx.zip", "async-pdf.zip"):
            with zipfile.ZipFile(work / filename) as archive:
                assert archive.namelist() == ["page-0001.png", "page-0002.png"]
                for name in archive.namelist():
                    image = archive.read(name)
                    assert image[:8] == b"\x89PNG\r\n\x1a\n"
                    assert struct.unpack(">II", image[16:24]) == (533, 267)
        print("PASS browser downloads: ZIP archives contain two source-sized 533x267 PNG pages", flush=True)
        print(f"Browser report and screenshots: {work}", flush=True)
    finally:
        for process in reversed(children):
            stop(process)
        for handle in handles:
            handle.close()
        for settings_file in settings_files:
            settings_file.unlink(missing_ok=True)
        print("Stopped isolated playground Functions hosts and Azurite.", flush=True)


if __name__ == "__main__":
    main()
