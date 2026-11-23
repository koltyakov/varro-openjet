import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

// Code-unit order: `localeCompare` follows the machine's ICU locale (Czech sorts
// "ch" after "h"), so the same tree would hash differently across developers.
export function compareNames(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function vendorHash(root, entries) {
  const hash = createHash('sha256');
  function visit(path, relative) {
    const stats = lstatSync(path);
    // A link would hash content from outside the vendored tree, or loop.
    if (stats.isSymbolicLink()) throw new Error(`Vendored sources must not contain symlinks: ${relative}`);
    if (!stats.isDirectory()) {
      hash.update(relative + '\0');
      hash.update(readFileSync(path));
      hash.update('\0');
      return;
    }
    for (const entry of readdirSync(path, { withFileTypes: true }).sort((a, b) =>
      compareNames(a.name, b.name)
    )) {
      const name = `${relative}/${entry.name}`;
      visit(join(path, entry.name), name);
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
