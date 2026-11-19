import { expect, it } from 'vitest';
import { parseSessionGoal } from '../vendor/shared/session-goal';
import { parseServerEvent } from '../vendor/shared/protocol';

it('accepts native goal updates and clearing under the shared event name', () => {
  const goal = { id: 'goal-1', objective: 'Fixture', status: 'active', rounds: 0, maxRounds: 50, updatedAt: 1 };
  expect(parseSessionGoal(goal)).toEqual(goal);
  for (const next of [goal, null]) {
    const event = { type: 'session.goal.updated', workspaceDirectory: '/repo', properties: { sessionID: 'ses_one', goal: next } };
    expect(parseServerEvent(event)).toEqual(event);
  }
  for (const change of [{ status: 'unknown' }, { rounds: NaN }, { maxRounds: Infinity }, { reason: null }]) {
    expect(parseSessionGoal({ ...goal, ...change })).toBeNull();
  }
});
