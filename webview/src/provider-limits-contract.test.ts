import { expect, it } from 'vitest';
import { parseExtensionMessage } from '../vendor/shared/extension-message';

it('accepts the native quota event with nullable bounds and rejects the former stripped-null wire shape', () => {
  const status = {
    providerID: 'openrouter', modelID: null, source: 'provider', status: 'available', checkedAt: 1_800_000_000_000,
    windows: [{ id: 'spend', label: 'Spend', unit: 'usd', remaining: 25, limit: null, resetAt: null }],
  };
  const message = { type: 'provider-limit/updated', payload: { directory: null, status } };
  expect(parseExtensionMessage(message)).toEqual(message);
  const formerWire = JSON.parse(JSON.stringify(message, (_key, value: unknown) => value === null ? undefined : value));
  expect(parseExtensionMessage(formerWire)).toBeNull();
});

it.each([62_500, 62500.5, 0])('accepts the native Codex credit balance %s without quota windows', (creditBalance) => {
  const message = {
    type: 'provider-limit/updated',
    payload: { directory: null, status: {
      providerID: 'openai', modelID: null, source: 'provider', status: 'available', checkedAt: 1_800_000_000_000,
      windows: [], creditBalance,
    } },
  };
  expect(parseExtensionMessage(message)).toEqual(message);
});

it.each(['62500', null, NaN, Infinity, -1])('rejects an invalid native credit balance %s', (creditBalance) => {
  expect(parseExtensionMessage({
    type: 'provider-limit/updated',
    payload: { directory: null, status: {
      providerID: 'openai', modelID: null, source: 'provider', status: 'available', checkedAt: 1_800_000_000_000,
      windows: [], creditBalance,
    } },
  })).toBeNull();
});
