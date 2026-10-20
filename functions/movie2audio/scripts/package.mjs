import { cp, mkdtemp, readdir, rename, rm, copyFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { writeOrCheckNotices } from './licenses.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const stage = await mkdtemp(join(root, '.package-'));
try {
  for (const name of ['package.json', 'package-lock.json', 'host.json', 'LICENSE', 'NOTICE', 'README.md']) {
    await copyFile(join(root, name), join(stage, name));
  }
  await mkdir(join(stage, 'src'));
  for (const name of await readdir(join(root, 'src'))) {
    if (name.endsWith('.js')) await copyFile(join(root, 'src', name), join(stage, 'src', name));
  }
  await cp(join(root, 'resources'), join(stage, 'resources'), { recursive: true });
  await cp(join(root, 'docs'), join(stage, 'docs'), { recursive: true });
  await cp(join(root, 'third-party'), join(stage, 'third-party'), { recursive: true });
  await mkdir(join(stage, 'scripts'));
  await copyFile(join(root, 'scripts/build_ffmpeg.py'), join(stage, 'scripts/build_ffmpeg.py'));
  await copyFile(join(root, 'scripts/patch-sdk.mjs'), join(stage, 'scripts/patch-sdk.mjs'));
  await copyFile(join(root, 'scripts/licenses.mjs'), join(stage, 'scripts/licenses.mjs'));
  const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  const npm = spawnSync(npmCommand, ['ci', '--omit=dev', '--ignore-scripts'],
    { cwd: stage, stdio: 'inherit', shell: process.platform === 'win32' });
  if (npm.status !== 0) throw new Error('Installing production dependencies failed');
  const patch = spawnSync(process.execPath, ['scripts/patch-sdk.mjs'], { cwd: stage, stdio: 'inherit' });
  if (patch.status !== 0) throw new Error('Applying the pinned streaming SDK compatibility patch failed');
  const count = await writeOrCheckNotices(stage);
  await writeOrCheckNotices(stage, { check: true });
  console.log(`License texts verified for all ${count} production npm packages; standalone notices generated.`);
  await rm(join(root, 'dist'), { recursive: true, force: true });
  await rename(stage, join(root, 'dist'));
  console.log('Deployment files: functions/movie2audio/dist/ (no deployment performed)');
} finally {
  await rm(stage, { recursive: true, force: true });
}
