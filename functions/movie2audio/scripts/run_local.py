#!/usr/bin/env python3
"""Install dependencies and run the Node.js Movie2Audio functions on port 7073."""
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

ROOT = Path(__file__).resolve().parents[1]
WINDOWS = os.name == 'nt'


CONTROL_KEYS = ('CONVERSION_STORAGE_CONNECTION_STRING', 'CONVERSION_STORAGE__blobServiceUri',
                'CONVERSION_STORAGE__queueServiceUri', 'CONVERSION_STORAGE__clientId')
BINDING_PREFIX = 'CONVERSION_QUEUE_CONNECTION_STRING'
AZURITE_CONNECTION = 'UseDevelopmentStorage=true'
AZURITE_PORTS = (10000, 10001)


def find_tool(name):
    """Resolve Windows command shims explicitly so subprocess does not miss .cmd files."""
    candidates = (f'{name}.exe', f'{name}.cmd', name) if WINDOWS else (name,)
    for candidate in candidates:
        executable = shutil.which(candidate)
        if executable:
            return str(Path(executable).resolve())
    return None


def find_project_tool(name):
    suffixes = ('.exe', '.cmd', '') if WINDOWS else ('',)
    for suffix in suffixes:
        executable = ROOT / 'node_modules' / '.bin' / f'{name}{suffix}'
        if executable.exists():
            return str(executable.resolve())
    return find_tool(name)


def command_arguments(command):
    """Return a subprocess-safe command, using cmd.exe only for trusted batch launchers."""
    batch = WINDOWS and Path(command[0]).suffix.lower() in ('.cmd', '.bat')
    if not batch:
        return command, False
    arguments = f'"{command[0]}" ' + subprocess.list2cmdline(command[1:])
    return arguments, True


def run_command(command, **kwargs):
    arguments, shell = command_arguments(command)
    return subprocess.run(arguments, shell=shell, **kwargs)


def popen_command(command, **kwargs):
    arguments, shell = command_arguments(command)
    return subprocess.Popen(arguments, shell=shell, **kwargs)


def stage_entry(source, destination):
    """Avoid Windows symlink privileges; the temporary stage is deleted after shutdown."""
    if WINDOWS:
        if source.is_dir():
            try:
                # Hard links keep the larger node_modules tree cheap when TEMP is on the same drive.
                shutil.copytree(source, destination, copy_function=os.link)
            except OSError:
                shutil.rmtree(destination, ignore_errors=True)
                shutil.copytree(source, destination)
        else:
            shutil.copy2(source, destination)
    else:
        destination.symlink_to(source, target_is_directory=source.is_dir())


def error_summary(error):
    """Report actionable OS/exit codes without printing settings or secret command data."""
    details = [type(error).__name__]
    if isinstance(error, OSError):
        if getattr(error, 'winerror', None) is not None:
            details.append(f'WinError {error.winerror}')
        if error.errno is not None:
            details.append(f'errno {error.errno}')
    if isinstance(error, subprocess.CalledProcessError):
        details.append(f'exit code {error.returncode}')
    return '; '.join(details)


def ports_ready(ports=AZURITE_PORTS):
    for port in ports:
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=0.25):
                pass
        except OSError:
            return False
    return True


