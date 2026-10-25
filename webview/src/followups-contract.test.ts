import { expect, it } from 'vitest';
import { parseExtensionMessage } from '../vendor/shared/extension-message';
import { getFollowupActionTitle, parseFollowupSource, parseFollowupSuggestions } from '../vendor/shared/followups';
import type { Message, Part } from '../vendor/shared/opencode-types';

it.each([true, false])('accepts application follow-up preferences from the Kotlin host: %s', (followupsEnabled) => {
  const message = { type: 'config/update', payload: {
    desktopSessionPaneSide: 'right', defaultPermissionMode: 'auto', chatFontSize: 13,
    chatEditorFontSize: 12, chatFontFamily: 'default', followupsEnabled, followupModel: 'provider/model',
  } };
  expect(parseExtensionMessage(JSON.parse(JSON.stringify(message)))).toEqual(message);
});

it('validates bounded source and suggestion JSON without treating it as executable work', () => {
  const source = { assistantMessageId: 'answer', userMessageId: 'user', userPrompt: 'Review the parser', answer: 'One parser bug remains.' };
  expect(parseFollowupSource(source)).toEqual(source);
  const data = { summary: 'One parser bug remains.', actions: [{ title: 'Fix parser', prompt: 'Fix the reported parser bug.' }] };
  const response = parseExtensionMessage({ type: 'api/response', payload: { id: 1, data } });
  expect(response?.type).toBe('api/response');
  expect(parseFollowupSuggestions(JSON.stringify(data))).toEqual(data);
  expect(() => parseFollowupSuggestions(JSON.stringify({ ...data, execute: true }))).toThrow();
});

it('uses explicit native attribution, never matching ordinary prompt text', () => {
  const info: Message = { id: 'user', sessionID: 'session', role: 'user', time: { created: 1 }, agent: 'build', model: { providerID: 'provider', modelID: 'model' } };
  const part: Part = { id: 'text', sessionID: 'session', messageID: 'user', type: 'text', text: 'Fix the reported parser bug.' };
  expect(getFollowupActionTitle(info, [part])).toBeNull();
  expect(getFollowupActionTitle({ ...info, followupTitle: 'Fix parser' }, [part])).toBe('Fix parser');
  expect(getFollowupActionTitle({ ...info, followupTitle: 'Fix parser' }, [{ ...part, synthetic: true }])).toBeNull();
});
