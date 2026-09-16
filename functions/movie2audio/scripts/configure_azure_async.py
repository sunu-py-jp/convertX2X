#!/usr/bin/env python3
"""Apply environment-provided async settings to an existing Node Function App."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resource-group', required=True)
    parser.add_argument('--name', required=True)
    parser.add_argument('--slot')
    args = parser.parse_args()
    for tool in ('node', 'az'):
        if not shutil.which(tool):
            parser.error(f'{tool} is required.')
    target = ['--resource-group', args.resource_group, '--name', args.name]
    if args.slot:
        target += ['--slot', args.slot]
    with tempfile.TemporaryDirectory(prefix='movie2audio-settings-') as directory:
        prepared = Path(directory) / 'prepared.json'
        result = subprocess.run(['node', 'scripts/prepare_settings.mjs', str(prepared)], cwd=ROOT, capture_output=True)
        if result.returncode:
            raise RuntimeError('Settings validation failed; check environment registrations.')
        document = json.loads(prepared.read_text())
        settings_file = Path(directory) / 'settings.json'
        descriptor = os.open(settings_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            json.dump(document['settings'], output)
        if document['settings']:
            result = subprocess.run(['az', 'functionapp', 'config', 'appsettings', 'set', *target,
                                     '--settings', f'@{settings_file}', '--only-show-errors', '--output', 'none'], capture_output=True)
            if result.returncode:
                raise RuntimeError('Azure settings update failed; check login, app and permissions.')
        if document['deletions']:
            result = subprocess.run(['az', 'functionapp', 'config', 'appsettings', 'delete', *target,
                                     '--setting-names', *document['deletions'], '--only-show-errors', '--output', 'none'], capture_output=True)
            if result.returncode:
                raise RuntimeError('Removing obsolete authentication settings failed; verify the active mode.')
    print('Async settings applied; unrelated app settings were preserved. No deployment performed.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError):
        print('Configuration update failed. Check settings, Azure CLI login and permissions.', file=sys.stderr)
        sys.exit(1)
