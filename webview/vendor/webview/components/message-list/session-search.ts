import type { MessageEntry } from '../../types';
import { isWorkspaceDirectoryText } from '../../lib/part-utils';
import { hasUserMessageContent, parseUserMessageContent } from '../message/UserMessageContent';

export type SessionSearchDocument = {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  created: number;
};

export type SessionSearchResult = SessionSearchDocument & {
  before: string;
  match: string;
  after: string;
};

export function getSessionSearchDocuments(
  messages: readonly MessageEntry[],
  sessionId: string
): SessionSearchDocument[] {
  return messages.flatMap(({ info, parts }) => {
    if (info.sessionID !== sessionId) return [];
    const prompt = info.role === 'user' ? parseUserMessageContent(parts) : undefined;
    const text = (
      prompt
        ? prompt.messageTexts.join('\n').trim() ||
          (hasUserMessageContent(prompt)
            ? prompt.fileParts
                .map((part) => part.filename)
                .filter(Boolean)
                .join(', ') || 'Prompt with attachments'
            : '')
        : parts
            .filter((part) => part.type === 'text' && !isWorkspaceDirectoryText(part.text))
            .map((part) => (part.type === 'text' ? part.text : ''))
            .join('\n')
    )
      .replace(/\s+/g, ' ')
      .trim();
    return text ? [{ id: info.id, role: info.role, text, created: info.time.created }] : [];
  });
}

export function searchSessionDocuments(
  documents: readonly SessionSearchDocument[],
  query: string
): SessionSearchResult[] {
  const needle = query.replace(/\s+/g, ' ').trim();
  if (!needle) return [];
  // Escape the query so punctuation is literal. The match retains original-case offsets.
  const pattern = new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i');
  return documents.flatMap((document) => {
    const match = pattern.exec(document.text);
    if (!match) return [];
    const start = Math.max(0, match.index - 65);
    const end = Math.min(document.text.length, match.index + match[0].length + 100);
    return [
      {
        ...document,
        before: `${start > 0 ? '…' : ''}${document.text.slice(start, match.index)}`,
        match: match[0],
        after: `${document.text.slice(match.index + match[0].length, end)}${end < document.text.length ? '…' : ''}`,
      },
    ];
  });
}
