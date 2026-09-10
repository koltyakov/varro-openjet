import { beforeEach, expect, it, vi } from 'vitest';
import type { PermissionMode } from '../vendor/shared/protocol';

const app = vi.hoisted(() => ({
  draft: 'auto' as PermissionMode,
  state: {
    sessionPermissionModes: {} as Record<string, PermissionMode>,
    sessions: [] as { id: string; parentID?: string }[],
  },
}));
vi.mock('../vendor/webview/lib/app-state', () => ({
  state: app.state,
  draftPermissionMode: () => app.draft,
}));
vi.mock('../vendor/webview/lib/bridge', () => ({ postMessage: vi.fn() }));
vi.mock('../vendor/webview/lib/state-storage', () => ({ STORAGE_KEYS: {} }));
vi.mock('../vendor/webview/lib/state-stored-values', () => ({
  readInitialWebviewState: () => ({}),
}));

import { getPermissionModeForSession } from '../vendor/webview/lib/state-permission-modes';

beforeEach(() => {
  app.draft = 'auto';
  app.state.sessions = [];
  app.state.sessionPermissionModes = {};
});

it('uses the current selection for drafts and defaults existing sessions', () => {
  app.state.sessions = [{ id: 'existing' }];
  for (const mode of ['default', 'auto', 'full'] as const) {
    app.draft = mode;
    expect(getPermissionModeForSession(null)).toBe(mode);
    expect(getPermissionModeForSession('existing')).toBe('default');
    expect(getPermissionModeForSession('missing')).toBe('default');
  }
});

it('uses an explicit saved mode', () => {
  app.state.sessionPermissionModes.external = 'default';
  expect(getPermissionModeForSession('external')).toBe('default');
});

it('inherits a parent mode unless the child has its own mode', () => {
  app.state.sessions = [
    { id: 'parent' },
    { id: 'child', parentID: 'parent' },
  ];
  app.state.sessionPermissionModes.parent = 'full';
  expect(getPermissionModeForSession('child')).toBe('full');
  app.state.sessionPermissionModes.child = 'auto';
  expect(getPermissionModeForSession('child')).toBe('auto');
});

it('falls back without looping on cyclic parent references', () => {
  app.state.sessions = [{ id: 'a', parentID: 'b' }, { id: 'b', parentID: 'a' }];
  expect(getPermissionModeForSession('a')).toBe('default');
});
