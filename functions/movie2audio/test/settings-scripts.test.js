import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, readFile, stat, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { queueBindingSettings, readJobSettings } from '../src/storage-settings.js';

const root = fileURLToPath(new URL('../', import.meta.url));

test('queue trigger derivation switches connection modes without carrying a previous identity', () => {
  assert.deepEqual(queueBindingSettings(null), {});
  assert.deepEqual(queueBindingSettings(readJobSettings({ CONVERSION_STORAGE_CONNECTION_STRING: 'connection' })),
    { CONVERSION_QUEUE_CONNECTION_STRING: 'connection' });
  const identity = readJobSettings({ CONVERSION_STORAGE__blobServiceUri: 'https://account.blob.core.windows.net',
    CONVERSION_STORAGE__queueServiceUri: 'https://account.queue.core.windows.net' });
  assert.deepEqual(queueBindingSettings(identity), { CONVERSION_QUEUE_CONNECTION_STRING__queueServiceUri: 'https://account.queue.core.windows.net',
    CONVERSION_QUEUE_CONNECTION_STRING__credential: 'managedidentity' });
});

test('local launcher mode switching clears stale connection and user-assigned identity values', () => {
  const code = `import importlib.util
import tempfile
from pathlib import Path
spec=importlib.util.spec_from_file_location('launcher','scripts/run_local.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
old={'CONVERSION_STORAGE_CONNECTION_STRING':'old-secret','CONVERSION_QUEUE_CONNECTION_STRING':'old-secret','CONVERSION_QUEUE_CONNECTION_STRING__clientId':'old-client'}
env={'CONVERSION_STORAGE__blobServiceUri':'https://account.blob.core.windows.net','CONVERSION_STORAGE__queueServiceUri':'https://account.queue.core.windows.net'}
defaults={}; m.use_default_azurite(defaults,{})
assert defaults['CONVERSION_STORAGE_CONNECTION_STRING']=='UseDevelopmentStorage=true'
disabled={}; m.use_default_azurite(disabled,{'CONVERSION_STORAGE_CONNECTION_STRING':''})
assert 'CONVERSION_STORAGE_CONNECTION_STRING' not in disabled
value=m.prepare_environment(old,env)
assert 'CONVERSION_STORAGE_CONNECTION_STRING' not in value
assert 'CONVERSION_QUEUE_CONNECTION_STRING' not in value
assert 'CONVERSION_QUEUE_CONNECTION_STRING__clientId' not in value
assert value['CONVERSION_QUEUE_CONNECTION_STRING__queueServiceUri']==env['CONVERSION_STORAGE__queueServiceUri']
value=m.prepare_environment(value,{'CONVERSION_STORAGE_CONNECTION_STRING':'new-secret'})
assert 'CONVERSION_STORAGE__blobServiceUri' not in value
assert 'CONVERSION_QUEUE_CONNECTION_STRING__queueServiceUri' not in value
assert value['CONVERSION_QUEUE_CONNECTION_STRING']=='new-secret'
value=m.prepare_environment(value,{'CONVERSION_STORAGE_CONNECTION_STRING':''})
assert 'CONVERSION_QUEUE_CONNECTION_STRING' not in value
original=m.WINDOWS
m.WINDOWS=True
m.shutil.which=lambda name: 'C:/Tools/'+name if name in ('npm.cmd','func.cmd','node.exe') else None
assert m.find_tool('npm').lower().endswith('npm.cmd')
assert m.find_tool('func').lower().endswith('func.cmd')
arguments,shell=m.command_arguments(['C:/Program Files/nodejs/npm.cmd','ci','--ignore-scripts'])
assert shell and 'npm.cmd' in arguments
with tempfile.TemporaryDirectory() as directory:
    source=Path(directory)/'source'; source.mkdir(); (source/'file.txt').write_text('copied')
    destination=Path(directory)/'destination'
    m.stage_entry(source,destination)
    assert not destination.is_symlink() and (destination/'file.txt').read_text()=='copied'
m.WINDOWS=original
`;
  const result = spawnSync('python3', ['-c', code], { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
});

test('Azure settings preparation uses private files and never prints connection secrets', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'movie-settings-test-')); t.after(() => rm(directory, { recursive: true, force: true }));
  const target = join(directory, 'settings.json');
  const clean = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('CONVERSION_')));
  const result = spawnSync(process.execPath, ['scripts/prepare_settings.mjs', target], { cwd: root, encoding: 'utf8',
    env: { ...clean, CONVERSION_STORAGE_CONNECTION_STRING: 'never-logged-secret' } });
  assert.equal(result.status, 0); assert.equal(result.stdout, ''); assert.equal(result.stderr, '');
  assert.equal((await stat(target)).mode & 0o777, 0o600);
  const config = JSON.parse(await readFile(target));
  assert.equal(config.settings.CONVERSION_QUEUE_CONNECTION_STRING, 'never-logged-secret');
  assert.ok(config.deletions.includes('CONVERSION_QUEUE_CONNECTION_STRING__clientId'));
});
