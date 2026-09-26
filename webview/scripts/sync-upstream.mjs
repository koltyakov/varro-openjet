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
      localChanges: run('git', ['status', '--porcelain', '--', 'src/webview', 'src/shared', 'package.json'], source).length > 0,
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
const packagePath = join(webviewRoot, 'package.json');
const localPackage = JSON.parse(readFileSync(packagePath, 'utf8'));
const upstreamPackage = JSON.parse(readFileSync(join(source, 'package.json'), 'utf8'));
let updatedDependencies = 0;

// Keep the webview's package selection, but use upstream's version specifiers.
for (const section of ['dependencies', 'devDependencies']) {
  for (const [name, currentVersion] of Object.entries(localPackage[section] ?? {})) {
    const upstreamVersion = upstreamPackage[section]?.[name]
      ?? upstreamPackage.dependencies?.[name]
      ?? upstreamPackage.devDependencies?.[name];
    if (upstreamVersion === undefined || upstreamVersion === currentVersion) continue;
    localPackage[section][name] = upstreamVersion;
    updatedDependencies += 1;
    console.log(`  ${name}: ${currentVersion} -> ${upstreamVersion}`);
  }
}

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

// The JetBrains version comes from Gradle rather than the npm package.
const toolbarPath = join(webviewRoot, 'vendor/webview/components/chat-input/ChatInputToolbar.tsx');
const toolbarSource = readFileSync(toolbarPath, 'utf8');
const packageImport = "import packageJson from '../../../../package.json';";
if (!toolbarSource.includes(packageImport)) {
  throw new Error('Upstream toolbar metadata import changed; update the JetBrains adaptation.');
}
writeFileSync(toolbarPath, toolbarSource.replace(
  packageImport,
  "import packageJson from '../../../../src/plugin-metadata';",
));

// Older IDEs cannot open detached editors through a supported API.
for (const [component, label] of [
  ['ChatHeader', 'New Chat Window'],
  ['SessionActionsMenu', 'Open in Window'],
]) {
  const path = join(webviewRoot, `vendor/webview/components/chat/${component}.tsx`);
  const source = readFileSync(path, 'utf8');
  let matches = 0;
  const adapted = source.replace(/<button\b[\s\S]*?<\/button>/g, (button) => {
    if (!new RegExp(`>\\s*${label}\\s*</button>`).test(button)) return button;
    matches += 1;
    return `<Show when={supportsDetachedEditors()}>${button}</Show>`;
  });
  if (matches !== 1) {
    throw new Error(`Upstream ${component} window menu changed; update the JetBrains adaptation.`);
  }
  writeFileSync(path,
    "import { supportsDetachedEditors } from '../../../../src/host-capabilities';\n" + adapted);
}

// JCEF restores the window theme from its boot snapshot, before any host update.
const runtimePath = join(webviewRoot, 'vendor/webview/hooks/runtime/open-code-runtime-instance.ts');
const runtimeSource = readFileSync(runtimePath, 'utf8');
if (!runtimeSource.includes('const initialTheme = syncWindowChatTheme(')) {
  const initialTheme = '      applyTheme(uiStore.theme());';
  if (!runtimeSource.includes(initialTheme)) {
    throw new Error('Upstream initial theme application changed; update the JetBrains adaptation.');
  }
  writeFileSync(runtimePath,
    "import { syncWindowChatTheme } from '../../lib/window-chat-theme';\n" +
    runtimeSource.replace(initialTheme,
      '      const initialTheme = syncWindowChatTheme({\n' +
      '        theme: initialWebviewState.theme ?? uiStore.theme(),\n' +
      '        windowChatTheme: initialWebviewState.windowChatTheme,\n' +
      '      });\n' +
      '      uiStore.setTheme(initialTheme);\n' +
      '      applyTheme(initialTheme);'));
}

