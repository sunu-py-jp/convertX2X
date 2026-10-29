import { cp, mkdtemp, rename, rm, copyFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { removeExcludedNodePackages, writeOrCheckNotices } from './licenses.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const stage = await mkdtemp(join(root, '.package-'));
try {
  for (const name of ['package.json', 'package-lock.json', 'host.json', 'LICENSE', 'NOTICE.md', 'README.md']) await copyFile(join(root, name), join(stage, name));
  for (const name of ['src', 'assets', 'docs', 'third-party']) await cp(join(root, name), join(stage, name), { recursive: true });
  await mkdir(join(stage, 'scripts'));
  for (const name of ['licenses.mjs', 'package.mjs', 'convert.mjs']) await copyFile(join(root, 'scripts', name), join(stage, 'scripts', name));
  const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  const npm = spawnSync(npmCommand, ['ci', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'], { cwd: stage, stdio: 'inherit', shell: process.platform === 'win32' });
  if (npm.status !== 0) throw new Error('Installing pinned production dependencies failed');
  await removeExcludedNodePackages(stage);
  const count = await writeOrCheckNotices(stage);
  await writeOrCheckNotices(stage, { check: true });
  await rm(join(root, 'dist'), { recursive: true, force: true });
  await rename(stage, join(root, 'dist'));
  console.log(`Standalone deployment files: functions/html2pptx/dist/ (${count} npm package license sets, native libraries and fonts verified; no deployment performed).`);
} finally {
  await rm(stage, { recursive: true, force: true });
}
