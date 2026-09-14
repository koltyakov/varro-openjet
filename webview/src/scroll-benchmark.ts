type Frame = { time: number; top: number; bottom: number; rows?: number; nodes?: number };
type LongTask = { start: number; duration: number };
type Mode = 'manual' | 'automatic' | 'manual-streaming';
type Entry = {
  info: { id: string; sessionID: string; role: string; time: { created: number; completed?: number } };
  parts: Array<{ type: string; text?: string }>;
};
type BenchmarkWindow = Window & {
  __varroE2E?: {
    getSessionMessages(sessionId: string): Entry[];
    replayServerEvent(event: unknown): void;
  };
  __varroBenchmark?: { run(mode: Mode): Promise<unknown>; cancel(): void };
};

export function summarizeFrames(frames: Frame[], tasks: LongTask[]) {
  const gaps = frames.slice(1).map((frame, index) => frame.time - frames[index]!.time).sort((a, b) => a - b);
  const percentile = (fraction: number) => gaps[Math.min(gaps.length - 1, Math.floor(gaps.length * fraction))] ?? null;
  const tops = frames.map((frame) => frame.top);
  return {
    frames: frames.length,
    p50FrameMs: percentile(0.5), p95FrameMs: percentile(0.95), p99FrameMs: percentile(0.99),
    maxFrameMs: gaps.at(-1) ?? null,
    gapsOver25ms: gaps.filter((gap) => gap > 25).length,
    gapsOver50ms: gaps.filter((gap) => gap > 50).length,
    longTasks: tasks.length,
    maxLongTaskMs: Math.max(0, ...tasks.map((task) => task.duration)),
    peakRows: Math.max(0, ...frames.map((frame) => frame.rows ?? 0)),
    peakNodes: Math.max(0, ...frames.map((frame) => frame.nodes ?? 0)),
    movementPx: tops.length ? Math.max(...tops) - Math.min(...tops) : 0,
    finalBottomDistance: frames.at(-1)?.bottom ?? null,
    backwardsSteps: frames.slice(1).filter((frame, index) => frame.top < frames[index]!.top - 1).length,
  };
}

