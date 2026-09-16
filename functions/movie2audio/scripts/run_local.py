#!/usr/bin/env python3
"""Install dependencies and run the Node.js Movie2Audio functions on port 7073."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build', action='store_true', help='Reuse installed npm dependencies')
    parser.add_argument('--port', type=int, default=7073)
    args = parser.parse_args()
    for tool in ('node', 'npm', 'func'):
        if not shutil.which(tool):
            parser.error(f'{tool} is required (Node.js 22/24 and Functions Core Tools v4).')
    major = int(subprocess.check_output(['node', '-p', 'process.versions.node.split(".")[0]'], text=True))
    if major not in (22, 24):
        parser.error('Use Node.js 22 or 24.')
    if not args.skip_build:
        subprocess.run(['npm', 'ci', '--ignore-scripts'], cwd=ROOT, check=True)
    if not (ROOT / 'node_modules/@azure/functions').is_dir():
        parser.error('Run npm ci first, or omit --skip-build.')
    subprocess.run(['node', 'scripts/patch-sdk.mjs'], cwd=ROOT, check=True)
    settings_file = ROOT / 'local.settings.json'
    if not settings_file.exists():
        data = json.loads((ROOT / 'local.settings.example.json').read_text())
        fd = os.open(settings_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as output:
            json.dump(data, output, indent=2)
            output.write('\n')
    settings = json.loads(settings_file.read_text())
    if settings.get('IsEncrypted'):
        parser.error('Decrypt local.settings.json before using this launcher.')
    values = settings.get('Values', {})
    if values.get('FUNCTIONS_WORKER_RUNTIME') != 'node':
        # Preserve user values while migrating this project's known runtime setting.
        values['FUNCTIONS_WORKER_RUNTIME'] = 'node'
        settings['Values'] = values
        fd = os.open(settings_file, os.O_WRONLY | os.O_TRUNC)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as output:
            json.dump(settings, output, indent=2)
            output.write('\n')
    child_env = os.environ.copy()
    for key, value in values.items():
        child_env.setdefault(key, str(value))
    conversion_storage = child_env.get('CONVERSION_STORAGE_CONNECTION_STRING', '').strip()
    if conversion_storage and not child_env.get('AzureWebJobsStorage', '').strip():
        child_env['AzureWebJobsStorage'] = conversion_storage
    child_env['FUNCTIONS_WORKER_RUNTIME'] = 'node'
    print(f'Playground: http://localhost:{args.port}/api/playground', flush=True)
    os.chdir(ROOT)
    os.execvpe('func', ['func', 'start', '--port', str(args.port)], child_env)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        print(f'Local startup failed ({type(failure).__name__}).', file=sys.stderr)
        sys.exit(1)
