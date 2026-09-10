import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const paths = [
  'provider-limit-service.ts',
  'provider-limit-service.test.ts',
  'provider-quota-coordinator.ts',
  'provider-quota-coordinator.test.ts',
  'provider-limits',
  'util/provider-limit.ts',
  'util/provider-limit.test.ts',
];

// Keep the adapters and their tests together. Only the OpenCode transport is
// replaced by the JetBrains host; provider code stays byte-for-byte upstream.
export function syncQuota(source) {
  for (const path of paths) {
    const target = join(root, 'vendor/extension', path);
    mkdirSync(dirname(target), { recursive: true });
    rmSync(target, { recursive: true, force: true });
    cpSync(join(source, 'src/extension', path), target, { recursive: true });
  }
  const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: source, encoding: 'utf8' }).trim();
  writeFileSync(join(root, 'vendor/extension/UPSTREAM.json'), JSON.stringify({
    repository: 'https://github.com/koltyakov/varro.git', commit, paths,
  }, null, 2) + '\n');
  console.log(`Vendored provider quota backend and tests from Varro ${commit.slice(0, 12)}.`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  syncQuota(resolve(process.env.VARRO_SOURCE || join(root, '.upstream')));
}
