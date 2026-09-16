#!/usr/bin/env python3
"""Apply the optional storage setting and matching queue switches to an existing app.

Reads connection-string or Managed Identity settings from the environment.
Unset/blank disables async. Unrelated Azure app settings are preserved.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from async_settings import STORAGE_KEY, derive_settings, obsolete_keys


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resource-group", required=True)
    parser.add_argument("--name", required=True, help="Existing Function App name")
    parser.add_argument("--slot")
    args = parser.parse_args()
    if not shutil.which("az"):
        parser.error("Azure CLI is required. Install it and sign in with az login.")

    # Existing remote notification/retention settings still determine maintenance when only authentication changes.
    read_command = ["az", "functionapp", "config", "appsettings", "list",
                    "--resource-group", args.resource_group, "--name", args.name, "--only-show-errors", "--output", "json"]
    if args.slot:
        read_command.extend(["--slot", args.slot])
    current = subprocess.run(read_command, capture_output=True, text=True)
    if current.returncode:
        print("Azure configuration read failed. Check CLI login and permissions.", file=sys.stderr)
        sys.exit(current.returncode)
    try:
        existing = {item["name"]: item["value"] for item in json.loads(current.stdout)}
        maintenance = {key: value for key, value in existing.items()
                       if key.startswith("CONVERSION_RESULT_QUEUE_")
                       or key in ("CONVERSION_RESULT_RETENTION_DAYS", "CONVERSION_STATE_RETENTION_DAYS")}
        settings = derive_settings({**maintenance, **dict(os.environ)})
    except (ValueError, TypeError, KeyError, AttributeError):
        print("Conversion settings are invalid; no settings were changed.", file=sys.stderr)
        sys.exit(1)
    # Apply the feature settings used to derive the maintenance switch as one configuration.
    settings.update({key: value for key, value in os.environ.items()
                     if key.startswith("CONVERSION_RESULT_QUEUE_") or key in (
                         "CONVERSION_RESULT_RETENTION_DAYS", "CONVERSION_STATE_RETENTION_DAYS", "CONVERSION_CREATE_RESOURCES")})
    # The command line contains only a protected temporary filename, never a key.
    with tempfile.TemporaryDirectory(prefix="conversion-appsettings-") as directory:
        settings_file = Path(directory) / "settings.json"
        descriptor = os.open(settings_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(settings, output)
        command = [
            "az", "functionapp", "config", "appsettings", "set",
            "--resource-group", args.resource_group, "--name", args.name,
            "--settings", f"@{settings_file}", "--only-show-errors", "--output", "none",
        ]
        if args.slot:
            command.extend(["--slot", args.slot])
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode:
            # Azure CLI errors can contain submitted configuration; do not echo them.
            print("Azure configuration update failed. Check CLI login, app name, and permissions.",
                  file=sys.stderr)
            sys.exit(result.returncode)
    # Remove stale exact values: Azure resolves scalar connection settings before identity prefixes.
    deletion = ["az", "functionapp", "config", "appsettings", "delete",
                "--resource-group", args.resource_group, "--name", args.name,
                "--setting-names", *obsolete_keys(settings), "--only-show-errors", "--output", "none"]
    if args.slot:
        deletion.extend(["--slot", args.slot])
    result = subprocess.run(deletion, capture_output=True, text=True)
    if result.returncode:
        print("Removing obsolete connection settings failed; verify the active authentication mode.", file=sys.stderr)
        sys.exit(result.returncode)
    state = "disabled" if settings["AzureWebJobs.ProcessConversion.Disabled"] == "true" else "enabled"
    print(f"Async conversion {state}; unrelated app settings were preserved.")


if __name__ == "__main__":
    main()
