import { expect, it } from 'vitest';
import { parseExtensionMessage } from '../vendor/shared/extension-message';

it.each(['opened', 'unavailable', 'cancelled'])(
  'accepts the Kotlin file-open result %s',
  (status) => {
    const message = { type: 'vscode/open-result', payload: { requestId: 42, status } };
    expect(parseExtensionMessage(JSON.parse(JSON.stringify(message)))).toEqual(message);
  }
);

it('rejects unknown file-open results', () => {
  expect(parseExtensionMessage({
    type: 'vscode/open-result', payload: { requestId: 42, status: 'guessed' },
  })).toBeNull();
});
