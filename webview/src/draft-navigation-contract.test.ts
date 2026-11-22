import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { installProjectStorage } from './project-storage';
import { installViewStateChannel } from './view-state';

beforeEach(() => vi.resetModules());
afterEach(() => vi.unstubAllGlobals());

async function setup(surface: 'editor' | 'sidebar' = 'editor', initial: Record<string, unknown> = {}) {
  const send = vi.fn();
  const host = Object.assign(new EventTarget(), {
    __varroInitialViewState: initial,
    __sendToExtension: send,
    __initialWebviewState: {
      browserStorage: {},
      editorContext: { workspacePath: '/repo' },
      webviewContext: { viewId: `${surface}-one`, surface, initialRoute: { type: 'session', sessionId: 'root' } },
    },
  });
  installViewStateChannel(host);
  const storage = installProjectStorage(host);
  vi.stubGlobal('window', host);
  vi.stubGlobal('document', Object.assign(new EventTarget(), { visibilityState: 'visible' }));
  const { registerHostExtension } = await import('../vendor/webview/host/extensions');
  const viewState = (host as typeof host & {
    __vscodeWebviewState: { getState(): Record<string, unknown>; setState(state: Record<string, unknown>): void };
  }).__vscodeWebviewState;
  registerHostExtension({
    apiVersion: 1, id: 'openjet.host',
    services: { projectStorage: storage, viewState, send },
  });
  const { createAppState } = await import('../vendor/webview/lib/app-state');
  const { flush } = await import('solid-js');
  return { app: createAppState(), host, viewState, send, createAppState, flush };
}

it.each(['editor', 'sidebar'] as const)('keeps the %s draft across sessions and the picker using per-view IDE storage', async (surface) => {
  const { app, viewState, send, createAppState, flush } = await setup(surface);
  app.setState((draft) => { draft.activeSessionId = 'root'; });
  app.setInputText('Carry this unsent input');
  flush();
  const owner = app.currentDraftKey();
  app.setState((draft) => { draft.activeSessionId = 'child'; });
  expect(app.inputText()).toBe('Carry this unsent input');
  expect(app.currentDraftKey()).toBe(owner);
  app.setShowSessionPicker(true);
  expect(app.inputText()).toBe('Carry this unsent input');
  app.setShowSessionPicker(false);
  app.setState((draft) => { draft.activeSessionId = 'root'; });
  expect(app.inputText()).toBe('Carry this unsent input');
  await Promise.resolve();
  expect(viewState.getState()[owner]).toBe('Carry this unsent input');
  expect(createAppState().inputText()).toBe('Carry this unsent input');
  expect(send.mock.calls.some(([message]) => message.type === 'host/storage' && message.payload.key.startsWith('varro.inputDraft'))).toBe(false);
  app.setInputText('');
  flush();
  app.setState((draft) => { draft.activeSessionId = 'child'; });
  expect(app.inputText()).toBe('');
  expect(createAppState().inputText()).toBe('');
});

it('keeps workspace drafts isolated and never applies a late write to the active workspace', async () => {
  const { app, flush } = await setup();
  app.setInputText('Workspace A');
  flush();
  const owner = app.currentDraftKey();
  app.setState((draft) => { draft.editorContext.workspacePath = '/other'; draft.activeSessionId = 'other'; });
  flush();
  expect(app.inputText()).toBe('');
  app.setInputText('Workspace B');
  flush();
  app.writeOwnedDraft(owner, 'Late result for A');
  expect(app.inputText()).toBe('Workspace B');
  app.setState((draft) => { draft.editorContext.workspacePath = '/repo'; draft.activeSessionId = 'root'; });
  flush();
  expect(app.inputText()).toBe('Late result for A');
});

it('adopts old session drafts without overwriting current shared input or another view', async () => {
  const shared = `varro.inputDraft:${JSON.stringify(['editor-one', '/repo', null])}`;
  const legacy = `varro.inputDraft:${JSON.stringify(['editor-one', '/repo', 'root'])}`;
  const other = `varro.inputDraft:${JSON.stringify(['editor-two', '/repo', null])}`;
  const { app, viewState, flush } = await setup('editor', { [shared]: 'Current input', [legacy]: 'Parked session draft', [other]: 'Other editor' });
  app.setState((draft) => { draft.activeSessionId = 'root'; });
  flush();
  expect(app.inputText()).toBe('Current input');
  expect(viewState.getState()[legacy]).toBe('Parked session draft');
  app.setInputText('');
  flush();
  app.setShowSessionPicker(true);
  app.setShowSessionPicker(false);
  flush();
  expect(app.inputText()).toBe('Parked session draft');
  expect(viewState.getState()[legacy]).toBeUndefined();
  expect(viewState.getState()[other]).toBe('Other editor');
});
