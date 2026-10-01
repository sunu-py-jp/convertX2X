#!/usr/bin/env python3
"""Build and run office2pdf on port 7074, deriving queue switches before host startup."""

import argparse
import json
import os
from pathlib import Path
import signal
import shutil
import socket
import subprocess
import sys
import tempfile
import time

from async_settings import STORAGE_KEY, derive_settings, obsolete_keys

ROOT = Path(__file__).resolve().parents[1]
WINDOWS = os.name == "nt"
AZURITE_CONNECTION = "UseDevelopmentStorage=true"
AZURITE_PORTS = (10000, 10001)


def find_tool(name: str) -> str | None:
    """Resolve Windows command shims explicitly."""
    candidates = (f"{name}.exe", f"{name}.cmd", name) if WINDOWS else (name,)
    for candidate in candidates:
        executable = shutil.which(candidate)
        if executable:
            return str(Path(executable).resolve())
    return None


def run_command(command: list[str], **kwargs) -> subprocess.CompletedProcess:
    """Use cmd.exe for Windows batch launchers; arguments are fixed flags or an integer port."""
    batch = WINDOWS and Path(command[0]).suffix.lower() in (".cmd", ".bat")
    if batch:
        # Always quote the path, including paths with spaces or shell metacharacters.
        arguments = f'"{command[0]}" ' + subprocess.list2cmdline(command[1:])
    else:
        arguments = command
    return subprocess.run(arguments, shell=batch, **kwargs)


def popen_command(command: list[str], **kwargs) -> subprocess.Popen:
    batch = WINDOWS and Path(command[0]).suffix.lower() in (".cmd", ".bat")
    arguments = f'"{command[0]}" ' + subprocess.list2cmdline(command[1:]) if batch else command
    return subprocess.Popen(arguments, shell=batch, **kwargs)


def ports_ready() -> bool:
    for port in AZURITE_PORTS:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=.25):
                pass
        except OSError:
            return False
    return True


def wait_for_azurite(process: subprocess.Popen, timeout: float = 15) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if ports_ready():
            return
        if process.poll() is not None:
            break
        time.sleep(.1)
    raise OSError("Azurite did not start on ports 10000 and 10001.")


def stop_process(process: subprocess.Popen | None) -> None:
    if not process or process.poll() is not None:
        return
    if WINDOWS:
        taskkill = find_tool("taskkill")
        if taskkill:
            subprocess.run([taskkill, "/pid", str(process.pid), "/t", "/f"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def prepare_azurite(environment: dict[str, str], work: Path) -> subprocess.Popen | None:
    selected = (environment.get("AzureWebJobsStorage", "").strip().lower() == AZURITE_CONNECTION.lower()
                or environment.get(STORAGE_KEY, "").strip().lower() == AZURITE_CONNECTION.lower())
    if not selected:
        return None
    process = None
    if not ports_ready():
        executable = find_tool("azurite")
        if not executable:
            raise OSError("Azurite is required when UseDevelopmentStorage=true.")
        log = work / "azurite.log"
        with log.open("w", encoding="utf-8") as output:
            process = popen_command([executable, "--location", str(work / "data"), "--silent",
                                     "--disableTelemetry", "--skipApiVersionCheck"],
                                    cwd=ROOT, env=environment, stdout=output,
                                    stderr=subprocess.STDOUT, start_new_session=not WINDOWS)
        try:
            wait_for_azurite(process)
        except Exception:
            stop_process(process)
            raise
        print("Azurite: started locally", flush=True)
    else:
        print("Azurite: using the existing local instance", flush=True)
    return process


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
    parser.add_argument("--port", type=int, default=7074)
    args = parser.parse_args()
    func = find_tool("func")
    if not func:
        parser.error("Azure Functions Core Tools v4 is required (func command).")
    wrapper = ROOT / ("mvnw.cmd" if WINDOWS else "mvnw")
    step = "Maven build"
    try:
        if not args.skip_build:
            run_command([str(wrapper), "-B", "-ntp", "package"], cwd=ROOT, check=True)

        step = "local settings preparation"
        stage = ROOT / "target" / "azure-functions" / "office2pdf-local"
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
        with tempfile.TemporaryDirectory(prefix="office2pdf-local-") as temporary:
            step = "Azurite startup"
            azurite = prepare_azurite(child_env, Path(temporary))
            try:
                if child_env.get(STORAGE_KEY, "").strip().lower() == AZURITE_CONNECTION.lower() \
                        and child_env.get("CONVERSION_CREATE_RESOURCES", "true").strip().lower() != "false":
                    step = "local Queue/Blob preparation"
                    jars = list(stage.glob("office2pdf-*.jar"))
                    if len(jars) != 1:
                        raise OSError("Packaged application JAR was not found.")
                    java = Path(child_env["JAVA_HOME"]) / "bin" / ("java.exe" if WINDOWS else "java")
                    classpath = os.pathsep.join((str(jars[0]), str(stage / "lib" / "*")))
                    run_command([str(java), "-cp", classpath,
                                 "com.convertx2x.office2pdf.LocalStoragePreparation"],
                                cwd=stage, env=child_env, check=True)
                    print("Local Queue/Blob resources: ready", flush=True)
                step = "Functions host startup"
                command = [func, "start", "--port", str(args.port)]
                return run_command(command, cwd=stage, env=child_env).returncode
            finally:
                stop_process(azurite)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Local startup failed during {step} ({error_summary(error)}).", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
