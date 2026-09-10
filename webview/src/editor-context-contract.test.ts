import { expect, it } from 'vitest';
import { parseExtensionMessage } from '../vendor/shared/extension-message';

const file = (name: string) => ({ path: `/project/${name}`, relativePath: name, language: 'text' });
const selection = { startLine: 9, endLine: 14 };
const selectedText = (name: string) => ({
  ...file(name), kind: 'selection', range: selection, text: 'selected text', truncated: false,
});

it('accepts file navigation, selection changes, and clearing editor context', () => {
  const snapshots = [
    { activeFile: file('.gitattributes'), selection: null, editorText: null },
    { activeFile: file('.gitattributes'), selection, editorText: selectedText('.gitattributes') },
    { activeFile: file('Dockerfile'), selection: null, editorText: null },
    { activeFile: file('Dockerfile'), selection, editorText: selectedText('Dockerfile') },
    { activeFile: file('Dockerfile'), selection: null, editorText: null },
    { activeFile: null, selection: null, editorText: null },
  ];
  for (const snapshot of snapshots) {
    const message = {
      type: 'context/update',
      payload: { workspacePath: '/project', diagnostics: [], ...snapshot },
    };
    expect(parseExtensionMessage(JSON.parse(JSON.stringify(message)))).toEqual(message);
  }
});

it('rejects the former stripped-null update for a file without a selection', () => {
  expect(parseExtensionMessage({
    type: 'context/update',
    payload: { workspacePath: '/project', activeFile: file('Dockerfile'), diagnostics: [] },
  })).toBeNull();
});