// Keep old VS Code transcripts readable while identifying this host's diagnostics.
const problemsPath = join(webviewRoot, 'vendor/webview/lib/editor-problems.ts');
if (existsSync(problemsPath)) {
  writeFileSync(problemsPath, readFileSync(problemsPath, 'utf8')
    .replaceAll('\\[VS Code problems for', '\\[(?:JetBrains|VS Code) problems for')
    .replace('`[VS Code problems for', '`[JetBrains problems for'));
}

// The database host sends console, Explorer, and connection details beyond the
// upstream grid snapshot. Keep the adaptation small; its implementation lives in src.
const databaseProtocolPath = join(webviewRoot, 'vendor/shared/protocol.ts');
const databaseSharedPath = join(webviewRoot, 'vendor/shared/database-context.ts');
function adaptDatabase(path, replacements) {
  let text = readFileSync(path, 'utf8');
  for (const [before, after] of replacements) {
    if (!text.includes(before)) throw new Error(`Upstream database context changed in ${path}; update the JetBrains adaptation.`);
    text = text.replaceAll(before, after);
  }
  writeFileSync(path, text);
}
adaptDatabase(databaseProtocolPath, [
  ['/** A detached snapshot of a loaded database grid. Values use strings to preserve SQL precision. */\n', ''],
  ['export interface DatabaseContext {', "import type { DatabaseContextDetails } from '../../src/database-context';\n\n/** A detached snapshot of the active database surface. Values preserve SQL precision. */\nexport interface DatabaseContext extends DatabaseContextDetails {"],
  ["scope: 'table' | 'selected-rows' | 'ddl';", "scope: 'table' | 'selected-rows' | 'ddl' | 'console' | 'object' | 'datasource';"],
  ['  databaseContext?: DatabaseContext | null;', '  databaseContext?: DatabaseContext | null;\n  databaseEnvironment?: DatabaseContext | null;'],
]);
adaptDatabase(databaseSharedPath, [
  ["import { asRecord, isString, isBoolean, isNumber } from './type-utils';", "import { asRecord, isString, isBoolean, isNumber } from './type-utils';\nimport { cloneDatabaseDetails, databaseDetailsLabel, isDatabaseContextDetails, isDatabaseScope } from '../../src/database-context';"],
  ["record.scope !== 'table' && record.scope !== 'selected-rows' && record.scope !== 'ddl'", '!isDatabaseScope(record.scope)'],
  ['return JSON.stringify(record.rows).length <= 81_000;', 'return JSON.stringify(record.rows).length <= 81_000 && isDatabaseContextDetails(record);'],
  ['        ...context,', '        ...context,\n        ...cloneDatabaseDetails(context),'],
  ['return databaseAttachmentDetail({ ...context, rowCount: context.rows.length });', 'return databaseDetailsLabel(context, databaseAttachmentDetail({ ...context, rowCount: context.rows.length }));'],
  ["    context.scope === 'ddl'", "    context.scope === 'console' || context.scope === 'object' || context.scope === 'datasource'\n      ? context.scope\n      : context.scope === 'ddl'"],
]);
adaptDatabase(join(webviewRoot, 'vendor/webview/components/ChatInput.tsx'), [
  ['    const database = state.editorContext.databaseContext;', '    const database = state.editorContext.databaseContext ??\n      (!composerActiveFile() && !state.editorContext.editorText ? state.editorContext.databaseEnvironment : null);'],
  ['        lineRange: databaseContextDetail(database),', "        lineRange: '',\n        tooltipDetail: databaseContextDetail(database),"],
  ['    const label = context.lineRange ? `${context.filename} ${context.lineRange}` : context.filename;',
    '    const detail = context.tooltipDetail ?? context.lineRange;\n    const label = detail ? `${context.filename} ${detail}` : context.filename;'],
  ["    const source = state.editorContext.databaseContext ? 'database' : 'document';", "    const source = context.icon === 'table' ? 'database' : 'document';"],
  ['            databaseContext: cloneDatabaseContext(state.editorContext.databaseContext),', '            databaseContext: cloneDatabaseContext(state.editorContext.databaseContext),\n            databaseEnvironment: cloneDatabaseContext(state.editorContext.databaseEnvironment),'],
]);
adaptDatabase(join(webviewRoot, 'vendor/shared/extension-message.ts'), [
  ['  if (record.databaseContext != null && !isDatabaseContext(record.databaseContext)) return false;',
    '  if (record.databaseContext != null && !isDatabaseContext(record.databaseContext)) return false;\n  if (record.databaseEnvironment != null && !isDatabaseContext(record.databaseEnvironment)) return false;'],
]);
adaptDatabase(join(webviewRoot, 'vendor/webview/lib/database-context.ts'), [
  ["import type { DatabaseContext } from '../../shared/protocol';", "import type { DatabaseContext } from '../../shared/protocol';\nimport { withDatabaseEnvironment } from '../../../src/database-context';"],
  ['export function formatDatabaseContext(context: DatabaseContext): string {', 'export function formatDatabaseContext(context: DatabaseContext, environment?: DatabaseContext | null): string {'],
  ['JSON.stringify(context, null, 2)', 'JSON.stringify(withDatabaseEnvironment(context, environment), null, 2)'],
]);
adaptDatabase(join(webviewRoot, 'vendor/webview/hooks/session/session-send.ts'), [
  ['  const databaseContext = composerState.editorContext.databaseContext;', '  const databaseContext = composerState.editorContext.databaseContext;\n  const databaseEnvironment = composerState.editorContext.databaseEnvironment;\n  if (databaseEnvironment && !databaseContext && currentDocumentEnabled) {\n    parts.push({ type: \'text\', text: formatDatabaseContext(databaseEnvironment) });\n  }'],
  ['formatDatabaseContext(databaseContext)', 'formatDatabaseContext(databaseContext, databaseEnvironment)'],
  ['        databaseContext: cloneDatabaseContext(sourceEditorContext.databaseContext),', '        databaseContext: cloneDatabaseContext(sourceEditorContext.databaseContext),\n        databaseEnvironment: cloneDatabaseContext(sourceEditorContext.databaseEnvironment),'],
]);
adaptDatabase(join(webviewRoot, 'vendor/webview/components/chat-input/message-usage.ts'), [
  ["import type { AssistantMessage, Message, Part, Session, TextPart } from '../../types';", "import type { AssistantMessage, Message, Part, Session, TextPart } from '../../types';\nimport { stripDatabaseContextForHistory } from '../../../../src/database-history';"],
  ['    .map((part) => part.text.trim())', '    .map((part) => stripDatabaseContextForHistory(part.text).trim())'],
]);
// Sent database chips follow the composer's name-only display. Titles retain details.
adaptDatabase(join(webviewRoot, 'vendor/webview/components/message/UserMessageContent.tsx'), [
  ['      <Show when={database()}>\n        {(table) => <span class="inline-chip-detail">{databaseAttachmentDetail(table())}</span>}\n      </Show>\n', ''],
  ['    if (value.type === \'file-reference\' && value.database)\n      return <span class="chip-detail">{databaseAttachmentDetail(value.database)}</span>;\n    if (value.type === \'database\')\n      return <span class="chip-detail">{databaseContextDetail(value.context)}</span>;\n', ''],
  ['  if (attachment.attachment.type === \'file-reference\' && attachment.attachment.database)\n    return databaseAttachmentDetail(attachment.attachment.database);\n  if (attachment.attachment.type === \'database\')\n    return databaseContextDetail(attachment.attachment.context);\n', ''],
]);

if (updatedDependencies > 0) {
  writeFileSync(packagePath, `${JSON.stringify(localPackage, null, 2)}\n`, 'utf8');
  console.log(`Updated ${updatedDependencies} dependency versions. Run npm install in webview/ to refresh package-lock.json.`);
}

writeFileSync(
  join(webviewRoot, 'vendor', 'UPSTREAM.json'),
  `${JSON.stringify({ ...config, revision }, null, 2)}\n`,
  'utf8',
);

console.log(`Vendored ${copied} files (${skipped} test files skipped) from Varro ${revision.commit.slice(0, 12)}.`);
