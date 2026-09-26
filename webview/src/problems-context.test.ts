import { describe, expect, it } from 'vitest';
import { registerHostExtension } from '../vendor/webview/host/extensions';
import { parseExtensionMessage } from '../vendor/shared/extension-message';
import {
  formatEditorProblems,
  getProblemsSeverity,
  parseIssueAttachment,
} from '../vendor/webview/lib/editor-problems';
import { getProblemCompletionItems } from '../vendor/webview/components/chat-input/completion';
import {
  cloneInlineProblems,
  deduplicateInlineProblems,
  formatInlineProblem,
  parseInlineProblem,
  problemReferenceMarker,
} from '../vendor/webview/lib/editor-problems';

const diagnostic = {
  path: '/project/app.kt',
  severity: 'error' as const,
  message: 'Unresolved reference\nDetails',
  line: 2,
  column: 4,
  endLine: 2,
  endColumn: 8,
  code: 'Kotlin',
  intersectsSelection: true,
};

describe('JetBrains problems context', () => {
  it('accepts the settings opt out in live configuration updates', () => {
    const message = {
      type: 'config/update',
      payload: {
        enableProblemsContext: false,
        desktopSessionPaneSide: 'right',
        defaultPermissionMode: 'auto',
        chatFontSize: 13,
        chatEditorFontSize: 12,
        chatFontFamily: 'default',
      },
    };
    expect(parseExtensionMessage(message)).toEqual(message);
  });

  it('round-trips grouped problem chips and preserves detached snapshots', () => {
    const reference = {
      id: 'jetbrains-1',
      diagnostic: { ...diagnostic },
      group: [{ ...diagnostic }],
    };
    const copy = cloneInlineProblems([reference])[0]!;
    reference.group[0]!.message = 'Changed after capture';
    expect(copy.group?.[0]?.message).toBe(diagnostic.message);
    expect(parseInlineProblem(formatInlineProblem(copy))).toEqual(copy);
    const text = `Fix ${problemReferenceMarker(copy)}`;
    expect(deduplicateInlineProblems(text, [copy], [diagnostic])).toEqual({
      text: 'Fix ',
      references: [],
    });
  });

  it('accepts native snapshots and formats selected details with JetBrains branding', () => {
    const payload = {
      workspacePath: '/project',
      activeFile: { path: diagnostic.path, relativePath: 'app.kt', language: 'kotlin' },
      selection: null,
      diagnostics: [diagnostic],
      diagnosticsTotal: 30,
      diagnosticCounts: { errors: 29, warnings: 1 },
    };
    expect(parseExtensionMessage({ type: 'context/update', payload })).not.toBeNull();
    const dispose = registerHostExtension({
      apiVersion: 1,
      id: 'openjet.test',
      metadata: {
        name: 'OpenJet',
        version: 'test',
        repository: 'https://github.com/koltyakov/varro-openjet',
        ideName: 'JetBrains',
      },
    });
    let text: string;
    try {
      text = formatEditorProblems(payload)!;
    } finally {
      dispose();
    }
    expect(text).toContain('[JetBrains problems for app.kt: 29 errors, 1 warnings]');
    expect(text).toContain('Unresolved reference\nDetails');
    expect(text).toContain('29 additional problems omitted.');
    expect(parseIssueAttachment(text)?.count).toBe(30);
    expect(getProblemsSeverity(text)).toBe('error');
    expect(parseIssueAttachment(text.replace('JetBrains', 'VS Code'))?.count).toBe(30);
  });

  it('accepts captured Problems actions and rejects invalid coordinates', () => {
    const message = { type: 'command/attach-problems', payload: { diagnostics: [diagnostic] } };
    expect(parseExtensionMessage(message)).toEqual(message);
    expect(
      parseExtensionMessage({
        ...message,
        payload: { diagnostics: [{ ...diagnostic, column: 0 }] },
      })
    ).toBeNull();
  });

  it('searches native workspace problems', () => {
    const items = getProblemCompletionItems(
      { diagnostics: [diagnostic], total: 1 },
      'unresolved',
      '/project'
    );
    expect(
      items.some((item) => item.type === 'problems' && item.diagnostic?.path === diagnostic.path)
    ).toBe(true);
  });
});
