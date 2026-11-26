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

it('accepts the host todo-mark and prompt-cache warming snapshots', () => {
  const todo = { type: 'todo-sessions/sync', payload: { sessionIds: ['ses_one'] } };
  expect(parseExtensionMessage(todo)).toEqual(todo);
  // PromptCacheWarming.snapshot serializes explicit nulls through Json.stringifyMessage.
  const warming = {
    type: 'warming/state',
    payload: {
      available: true,
      mode: 'chosen',
      durationMs: 1_800_000,
      intervalMs: 240_000,
      openCodeWarming: false,
      sessions: {
        ses_waiting: { warmUntil: null, sentAt: null, stopped: null },
        ses_warm: { warmUntil: 1_900_000, sentAt: 100_000, stopped: null },
        ses_expired: { warmUntil: 1_900_000, sentAt: 100_000, stopped: 'expired' },
      },
    },
  };
  expect(parseExtensionMessage(warming)).toEqual(warming);
  const missingNull = structuredClone(warming);
  delete (missingNull.payload.sessions.ses_waiting as { warmUntil?: null }).warmUntil;
  expect(parseExtensionMessage(missingNull)).toBeNull();
  for (const command of ['command/open-skill-settings', 'command/open-mcp-settings']) {
    expect(parseExtensionMessage({ type: command })).toEqual({ type: command });
  }
});
