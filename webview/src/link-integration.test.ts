import { expect, it, vi } from 'vitest';
import { installLinkIntegration, readLinkContext } from './link-integration';

function setup() {
  const listeners = new Map<string, (event: MouseEvent) => void>();
  const register = (type: string, listener: (event: MouseEvent) => void) => {
    listeners.set(type, listener);
  };
  const addEventListener = vi.fn(register);
  const addDocumentListener = vi.fn(register);
  const send = vi.fn();
  installLinkIntegration(
    { addEventListener: addDocumentListener } as unknown as Document,
    { addEventListener } as unknown as Window,
    send
  );
  return { listeners, addEventListener, addDocumentListener, send };
}

function event(attribute: string | null, prevented = false) {
  return {
    defaultPrevented: prevented,
    button: 0,
    clientX: 24,
    clientY: 48,
    target: { closest: () => ({ getAttribute: () => attribute }) },
    preventDefault: vi.fn(),
    stopPropagation: vi.fn(),
  } as unknown as MouseEvent;
}

it.each(['http://localhost:3000', 'http://example.test/docs?q=one&two=2', 'https://example.test'])(
  'opens an otherwise unhandled link once: %s',
  (url) => {
    const { listeners, addEventListener, send } = setup();
    const click = event(url);
    listeners.get('click')!(click);
    expect(addEventListener.mock.calls[0]).toHaveLength(2); // Window bubble, after Solid's document handlers.
    expect(send).toHaveBeenCalledExactlyOnceWith({
      type: 'vscode/open-external',
      payload: { url },
    });
    expect(click.preventDefault).toHaveBeenCalledOnce();
  }
);

it('does not open a link already handled by the shared UI a second time', () => {
  const { listeners, send } = setup();
  listeners.get('click')!(event('https://example.test', true));
  expect(send).not.toHaveBeenCalled();
});

it.each(['javascript:alert(1)', 'file:///repo/file.ts', 'varro-content:test', 'not a URL'])(
  'blocks navigation without opening unsafe or local URLs: %s',
  (url) => {
    const { listeners, send } = setup();
    const click = event(url);
    listeners.get('click')!(click);
    expect(send).not.toHaveBeenCalled();
    expect(click.preventDefault).toHaveBeenCalledOnce();
  }
);

it('leaves hash links and non-primary clicks alone', () => {
  const { listeners, send } = setup();
  const hash = event('#section');
  const middle = event('https://example.test');
  Object.assign(middle, { button: 1 });
  for (const click of [hash, middle]) {
    listeners.get('click')!(click);
    expect(click.preventDefault).not.toHaveBeenCalled();
  }
  expect(send).not.toHaveBeenCalled();
});

it.each([
  { webviewSection: 'varroExternalLink', varroLinkUrl: 'http://localhost:3000' },
  { webviewSection: 'varroFileLink', varroFilePath: '/repo/folder with spaces/file.ts' },
  { webviewSection: 'varroFileLink', varroFilePath: 'C:/repo/src/App.tsx' },
])('requests the native copy menu without opening its target: %j', (context) => {
  const { listeners, send } = setup();
  const menu = event(JSON.stringify(context));
  listeners.get('contextmenu')!(menu);
  expect(send).toHaveBeenCalledExactlyOnceWith({
    type: 'host/link-context-menu',
    payload: { ...context, x: 24, y: 48 },
  });
  expect(menu.preventDefault).toHaveBeenCalledOnce();
  expect(menu.stopPropagation).toHaveBeenCalledOnce();
});

it.each([
  undefined,
  null,
  '',
  '{',
  'null',
  '42',
  '{}',
  JSON.stringify({ webviewSection: 'varroExternalLink', varroLinkUrl: 'javascript:alert(1)' }),
  JSON.stringify({ webviewSection: 'varroExternalLink', varroLinkUrl: 42 }),
  JSON.stringify({ webviewSection: 'varroFileLink', varroFilePath: ' ' }),
  JSON.stringify({ webviewSection: 'varroFileLink', varroFilePath: 42 }),
])('ignores malformed or unsupported menu context: %s', (raw) => {
  expect(readLinkContext(raw)).toBeNull();
  const { listeners, send } = setup();
  const menu = event(raw ?? null);
  listeners.get('contextmenu')!(menu);
  expect(send).not.toHaveBeenCalled();
  expect(menu.preventDefault).not.toHaveBeenCalled();
});
