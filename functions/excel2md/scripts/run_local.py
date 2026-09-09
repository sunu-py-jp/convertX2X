#!/usr/bin/env python3
"""Build and run excel2md on port 7072, deriving queue switches before host startup."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from async_settings import STORAGE_KEY, derive_settings

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--port", type=int, default=7072)
    args = parser.parse_args()
    if not shutil.which("func"):
        parser.error("Azure Functions Core Tools v4 is required (func command).")
    if not args.skip_build:
        subprocess.run([str(ROOT / "mvnw"), "-B", "-ntp", "package"], cwd=ROOT, check=True)

    stage = ROOT / "target" / "azure-functions" / "excel2md-local"
    if not (stage / "host.json").is_file():
        parser.error("Function package is missing. Run ./mvnw package first.")
    source = ROOT / "local.settings.json"
    if not source.exists():
        source = ROOT / "local.settings.example.json"
    settings = json.loads(source.read_text(encoding="utf-8"))
    if settings.get("IsEncrypted"):
        parser.error("Decrypt local.settings.json before using this launcher.")
    values = settings.setdefault("Values", {})
    for key in values:
        values[key] = os.environ.get(key, values[key])
    connection = os.environ.get(STORAGE_KEY, values.get(STORAGE_KEY, ""))
    values.update(derive_settings(connection))
    values["FUNCTIONS_WORKER_RUNTIME"] = "java"
    values["JAVA_OPTS"] = os.environ.get(
        "JAVA_OPTS", values.get("JAVA_OPTS", "-Djava.awt.headless=true")
    )
    host_storage = os.environ.get("AzureWebJobsStorage", values.get("AzureWebJobsStorage", ""))
    # Queue listeners also need host storage; use the selected storage locally by default.
    values["AzureWebJobsStorage"] = host_storage.strip() or connection.strip()
    stage_settings = stage / "local.settings.json"
    descriptor = os.open(stage_settings, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.chmod(stage_settings, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(settings, output, indent=2)
        output.write("\n")

    child_env = os.environ.copy()
    child_env.update({key: str(value) for key, value in values.items()})
    if not child_env.get("JAVA_HOME"):
        java_settings = subprocess.run(
            ["java", "-XshowSettings:properties", "-version"],
            capture_output=True, text=True, check=True,
        )
        for line in java_settings.stderr.splitlines():
            key, separator, value = line.strip().partition(" = ")
            if separator and key == "java.home":
                child_env["JAVA_HOME"] = value
                break
        if not child_env.get("JAVA_HOME"):
            parser.error("Set JAVA_HOME to the Java 21 JDK directory.")
    print(f"Async conversion: {'enabled' if connection.strip() else 'disabled'}", flush=True)
    print(f"Playground: http://localhost:{args.port}/api/playground", flush=True)
    os.chdir(stage)
    os.execvpe("func", ["func", "start", "--port", str(args.port)], child_env)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        # Do not echo settings or subprocess commands containing configuration.
        print(f"Local startup failed ({type(error).__name__}).", file=sys.stderr)
        sys.exit(1)
