import { spawnSync } from 'node:child_process';

const candidates = process.platform === 'win32'
  ? [['py', ['-3']], ['python', []], ['python3', []]]
  : [['python3', []], ['python', []]];

for (const [command, prefix] of candidates) {
  const result = spawnSync(command, [...prefix, 'scripts/run_local.py', ...process.argv.slice(2)],
    { stdio: 'inherit', shell: false });
  if (result.error?.code === 'ENOENT') continue;
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
  process.exit();
}
process.stderr.write('Python 3.10 or later is required.\n');
process.exitCode = 1;
