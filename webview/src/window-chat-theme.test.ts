import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { parseExtensionMessage } from '../vendor/shared/extension-message';
import type { WindowChatTheme } from '../vendor/shared/protocol';

vi.mock('../vendor/webview/lib/state-stored-values', () => ({ readInitialWebviewState: () => ({ theme: 'dark' }) }));
vi.mock('../vendor/webview/lib/state', () => ({ setTheme: vi.fn() }));
vi.mock('../vendor/webview/lib/bridge', () => ({ postMessage: vi.fn() }));

import { postMessage } from '../vendor/webview/lib/bridge';
import { setTheme } from '../vendor/webview/lib/state';
import { syncWindowChatTheme, toggleWindowChatTheme, windowChatThemeReversed } from '../vendor/webview/lib/window-chat-theme';

const pair: WindowChatTheme = {
  source: 'IDE theme', reversed: true,
  counterpart: { name: 'Varro light', kind: 'light', colors: {} },
};

const styles = new Set<{ textContent: string }>();
const classes = new Set<string>();
beforeEach(() => {
  vi.stubGlobal('document', {
    head: { append: (style: { textContent: string }) => styles.add(style) },
    createElement: () => {
      const style = { textContent: '', remove: () => styles.delete(style) };
      return style;
    },
    body: {
      classList: {
        remove: (...names: string[]) => names.forEach((name) => classes.delete(name)),
        add: (name: string) => classes.add(name),
        contains: (name: string) => classes.has(name),
      },
      dataset: {},
    },
  });
  vi.stubGlobal('MutationObserver', class { observe() {} disconnect() {} });
});

afterEach(() => {
  syncWindowChatTheme({ theme: 'dark' });
  styles.clear();
  classes.clear();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

it('preserves the native theme payload and rejects malformed saved preferences', () => {
  const message = { type: 'theme/update', payload: { theme: 'dark', windowChatTheme: pair } };
  expect(parseExtensionMessage(message)).toEqual(message);
  expect(parseExtensionMessage({ ...message, payload: {
    ...message.payload, windowChatTheme: { ...pair, reversed: 'true' },
  } })).toBeNull();
});

it('restores the reverse preference and follows the opposite of a new IDE theme', () => {
  expect(syncWindowChatTheme({ theme: 'dark', windowChatTheme: pair })).toBe('light');
  expect([...styles][0]?.textContent).toContain('--vscode-editor-background: #ffffff !important');
  expect(syncWindowChatTheme({ theme: 'light', windowChatTheme: {
    ...pair, counterpart: { name: 'Varro dark', kind: 'dark', colors: {} },
  } })).toBe('dark');
  expect(styles.size).toBe(1);
  toggleWindowChatTheme();
  expect(windowChatThemeReversed()).toBe(false);
  expect(styles.size).toBe(0);
  expect(setTheme).toHaveBeenLastCalledWith('light');
  expect(postMessage).toHaveBeenLastCalledWith({ type: 'window-chat-theme/set-reversed', payload: { reversed: false } });
  expect(classes.has('vscode-light')).toBe(true);
});

it('removes the override for sidebar payloads and ignores toggles there', () => {
  syncWindowChatTheme({ theme: 'dark', windowChatTheme: pair });
  expect(syncWindowChatTheme({ theme: 'dark' })).toBe('dark');
  toggleWindowChatTheme();
  expect(windowChatThemeReversed()).toBe(false);
  expect(styles.size).toBe(0);
  expect(postMessage).not.toHaveBeenCalled();
});