def wait_for_ports(process, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if ports_ready():
            return
        if process.poll() is not None:
            break
        time.sleep(0.1)
    raise OSError('Azurite did not start on its default ports.')


def stop_process(process):
    if not process or process.poll() is not None:
        return
    if WINDOWS:
        taskkill = find_tool('taskkill')
        if taskkill:
            subprocess.run([taskkill, '/pid', str(process.pid), '/t', '/f'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        if WINDOWS:
            process.kill()
        else:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        process.wait()


def use_default_azurite(values, environ):
    """Enable local queues unless the caller explicitly selected a storage mode."""
    if any(key in environ for key in CONTROL_KEYS):
        return
    if not any(str(values.get(key, '')).strip() for key in CONTROL_KEYS):
        values[CONTROL_KEYS[0]] = AZURITE_CONNECTION


def prepare_azurite(node, environment, work):
    if environment.get(CONTROL_KEYS[0], '').strip().lower() != AZURITE_CONNECTION.lower():
        return None
    process = None
    if not ports_ready():
        executable = find_project_tool('azurite')
        if not executable:
            raise OSError('Azurite is required for the default local Queue configuration.')
        log = work / 'azurite.log'
        with log.open('w', encoding='utf-8') as output:
            process = popen_command([executable, '--location', str(work / 'azurite'), '--silent',
                                     '--disableTelemetry', '--skipApiVersionCheck'],
                                    cwd=ROOT, env=environment, stdout=output,
                                    stderr=subprocess.STDOUT, start_new_session=not WINDOWS)
        try:
            wait_for_ports(process)
        except Exception:
            stop_process(process)
            raise
        print('Azurite: started locally', flush=True)
    else:
        print('Azurite: using the existing local instance', flush=True)
    if environment.get('CONVERSION_CREATE_RESOURCES', 'true').strip().lower() != 'false':
        try:
            run_command([node, 'scripts/prepare_local_storage.mjs'], cwd=ROOT,
                        env=environment, check=True)
        except Exception:
            stop_process(process)
            raise
        print('Local Queue/Blob resources: ready', flush=True)
    return process


def prepare_environment(values, environ):
    """Environment mode overrides local mode; derived trigger settings are rebuilt."""
    result = {key: str(value) for key, value in values.items()}
    if any(key in environ for key in CONTROL_KEYS):
        for key in CONTROL_KEYS:
            result.pop(key, None)
    result.update(environ)
    for key in list(result):
        if key == BINDING_PREFIX or key.startswith(BINDING_PREFIX + '__'):
            result.pop(key)
    connection = result.get(CONTROL_KEYS[0], '').strip()
    blob, queue, client = (result.get(key, '').strip() for key in CONTROL_KEYS[1:])
    if connection and (blob or queue or client):
        raise ValueError('Choose one control storage authentication mode.')
    if not connection and (bool(blob) != bool(queue) or (client and not blob)):
        raise ValueError('Both identity storage endpoints are required.')
    if connection:
        result[BINDING_PREFIX] = connection
        if not result.get('AzureWebJobsStorage', '').strip():
            result['AzureWebJobsStorage'] = connection
    elif blob and queue:
        result[BINDING_PREFIX + '__queueServiceUri'] = queue
        result[BINDING_PREFIX + '__credential'] = 'managedidentity'
        if client:
            result[BINDING_PREFIX + '__clientId'] = client
    result['FUNCTIONS_WORKER_RUNTIME'] = 'node'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build', action='store_true', help='Reuse installed npm dependencies')
    parser.add_argument('--port', type=int, default=7073)
    args = parser.parse_args()
    tools = {name: find_tool(name) for name in ('node', 'npm', 'func')}
    for name, executable in tools.items():
        if not executable:
            parser.error(f'{name} is required (Node.js 22/24 and Functions Core Tools v4).')
    major = int(run_command([tools['node'], '-p', 'process.versions.node.split(".")[0]'],
                            text=True, capture_output=True, check=True).stdout)
    if major not in (22, 24):
        parser.error('Use Node.js 22 or 24.')
    if not args.skip_build:
        run_command([tools['npm'], 'ci', '--ignore-scripts'], cwd=ROOT, check=True)
    if not (ROOT / 'node_modules/@azure/functions').is_dir():
        parser.error('Run npm ci first, or omit --skip-build.')
    run_command([tools['node'], 'scripts/patch-sdk.mjs'], cwd=ROOT, check=True)
    settings_file = ROOT / 'local.settings.json'
    if not settings_file.exists():
        data = json.loads((ROOT / 'local.settings.example.json').read_text(encoding='utf-8'))
        fd = os.open(settings_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as output:
            json.dump(data, output, indent=2)
            output.write('\n')
    settings = json.loads(settings_file.read_text(encoding='utf-8'))
    if settings.get('IsEncrypted'):
        parser.error('Decrypt local.settings.json before using this launcher.')
    values = settings.get('Values', {})
    use_default_azurite(values, os.environ)
    if values.get('FUNCTIONS_WORKER_RUNTIME') != 'node':
        # Preserve user values while migrating this project's known runtime setting.
        values['FUNCTIONS_WORKER_RUNTIME'] = 'node'
        settings['Values'] = values
        fd = os.open(settings_file, os.O_WRONLY | os.O_TRUNC)
        if not WINDOWS:
            os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as output:
            json.dump(settings, output, indent=2)
            output.write('\n')
    child_env = prepare_environment(values, dict(os.environ))
    print(f'Playground: http://localhost:{args.port}/api/playground', flush=True)
    # Core Tools rereads local.settings.json after launch. Use a private copy so
    # stale connection strings or identity prefixes cannot override the chosen
    # authentication mode, while keeping the user's original settings intact.
    with tempfile.TemporaryDirectory(prefix='movie2audio-local-') as directory:
        work = Path(directory)
        azurite = prepare_azurite(tools['node'], child_env, work)
        try:
            stage = work / 'function'
            stage.mkdir()
            for name in ('host.json', 'package.json', 'src', 'resources', 'node_modules'):
                stage_entry(ROOT / name, stage / name)
            names = set(values) | {key for key in child_env if key.startswith('CONVERSION_')}
            names |= {'FUNCTIONS_WORKER_RUNTIME', 'AzureWebJobsStorage'}
            staged_settings = dict(settings)
            staged_settings['Values'] = {key: child_env[key] for key in names if key in child_env}
            descriptor = os.open(stage / 'local.settings.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
                json.dump(staged_settings, output)
            child = popen_command([tools['func'], 'start', '--port', str(args.port)], cwd=stage, env=child_env,
                                  start_new_session=not WINDOWS)
            try:
                returncode = child.wait()
            except KeyboardInterrupt:
                stop_process(child)
                returncode = 130
        finally:
            stop_process(azurite)
        if returncode:
            raise subprocess.CalledProcessError(returncode, 'func')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        print(f'Local startup failed ({error_summary(failure)}).', file=sys.stderr)
        sys.exit(1)
