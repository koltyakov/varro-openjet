import { expect, it } from 'vitest';
import { parseExtensionMessage } from '../vendor/shared/extension-message';
import { parseLocalImagePath } from '../vendor/shared/local-images';

it('accepts authoritative pin snapshots without an outstanding RPC', () => {
  for (const sessionIds of [[], ['ses_one', 'ses_two']]) {
    const message = { type: 'pinned-sessions/sync', payload: { sessionIds } };
    expect(parseExtensionMessage(message)).toEqual(message);
  }
  for (const sessionIds of [null, 'ses_one', [1], [''], ['ses_one', null]]) {
    expect(parseExtensionMessage({ type: 'pinned-sessions/sync', payload: { sessionIds } })).toBeNull();
  }
});

it('accepts local file image targets but rejects network and executable paths', () => {
  expect(parseLocalImagePath('file:///workspace/screen%23shot.png')).toBe('/workspace/screen#shot.png');
  expect(parseLocalImagePath('screen%20shot.png')).toBe('screen shot.png');
  for (const path of ['https://example.test/a.png', '\\\\attacker.test\\a.png', '%2F%2Fattacker.test/a.png', 'javascript:alert(1)']) {
    expect(parseLocalImagePath(path)).toBeNull();
  }
});

it('accepts the host todo-mark and learned prompt-cache snapshots', () => {
  const todo = { type: 'todo-sessions/sync', payload: { sessionIds: ['ses_one'] } };
  expect(parseExtensionMessage(todo)).toEqual(todo);
  const learned = {
    type: 'promptCache/learned',
    payload: {
      learned: { 'anthropic/claude-sonnet-5-5': { ms: 3_600_000, bounded: true } },
    },
  };
  expect(parseExtensionMessage(learned)).toEqual(learned);
  expect(parseExtensionMessage({ ...learned, payload: {} })).toBeNull();
  expect(parseExtensionMessage({ ...learned, type: 'warming/state' })).toBeNull();
  for (const command of ['command/open-skill-settings', 'command/open-mcp-settings']) {
    expect(parseExtensionMessage({ type: command })).toEqual({ type: command });
  }
});

it('accepts an explicit opt-out from composer tabs in host config updates', () => {
  const message = { type: 'config/update', payload: {
    desktopSessionPaneSide: 'right', defaultPermissionMode: 'auto',
    chatFontSize: 13, chatEditorFontSize: 12, chatFontFamily: 'default', groupComposerBlocks: false,
  } };
  expect(parseExtensionMessage(message)).toEqual(message);
});
