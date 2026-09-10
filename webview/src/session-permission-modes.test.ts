import { beforeEach, expect, it, vi } from 'vitest';
import type { PermissionMode } from '../vendor/shared/protocol';
import type { PermissionRule } from '../vendor/shared/opencode-types';
import { getSessionPermissionRulesForMode, inferSessionPermissionMode } from '../vendor/shared/permission-rules';

const app = vi.hoisted(() => ({
  draft: 'auto' as PermissionMode,
  state: {
    sessionPermissionModes: {} as Record<string, PermissionMode>,
    sessions: [] as { id: string; parentID?: string; permission?: PermissionRule[] }[],
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

it.each(['auto', 'full'] as const)('recognizes the %s preset stored by another client', (mode) => {
  const permission = getSessionPermissionRulesForMode(mode, 'create');
  expect(inferSessionPermissionMode(permission)).toBe(mode);
  app.state.sessions = [{ id: 'external', permission }];
  expect(getPermissionModeForSession('external')).toBe(mode);
});

it('recognizes a universal allow rule and respects last-match ordering', () => {
  const allow: PermissionRule = { permission: '*', pattern: '*', action: 'allow' };
  const deny: PermissionRule = { permission: 'bash', pattern: '*', action: 'deny' };
  expect(inferSessionPermissionMode([allow])).toBe('full');
  expect(inferSessionPermissionMode([deny, allow])).toBe('full');
  expect(inferSessionPermissionMode([allow, deny])).toBe('default');
});

it('does not label custom permissions as Auto or Full', () => {
  app.draft = 'full';
  for (const permission of [
    [{ permission: 'bash', pattern: '*', action: 'deny' } as PermissionRule],
    [...getSessionPermissionRulesForMode('auto', 'create'),
      { permission: 'bash', pattern: 'git *', action: 'allow' } as PermissionRule],
  ]) {
    app.state.sessions = [{ id: 'external', permission }];
    expect(getPermissionModeForSession('external')).toBe('default');
  }
});

it('uses the current selection when external sessions have no permission rules', () => {
  app.state.sessions = [{ id: 'missing' }, { id: 'empty', permission: [] }];
  for (const mode of ['default', 'auto', 'full'] as const) {
    app.draft = mode;
    expect(getPermissionModeForSession('missing')).toBe(mode);
    expect(getPermissionModeForSession('empty')).toBe(mode);
    expect(getPermissionModeForSession(null)).toBe(mode);
  }
});

it('keeps an explicit saved mode ahead of inference and fallback', () => {
  app.state.sessionPermissionModes.external = 'default';
  app.state.sessions = [{ id: 'external', permission: getSessionPermissionRulesForMode('full', 'create') }];
  expect(getPermissionModeForSession('external')).toBe('default');
});

it('inherits parent permissions unless the child has its own rules', () => {
  app.state.sessions = [
    { id: 'parent', permission: getSessionPermissionRulesForMode('full', 'create') },
    { id: 'child', parentID: 'parent' },
  ];
  expect(getPermissionModeForSession('child')).toBe('full');
  app.state.sessions[1]!.permission = getSessionPermissionRulesForMode('auto', 'create');
  expect(getPermissionModeForSession('child')).toBe('auto');
});

it('falls back without looping on cyclic parent references', () => {
  app.state.sessions = [{ id: 'a', parentID: 'b' }, { id: 'b', parentID: 'a' }];
  expect(getPermissionModeForSession('a')).toBe('auto');
});
