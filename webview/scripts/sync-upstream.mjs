#!/usr/bin/env node
/**
 * Vendors the upstream Varro webview into `webview/vendor`.
 *
 * Varro's webview is platform-neutral: it has no `vscode` imports and talks to
 * its host through four `window` globals (see `src/host/bridge.ts`). That makes
 * it reusable verbatim under JetBrains JCEF, so this port vendors the upstream
 * sources instead of reimplementing ~80k lines of Solid UI.
 *
 * Source resolution order:
 *   1. `VARRO_SOURCE=/path/to/varro` - a local checkout (fastest for dev).
 *   2. `webview/.upstream` - an existing clone, fetched and reset to the ref.
 *   3. A fresh shallow clone of the repository in `upstream.json`.
 */
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webviewRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const config = JSON.parse(readFileSync(join(webviewRoot, 'upstream.json'), 'utf8'));

function run(command, args, cwd) {
  return execFileSync(command, args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] }).trim();
}

function resolveSource() {
  const override = process.env.VARRO_SOURCE?.trim();
  if (override) {
    const source = resolve(override);
    if (!existsSync(join(source, 'src', 'webview'))) {
      throw new Error(`VARRO_SOURCE=${source} does not look like a Varro checkout (no src/webview).`);
    }
    console.log(`Using local Varro checkout: ${source}`);
    return { source, pinned: false };
  }

  const clone = join(webviewRoot, '.upstream');
  if (existsSync(join(clone, '.git'))) {
    console.log(`Updating existing clone: ${clone}`);
    run('git', ['fetch', '--depth', '1', 'origin', config.ref], clone);
    run('git', ['checkout', '--force', 'FETCH_HEAD'], clone);
  } else {
    console.log(`Cloning ${config.repository} @ ${config.ref}`);
    rmSync(clone, { recursive: true, force: true });
    run('git', ['clone', '--depth', '1', '--branch', config.ref, config.repository, clone], webviewRoot);
  }
  return { source: clone, pinned: true };
}

function describeRevision(source) {
  try {
    return {
      commit: run('git', ['rev-parse', 'HEAD'], source),
      describedAt: new Date().toISOString(),
    };
  } catch {
    return { commit: 'unknown', describedAt: new Date().toISOString() };
  }
}

/**
 * Converts a glob to a regex in a single pass. Sequential `String.replace`
 * calls cannot do this: each pass also rewrites the regex syntax emitted by the
 * previous one, so `**\/*.test.ts` degrades into a pattern that matches nothing.
 */
function toRegExp(glob) {
  let pattern = '';
  for (let index = 0; index < glob.length; index += 1) {
    const char = glob[index];
    if (char === '*') {
      if (glob[index + 1] === '*') {
        // `**/` spans any number of directories, including none at all.
        if (glob[index + 2] === '/') {
          pattern += '(?:[^/]+/)*';
          index += 2;
        } else {
          pattern += '.*';
          index += 1;
        }
      } else {
        pattern += '[^/]*';
      }
      continue;
    }
    if (char === '?') {
      pattern += '[^/]';
      continue;
    }
    pattern += char.replace(/[.+^${}()|[\]\\/]/, '\\$&');
  }
  return new RegExp(`^${pattern}$`);
}

const excludes = (config.exclude ?? []).map(toRegExp);

function isExcluded(path) {
  const normalized = path.split('\\').join('/');
  return excludes.some((pattern) => pattern.test(normalized));
}

const { source } = resolveSource();
const revision = describeRevision(source);
let copied = 0;
let skipped = 0;

for (const entry of config.copy) {
  const from = join(source, entry.from);
  const to = join(webviewRoot, entry.to);
  if (!existsSync(from)) {
    throw new Error(`Upstream path is missing: ${from}`);
  }

  rmSync(to, { recursive: true, force: true });
  mkdirSync(dirname(to), { recursive: true });
  cpSync(from, to, {
    recursive: true,
    filter(src) {
      if (statSync(src).isDirectory()) return true;
      const rel = relative(from, src);
      if (isExcluded(rel)) {
        skipped += 1;
        return false;
      }
      copied += 1;
      return true;
    },
  });
  console.log(`  ${entry.from} -> ${entry.to}`);
}

writeFileSync(
  join(webviewRoot, 'vendor', 'UPSTREAM.json'),
  `${JSON.stringify({ ...config, revision }, null, 2)}\n`,
  'utf8',
);

console.log(`Vendored ${copied} files (${skipped} test files skipped) from Varro ${revision.commit.slice(0, 12)}.`);
