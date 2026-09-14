#!/usr/bin/env node
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const endpoint = new URL(option('--cdp', 'http://127.0.0.1:9222'));
const origin = new URL(option('--origin', 'http://127.0.0.1:4186')).origin;
if (endpoint.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(endpoint.hostname)) throw new Error('Use a loopback CDP endpoint');
const mode = option('--mode', 'automatic');
if (!['automatic', 'manual', 'manual-streaming'].includes(mode)) throw new Error('Invalid --mode');
const targets = await fetch(new URL('/json/list', endpoint)).then((response) => response.json());
const matches = targets.filter((target) => target.type === 'page' && target.url.startsWith(origin + '/'));
if (matches.length !== 1) throw new Error(`Expected exactly one fixture page on ${origin}; found ${matches.length}`);
const target = matches[0];
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((accept, reject) => { socket.addEventListener('open', accept, { once: true }); socket.addEventListener('error', reject, { once: true }); });
let nextId = 0;
const pending = new Map();
const events = new Map();
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  if (message.id) {
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timer);
    if (message.error) request.reject(new Error(JSON.stringify(message.error))); else request.resolve(message.result);
  } else if (events.has(message.method)) {
    events.get(message.method)(message.params);
    events.delete(message.method);
  }
});
socket.addEventListener('close', () => {
  for (const request of pending.values()) { clearTimeout(request.timer); request.reject(new Error('CDP connection closed')); }
  pending.clear();
});
function call(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`Timed out: ${method}`)); }, 60_000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const response = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (response.exceptionDetails) throw new Error(JSON.stringify(response.exceptionDetails));
  return response.result.value;
}
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let tracing = false;
try {
  await call('Page.enable');
  if (args.includes('--inspect')) {
    console.log(JSON.stringify(await evaluate(`(() => {
      const list = document.querySelector('.interactive-list');
      return { href: location.href, nativeHost: JSON.parse(new URL(location.href).searchParams.get('nativeHost') ?? 'null'), hidden: document.hidden,
        dimensions: { width: innerWidth, height: innerHeight },
        list: list && { top: list.scrollTop, height: list.scrollHeight, clientHeight: list.clientHeight, text: list.textContent.slice(-1500) },
        tailSegments: [...document.querySelectorAll('[data-markdown-segment="tail"]')].slice(-3).map(el => ({ length: el.textContent.length, text: el.textContent.slice(-200), display: el.style.display })),
        controls: document.body.lastElementChild?.textContent };
    })()`), null, 2));
    socket.close();
    process.exit(0);
  }
  const url = new URL(target.url);
  url.searchParams.delete('run');
  if (args.includes('--scenario')) {
    url.searchParams.set('scenario', option('--scenario'));
    url.searchParams.delete('singleTall');
  }
  if (args.includes('--single-tall')) url.searchParams.set('singleTall', '1');
  await call('Page.navigate', { url: url.href });
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (await evaluate('!!window.__varroBenchmark && !!document.querySelector(".interactive-list")')) break;
    await delay(100);
  }
  if (!await evaluate('!!window.__varroBenchmark')) throw new Error('Fixture did not initialize');
  await delay(1500);
  const parent = resolve(dirname(fileURLToPath(import.meta.url)), '../../build/scroll-benchmark');
  await mkdir(parent, { recursive: true });
  const output = await mkdtemp(resolve(parent, 'cdp-'));
  if (args.includes('--trace')) {
    await call('Tracing.start', {
      categories: 'devtools.timeline,blink.user_timing,toplevel' +
        (args.includes('--invalidations') ? ',disabled-by-default-devtools.timeline.invalidationTracking' : ''),
      transferMode: 'ReturnAsStream',
    });
    tracing = true;
  }
  const resultPromise = evaluate(`window.__varroBenchmark.run(${JSON.stringify(mode)})`);
  // Prevent a rejected browser promise from becoming an unhandled rejection while input is sent.
  resultPromise.catch(() => undefined);
  if (mode !== 'automatic') {
    await delay(2000);
    const box = await evaluate('(() => { const r = document.querySelector(".interactive-list").getBoundingClientRect(); return { x: r.x + r.width * 0.65, y: r.y + r.height * 0.5 }; })()');
    for (const direction of [-1, 1]) {
      for (let step = 0; step < 60; step++) {
        await call('Input.dispatchMouseEvent', { type: 'mouseWheel', x: box.x, y: box.y, deltaX: 0, deltaY: direction * 100 });
        await delay(25);
      }
    }
  }
  const result = await resultPromise;
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ target, ...result }, null, 2), { flag: 'wx' });
  if (tracing) {
    const finished = new Promise((resolve) => events.set('Tracing.tracingComplete', resolve));
    await call('Tracing.end');
    const { stream } = await finished;
    let trace = '';
    for (;;) {
      const chunk = await call('IO.read', { handle: stream });
      trace += chunk.base64Encoded ? Buffer.from(chunk.data, 'base64').toString('utf8') : chunk.data;
      if (chunk.eof) break;
    }
    await call('IO.close', { handle: stream });
    tracing = false;
    await writeFile(resolve(output, 'trace.json'), trace, { flag: 'wx' });
  }
  if (args.includes('--screenshot')) {
    const screenshot = await call('Page.captureScreenshot', { format: 'png' });
    await writeFile(resolve(output, 'viewport.png'), Buffer.from(screenshot.data, 'base64'), { flag: 'wx' });
  }
  console.log(JSON.stringify({ output, nativeHost: result.nativeHost, valid: result.valid, performanceNeedsReview: result.performanceNeedsReview, userAgent: result.userAgent, viewport: result.viewport, summary: result.summary }, null, 2));
  if (!result.valid) process.exitCode = 1;
} finally {
  if (tracing) await call('Tracing.end').catch(() => undefined);
  socket.close();
}
