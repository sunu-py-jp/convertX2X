#!/usr/bin/env python3
"""Apply the optional storage setting and matching queue switches to an existing app.

Reads CONVERSION_STORAGE_CONNECTION_STRING from this process's environment.
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

from async_settings import STORAGE_KEY, derive_settings


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resource-group", required=True)
    parser.add_argument("--name", required=True, help="Existing Function App name")
    parser.add_argument("--slot")
    args = parser.parse_args()
    if not shutil.which("az"):
        parser.error("Azure CLI is required. Install it and sign in with az login.")

    settings = derive_settings(os.environ.get(STORAGE_KEY, ""))
    # The command line contains only a protected temporary filename, never a key.
    with tempfile.TemporaryDirectory(prefix="excel2md-appsettings-") as directory:
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
    state = "enabled" if settings[STORAGE_KEY] else "disabled"
    print(f"Async conversion {state}; unrelated app settings were preserved.")


if __name__ == "__main__":
    main()
