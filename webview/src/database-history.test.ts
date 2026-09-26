import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { DatabaseContext } from './database-context';
import type { TextPart } from '../vendor/webview/types';
import { formatDatabaseContext } from './database-context';
import { databaseProvider } from './database-extension';
import { registerHostExtension } from '../vendor/webview/host/extensions';

let dispose: () => void;
beforeEach(() => {
  dispose = registerHostExtension({
    apiVersion: 1,
    id: 'openjet.test',
    contexts: [databaseProvider],
  });
});
afterEach(() => dispose());
import { getUserMessageHistoryText } from '../vendor/webview/components/chat-input/message-usage';

const context: DatabaseContext = {
  name: 'Demo SQLite',
  dataSource: 'Demo SQLite',
  dialect: 'SQLite',
  filter: '',
  scope: 'datasource',
  origin: 'workspace',
  columns: [],
  rows: [],
  selectedRowCount: 0,
  pendingChanges: false,
  cellEditing: false,
  pageStart: 0,
  truncated: false,
  dataSources: [
    {
      id: 'demo',
      name: 'Demo SQLite',
      dialect: 'SQLite',
      connected: true,
      catalogs: [],
      schemas: ['main'],
    },
  ],
};
const part = (text: string): TextPart => ({
  type: 'text',
  id: 'part',
  sessionID: 'session',
  messageID: 'message',
  text,
});

describe('database context in Up/Down prompt history', () => {
  it('recalls only the prompt when automatic context is a separate part', () => {
    expect(
      getUserMessageHistoryText([
        part("What's the current DB?"),
        part(formatDatabaseContext(context)),
      ])
    ).toBe("What's the current DB?");
  });

  it('removes joined context blocks while retaining prompt text on either side', () => {
    const text = `Compare these.\n\n${formatDatabaseContext(context)}\n\nExplain the result.\n\n${formatDatabaseContext(context)}`;
    expect(getUserMessageHistoryText([part(text)])).toBe('Compare these.\n\nExplain the result.');
  });

  it('handles old grid snapshots and longer fences needed by SQL containing backticks', () => {
    const grid: DatabaseContext = {
      ...context,
      scope: 'table',
      origin: 'grid',
      ddl: 'select ```literal```;',
    };
    const formatted = formatDatabaseContext(grid);
    expect(formatted).toContain('````json');
    expect(
      getUserMessageHistoryText([
        part(`Fix this query.\r\n\r\n${formatted.replaceAll('\n', '\r\n')}`),
      ])
    ).toBe('Fix this query.');
    expect(getUserMessageHistoryText([part(formatted)])).toBeNull();
  });

  it('preserves literal examples inside user code fences and malformed context', () => {
    for (const text of [
      `Here is a log:\n\n\`\`\`\`text\n${formatDatabaseContext(context)}\n\`\`\`\``,
      `~~~text\n${formatDatabaseContext(context)}\n~~~`,
      '[Database context]\n```json\n{"example":true}\n```',
      '[Database context]\n```json\nnot JSON\n```',
      '[Database context]\n```json\n{}',
      'What does [Database context] mean?',
    ])
      expect(getUserMessageHistoryText([part(text)])).toBe(text);
  });
});