/** Imported only by the diagnostic build, never by the ordinary chat entry point. */
export function installScrollingBenchmark(token: string, build: unknown) {
  const host = window as BenchmarkWindow;
  const params = new URLSearchParams(location.search);
  const scenario = params.get('scenario') ?? 'large-transcript';
  const nativeHost: unknown = JSON.parse(params.get('nativeHost') ?? 'null');
  const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
  let running: { cancelled: boolean } | undefined;
  const controls = document.createElement('div');
  controls.style.cssText = 'position:fixed;z-index:2147483647;top:4px;right:8px;max-width:420px;padding:8px;background:#252526;color:#eee;border:1px solid #888;font:12px system-ui';
  const status = document.createElement('div');
  status.textContent = 'Synthetic fixture. Manual runs record 10 seconds of your wheel or keyboard input.';
  controls.append(status);
  document.body.append(controls);
  const showStatus = (text: string) => { status.textContent = text; };

  async function run(mode: Mode) {
    if (running) throw new Error('A benchmark is already running');
    const runState = { cancelled: false };
    running = runState;
    const errors: string[] = [];
    const frames: Frame[] = [];
    const tasks: LongTask[] = [];
    let raf = 0;
    let observer: PerformanceObserver | undefined;
    let hidden = document.hidden;
    let resized = false;
    let wheelEvents = 0;
    let keyEvents = 0;
    const onVisibility = () => { hidden ||= document.hidden; };
    const onResize = () => { resized = true; };
    const onWheel = (event: WheelEvent) => { if (event.isTrusted) wheelEvents++; };
    const onKey = (event: KeyboardEvent) => {
      if (event.isTrusted && ['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) keyEvents++;
    };
    const onError = (event: ErrorEvent) => { errors.push(event.message); };
    const checkCancelled = () => { if (runState.cancelled) throw new Error('Benchmark cancelled'); };
    const until = async (condition: () => boolean, timeout: number) => {
      const deadline = performance.now() + timeout;
      while (!condition()) {
        checkCancelled();
        if (performance.now() > deadline) throw new Error('Timed out waiting for fixture rendering or bottom follow');
        await delay(50);
      }
    };
    try {
      showStatus('Waiting for the transcript to settle.');
      await until(() => !!document.querySelector('.interactive-list') && !!host.__varroE2E, 20_000);
      const list = document.querySelector<HTMLElement>('.interactive-list')!;
      if (list.clientHeight <= 0 || list.clientHeight > innerHeight) {
        throw new Error('Invalid fixture layout: transcript viewport does not fit the browser. Check generated CSS.');
      }
      await until(() => list.scrollHeight - list.clientHeight - list.scrollTop < 2, 20_000);
      await delay(1000);
      checkCancelled();
      const fixture = host.__varroE2E!;
      const messages = fixture.getSessionMessages(`session-${scenario}`);
      const assistant = messages.findLast((entry) => entry.info.role === 'assistant');
      if (!assistant) throw new Error('This fixture has no assistant response');
      const fixtureSize = {
        messages: messages.length,
        textCharacters: messages.flatMap((entry) => entry.parts).reduce((size, part) => size + (part.text?.length ?? 0), 0),
        serializedBytes: new TextEncoder().encode(JSON.stringify(messages)).length,
      };
      const viewport = { width: innerWidth, height: innerHeight, listWidth: list.clientWidth, listHeight: list.clientHeight, devicePixelRatio };
      const started = performance.now();
      controls.style.visibility = 'hidden';
      document.addEventListener('visibilitychange', onVisibility);
      window.addEventListener('resize', onResize);
      list.addEventListener('wheel', onWheel, { passive: true });
      list.addEventListener('keydown', onKey);
      window.addEventListener('error', onError);
      const longTasksSupported = PerformanceObserver.supportedEntryTypes.includes('longtask');
      if (longTasksSupported) {
        observer = new PerformanceObserver((entries) => {
          for (const entry of entries.getEntries()) tasks.push({ start: entry.startTime - started, duration: entry.duration });
        });
        observer.observe({ type: 'longtask' });
      }
      const sample = (time: number) => {
        if (frames.length >= 12_000) { runState.cancelled = true; return; }
        frames.push({ time: time - started, top: list.scrollTop,
          bottom: list.scrollHeight - list.clientHeight - list.scrollTop,
          ...(frames.length % 10 === 0 ? { rows: list.querySelectorAll('[data-msg-id]').length } : {}),
          ...(frames.length % 30 === 0 ? { nodes: list.querySelectorAll('*').length } : {}),
        });
        raf = requestAnimationFrame(sample);
      };
      raf = requestAnimationFrame(sample);
      const partId = `benchmark-${crypto.randomUUID()}`;
      const send = (type: string, properties: unknown) => fixture.replayServerEvent({ type, properties });
      if (mode !== 'manual') {
        const info = { ...assistant.info, time: { created: assistant.info.time.created } };
        send('session.status', { sessionID: info.sessionID, status: { type: 'busy' } });
        send('message.updated', { info });
        send('message.part.updated', { part: { id: partId, type: 'text', sessionID: info.sessionID, messageID: info.id, text: '' } });
        performance.mark('varro-benchmark-stream-start');
        for (let index = 0; index < 120; index++) {
          checkCancelled();
          send('message.part.delta', { sessionID: info.sessionID, messageID: info.id, partID: partId, field: 'text',
            delta: `\n\nBenchmark paragraph ${index}: incremental Markdown must follow the growing answer smoothly.` });
          await delay(32);
        }
        performance.mark('varro-benchmark-stream-complete');
        send('message.updated', { info: { ...info, time: { ...info.time, completed: Date.now() } } });
        send('session.status', { sessionID: info.sessionID, status: { type: 'idle' } });
        if (mode === 'automatic') {
          await until(() => list.textContent?.includes('Benchmark paragraph 119:') === true &&
            list.scrollHeight - list.clientHeight - list.scrollTop < 2, 20_000);
        }
      }
      if (mode !== 'automatic') await delay(Math.max(0, 10_000 - (performance.now() - started)));
      await delay(500);
      checkCancelled();
      cancelAnimationFrame(raf);
      observer?.disconnect();
      const summary = summarizeFrames(frames, tasks);
      const valid = !hidden && !resized && !errors.length && summary.frames > 0 && summary.movementPx > 100 &&
        (mode === 'automatic' ? summary.finalBottomDistance !== null && summary.finalBottomDistance < 2 && summary.backwardsSteps === 0 : wheelEvents + keyEvents > 0);
      const result = { kind: 'varro-jcef-scroll-benchmark', build, nativeHost,
        scenario, singleTall: params.get('singleTall') === '1', mode,
        userAgent: navigator.userAgent, viewport, fixture: fixtureSize, durationMs: performance.now() - started,
        longTasksSupported, hidden, resized, wheelEvents, keyEvents, errors, valid,
        performanceNeedsReview: !longTasksSupported || errors.length > 0 || (summary.p95FrameMs ?? 0) > 25 ||
          (summary.maxFrameMs ?? 0) > 100 || summary.maxLongTaskMs > 100,
        summary, frames, tasks };
      const response = await fetch('/results', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Varro-Benchmark': token }, body: JSON.stringify(result) });
      if (!response.ok) throw new Error(`Result save failed: HTTP ${response.status}`);
      const saved = await response.json() as { path: string };
      showStatus(`${valid ? 'Recorded' : 'Invalid run'}: p95 ${summary.p95FrameMs?.toFixed(1)} ms, max ${summary.maxFrameMs?.toFixed(1)} ms. Saved ${saved.path}. Reload before another run.`);
      return result;
    } catch (error) {
      showStatus(error instanceof Error ? error.message : String(error));
      throw error;
    } finally {
      cancelAnimationFrame(raf);
      observer?.disconnect();
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('resize', onResize);
      const list = document.querySelector('.interactive-list');
      list?.removeEventListener('wheel', onWheel as EventListener);
      list?.removeEventListener('keydown', onKey as EventListener);
      window.removeEventListener('error', onError);
      controls.style.visibility = 'visible';
      running = undefined;
    }
  }

  host.__varroBenchmark = { run, cancel: () => { if (running) running.cancelled = true; } };
  for (const mode of ['manual', 'automatic', 'manual-streaming'] as const) {
    const button = document.createElement('button');
    button.style.cssText = 'margin:6px 4px 0 0;padding:4px 6px;border:1px solid #888;border-radius:3px;cursor:pointer';
    button.textContent = mode;
    button.onclick = () => { void run(mode).catch(() => undefined); };
    controls.append(button);
  }
  const reload = document.createElement('button');
  reload.style.cssText = 'margin:6px 4px 0 0;padding:4px 6px;border:1px solid #888;border-radius:3px;cursor:pointer';
  reload.textContent = 'Reset fixture';
  reload.onclick = () => location.reload();
  controls.append(reload);
  const mode = params.get('run');
  if (mode === 'manual' || mode === 'automatic' || mode === 'manual-streaming') void run(mode).catch(() => undefined);
}
