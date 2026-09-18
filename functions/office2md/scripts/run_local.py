#!/usr/bin/env python3
"""Build and run office2md on port 7072, deriving queue switches before host startup."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from async_settings import STORAGE_KEY, derive_settings, obsolete_keys

ROOT = Path(__file__).resolve().parents[1]
WINDOWS = os.name == "nt"


def run_command(command: list[str], **kwargs) -> subprocess.CompletedProcess:
    """Use cmd.exe for Windows batch launchers; arguments are fixed flags or an integer port."""
    batch = WINDOWS and Path(command[0]).suffix.lower() in (".cmd", ".bat")
    if batch:
        # Always quote the path, including paths with spaces or shell metacharacters.
        arguments = f'"{command[0]}" ' + subprocess.list2cmdline(command[1:])
    else:
        arguments = command
    return subprocess.run(arguments, shell=batch, **kwargs)


def error_summary(error: Exception) -> str:
    """Show diagnostic codes without echoing commands, settings or exception payloads."""
    details = [type(error).__name__]
    if isinstance(error, OSError):
        if getattr(error, "winerror", None) is not None:
            details.append(f"WinError {error.winerror}")
        if error.errno is not None:
            details.append(f"errno {error.errno}")
    if isinstance(error, subprocess.CalledProcessError):
        details.append(f"exit code {error.returncode}")
    return "; ".join(details)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--port", type=int, default=7072)
    args = parser.parse_args()
    func = shutil.which("func")
    if not func:
        parser.error("Azure Functions Core Tools v4 is required (func command).")
    func = str(Path(func).resolve())
    wrapper = ROOT / ("mvnw.cmd" if WINDOWS else "mvnw")
    step = "Maven build"
    try:
        if not args.skip_build:
            run_command([str(wrapper), "-B", "-ntp", "package"], cwd=ROOT, check=True)

        step = "local settings preparation"
        stage = ROOT / "target" / "azure-functions" / "office2md-local"
        if not (stage / "host.json").is_file():
            build_command = ".\\mvnw.cmd" if WINDOWS else "./mvnw"
            parser.error(f"Function package is missing. Run {build_command} package first.")
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
        # Include environment-only MI, alias and retention settings before deriving trigger switches.
        values.update({key: value for key, value in os.environ.items() if key.startswith("CONVERSION_") or key.startswith("AzureWebJobsStorage__")})
        derived = derive_settings(connection, values)
        values.update(derived)
        for key in obsolete_keys(derived): values.pop(key, None)
        values["FUNCTIONS_WORKER_RUNTIME"] = "java"
        values["JAVA_OPTS"] = os.environ.get(
            "JAVA_OPTS", values.get("JAVA_OPTS", "-Djava.awt.headless=true")
        )
        host_storage = os.environ.get("AzureWebJobsStorage", values.get("AzureWebJobsStorage", ""))
        # Queue listeners also need host storage; use the selected storage locally by default.
        values["AzureWebJobsStorage"] = host_storage.strip() or connection.strip()
        stage_settings = stage / "local.settings.json"
        descriptor = os.open(stage_settings, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            os.chmod(stage_settings, 0o600)
            json.dump(settings, output, indent=2)
            output.write("\n")

        child_env = os.environ.copy()
        child_env.update({key: str(value) for key, value in values.items()})
        for key in obsolete_keys(derived): child_env.pop(key, None)
        step = "Java detection"
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
        print(f"Async conversion: {'enabled' if derived['AzureWebJobs.ProcessConversion.Disabled'] == 'false' else 'disabled'}", flush=True)
        print(f"Playground: http://localhost:{args.port}/api/playground", flush=True)
        step = "Functions host startup"
        command = [func, "start", "--port", str(args.port)]
        if WINDOWS:
            return run_command(command, cwd=stage, env=child_env).returncode
        os.chdir(stage)
        os.execvpe(func, command, child_env)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Local startup failed during {step} ({error_summary(error)}).", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
