import { expect, it } from 'vitest';
import { parseExtensionMessage } from '../vendor/shared/extension-message';

it.each([null, [], [{ info: { id: 'message-1' }, parts: [{ type: 'text', text: 'é ✓' }] }]])(
  'accepts JSON API response data from the Kotlin host: %j',
  (data) => {
    const message = { type: 'api/response', payload: { id: 1, data } };
    expect(parseExtensionMessage(JSON.parse(JSON.stringify(message)))).toEqual(message);
  }
);

it('preserves JSON API errors from the Kotlin host', () => {
  const message = { type: 'api/response', payload: { id: 2, error: 'Request failed' } };
  expect(parseExtensionMessage(JSON.parse(JSON.stringify(message)))).toEqual(message);
});

it('decodes optional upstream byte responses without changing the JSON contract', () => {
  const data = [{ text: 'é ✓' }];
  const bytes = new TextEncoder().encode(JSON.stringify(data));
  const buffer = new Uint8Array(bytes.length + 4);
  buffer.set(bytes, 2);
  expect(parseExtensionMessage({
    type: 'api/response',
    payload: { id: 3, encodedData: buffer.subarray(2, 2 + bytes.length) },
  })).toEqual({ type: 'api/response', payload: { id: 3, data } });
});

it.each(['[]', new TextEncoder().encode('{"truncated":')])(
  'settles malformed encoded responses with an error',
  (encodedData) => {
    expect(parseExtensionMessage({
      type: 'api/response', payload: { id: 4, encodedData },
    })).toEqual({
      type: 'api/response', payload: { id: 4, error: 'Invalid encoded API response' },
    });
  }
);
