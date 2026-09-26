import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export function vendorHash(root, entries) {
  const hash = createHash('sha256');
  function visit(path, relative) {
    for (const entry of readdirSync(path, { withFileTypes: true }).sort((a, b) =>
      a.name.localeCompare(b.name)
    )) {
      const name = `${relative}/${entry.name}`;
      if (entry.isDirectory()) visit(join(path, entry.name), name);
      else {
        hash.update(name + '\0');
        hash.update(readFileSync(join(path, entry.name)));
        hash.update('\0');
      }
    }
  }
  for (const entry of entries) visit(join(root, entry.to), entry.to);
  return hash.digest('hex');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const manifest = JSON.parse(readFileSync(join(root, 'vendor/UPSTREAM.json'), 'utf8'));
  if (vendorHash(root, manifest.copy) !== manifest.sourceHash)
    throw new Error(
      'Vendored sources differ from the synced snapshot. Run sync; put adaptations in src/.'
    );
  console.log('Vendored sources match the unmodified sync snapshot.');
}
