import { beforeEach, expect, it, vi } from 'vitest';
import { isProviderAuthFailure } from '../vendor/shared/error-classification';
import { getProviderUsageLink } from '../vendor/webview/lib/provider-usage';

const postMessage = vi.hoisted(() => vi.fn());
vi.mock('../vendor/webview/lib/bridge', () => ({ postMessage }));
import { openProviderSetup } from '../vendor/webview/lib/provider-setup';

beforeEach(() => postMessage.mockClear());

it('routes Claude Agent login through the native terminal instead of OpenCode auth', () => {
  openProviderSetup('claude-agent');
  expect(postMessage).toHaveBeenCalledExactlyOnceWith({
    type: 'terminal/run', payload: { command: 'claude auth login', title: 'Claude Code Login' },
  });
});

it('retains OpenCode login for other providers', () => {
  openProviderSetup('anthropic');
  expect(postMessage).toHaveBeenCalledExactlyOnceWith({
    type: 'terminal/run', payload: { command: 'opencode auth login', title: 'OpenCode Provider Setup' },
  });
});

it.each(['Claude Code is not logged in', 'Run `claude auth login` in a terminal'])('recognizes login recovery: %s', (message) => {
  expect(isProviderAuthFailure({ data: { message } })).toBe(true);
  expect(isProviderAuthFailure({ data: { message: 'Claude Code subscription limit reached' } })).toBe(false);
});

it('links Claude Agent quota details to the Claude account', () => {
  expect(getProviderUsageLink('claude-agent')).toEqual({ label: 'Claude Usage', url: 'https://claude.ai/settings/usage' });
});
