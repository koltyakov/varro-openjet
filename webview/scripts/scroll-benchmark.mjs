#!/usr/bin/env node
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { dirname, extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import solid from 'vite-plugin-solid';
import tailwindcss from '@tailwindcss/vite';

const webview = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const project = resolve(webview, '..');
const args = process.argv.slice(2);
function option(name, fallback) {
  const index = args.indexOf(name);
  if (index < 0) return fallback;
  if (!args[index + 1] || args[index + 1].startsWith('--')) throw new Error(`Missing value for ${name}`);
  return args[index + 1];
}
const source = resolve(option('--source', process.env.VARRO_SOURCE ?? resolve(project, '../varro')));
const port = Number(option('--port', '4186'));
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid --port');
const harnessDir = resolve(source, 'e2e/harness');
const token = randomBytes(24).toString('hex');
const parent = resolve(project, 'build/scroll-benchmark');
await mkdir(parent, { recursive: true });
const runDir = await mkdtemp(resolve(parent, 'run-'));
const dist = resolve(runDir, 'dist');
const results = resolve(runDir, 'results');
await mkdir(results);
const metadata = {
  createdAt: new Date().toISOString(),
  fixtureModel: 'GPT Luna',
  synthetic: true,
  source,
  harnessCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: source, encoding: 'utf8' }).trim(),
  harnessLocalChanges: execFileSync('git', ['status', '--porcelain', '--', 'e2e/harness'], { cwd: source, encoding: 'utf8' }).trim(),
  vendored: JSON.parse(await readFile(resolve(webview, 'vendor/UPSTREAM.json'), 'utf8')).revision,
  sourceHashes: Object.fromEntries(await Promise.all([
    'components/MarkdownRenderer.tsx', 'components/MessagePart.tsx', 'components/MessageList.tsx', 'styles/messages.css', 'styles/tool-calls.css',
  ].map(async (file) => [file, createHash('sha256').update(await readFile(resolve(webview, 'vendor/webview', file))).digest('hex')]))),
  host: 'Synthetic E2E host; vendored OpenJet UI; no OpenCode server connection',
};
const properties = await readFile(resolve(project, 'gradle.properties'), 'utf8');
const pluginVersion = properties.match(/^pluginVersion\s*=\s*(\S+)\s*$/m)?.[1];
let harness = await readFile(resolve(harnessDir, 'main.ts'), 'utf8');
if (!harness.includes("const DEFAULT_MODEL_ID = 'gpt-5-mini';") || !harness.includes("name: 'GPT-5 mini'")) {
  throw new Error('Upstream default fixture model changed; update the Luna benchmark label adaptation');
}
harness = harness.replace("const DEFAULT_MODEL_ID = 'gpt-5-mini';", "const DEFAULT_MODEL_ID = 'gpt-luna';")
  .replace("name: 'GPT-5 mini'", "name: 'GPT Luna'");
const marker = "await import('../../src/webview/index');";
if (!harness.includes(marker)) throw new Error('Upstream fixture entry changed; update the benchmark adaptation');
harness = harness.replace(marker, `
if (new URLSearchParams(window.location.search).get('singleTall') === '1') {
  const sessionId = 'session-huge-content-transcript';
  const messages = scenarioState.messagesBySessionId[sessionId].slice(-40);
  const part = messages.at(-1).parts.find((part) => part.type === 'text');
  part.text = Array.from({ length: 2400 }, (_, index) =>
    '### Analysis block ' + index + '\\n\\n' +
    'This long response tests Markdown layout within a single message below the outer virtualization threshold. '.repeat(3)
  ).join('\\n\\n');
  scenarioState.messagesBySessionId[sessionId] = messages;
}
${marker}`);
// Reuse fixture construction, but compile the UI actually shipped by OpenJet.
harness = harness.replaceAll('../../src/webview', resolve(webview, 'vendor/webview'))
  .replaceAll('../../src/shared', resolve(webview, 'vendor/shared'));
harness += `\nconst { installScrollingBenchmark } = await import(${JSON.stringify(resolve(webview, 'src/scroll-benchmark.ts'))});\ninstallScrollingBenchmark(${JSON.stringify(token)}, ${JSON.stringify(metadata)});\n`;
await writeFile(resolve(runDir, 'main.ts'), harness);
await writeFile(resolve(runDir, 'index.html'), await readFile(resolve(harnessDir, 'index.html'), 'utf8'));
await writeFile(resolve(runDir, 'manifest.json'), JSON.stringify(metadata, null, 2));
await build({
  configFile: false,
  root: runDir,
  base: './',
  define: { __VARRO_PLUGIN_VERSION__: JSON.stringify(pluginVersion) },
  plugins: [{
    name: 'benchmark-tailwind-sources',
    enforce: 'pre',
    transform(code, id) {
      if (id === resolve(webview, 'vendor/webview/index.css')) {
        return `${code}\n@source ${JSON.stringify(resolve(webview, 'vendor/webview'))};\n`;
      }
    },
  }, solid(), tailwindcss()],
  build: { outDir: dist, target: 'es2022', minify: 'oxc', sourcemap: 'hidden', chunkSizeWarningLimit: 4096 },
  logLevel: 'warn',
});
console.log(`Benchmark build: ${runDir}`);
if (args.includes('--build-only')) process.exit(0);

const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json', '.map': 'application/json' };
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, `http://127.0.0.1:${port}`);
    if (request.method === 'POST' && url.pathname === '/results') {
      if (request.headers['x-varro-benchmark'] !== token) { response.writeHead(403).end(); return; }
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > 5 * 1024 * 1024) { response.writeHead(413).end(); return; }
        chunks.push(chunk);
      }
      const result = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (result.kind !== 'varro-jcef-scroll-benchmark' || !Array.isArray(result.frames)) {
        response.writeHead(400).end(); return;
      }
      const path = resolve(results, `${Date.now()}-${randomBytes(4).toString('hex')}.json`);
      await writeFile(path, JSON.stringify(result, null, 2), { flag: 'wx' });
      console.log(JSON.stringify({ saved: path, mode: result.mode, viewport: result.viewport, valid: result.valid, summary: result.summary }));
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ path }));
      return;
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') { response.writeHead(405).end(); return; }
    const file = resolve(dist, `.${decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname)}`);
    if (!file.startsWith(dist + sep)) { response.writeHead(403).end(); return; }
    const bytes = await readFile(file);
    response.writeHead(200, { 'Content-Type': mime[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
    response.end(request.method === 'HEAD' ? undefined : bytes);
  } catch (error) {
    if (error?.code === 'ENOENT' || error?.code === 'EISDIR') response.writeHead(404).end();
    else { console.error(error); response.writeHead(400).end(); }
  }
});
server.listen(port, '127.0.0.1', () => {
  console.log(`Open in IntelliJ: http://127.0.0.1:${port}/?scenario=huge-content-transcript&singleTall=1`);
  console.log('Add &run=automatic for an unattended automatic run. Ctrl+C stops this fixture server.');
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
