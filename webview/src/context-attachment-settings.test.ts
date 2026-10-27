import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DroppedFile, EditorContext } from '../vendor/shared/protocol';
import { subtractContextLineRanges } from '../vendor/shared/context-files';
import {
  buildSessionSendBody,
  SessionSendOperations,
} from '../vendor/webview/hooks/session/session-send';
import { appStore } from '../vendor/webview/lib/stores/app-store';
import { readStoredQueuedMessages } from '../vendor/webview/lib/state-stored-values';

vi.hoisted(() => {
  vi.stubGlobal('window', { addEventListener() {}, removeEventListener() {} });
  vi.stubGlobal('document', { addEventListener() {}, removeEventListener() {} });
});

const activeFile = { path: '/project/app.kt', relativePath: 'app.kt', language: 'kotlin' };
const editorContext: EditorContext = {
  workspacePath: '/project',
  activeFile,
  selection: { startLine: 2, endLine: 3 },
  diagnostics: [{ path: activeFile.path, severity: 'error', message: 'Private diagnostic', line: 2 }],
};

function composer(context: EditorContext) {
  return {
    selectedAgent: null,
    selectedModel: null,
    providers: [],
    providerDefaults: {},
    modelVariantSelections: {},
    terminalSelection: null,
    droppedFiles: [] as DroppedFile[],
    clipboardImages: [],
    editorContext: context,
    autoAttachProblems: false,
    autoAttachActiveFile: false,
  };
}

afterEach(() => {
  appStore.setState('autoAttachProblems', true);
  appStore.setState('autoAttachActiveFile', true);
  appStore.setState('activeSessionId', null);
});

describe('context attachment settings', () => {
  it('omits saved current selections already covered by pinned ranges', () => {
    const state = composer({
      ...editorContext,
      selection: { startLine: 121, endLine: 122 },
      editorText: null,
    });
    state.autoAttachActiveFile = true;
    const pinnedRanges = [
      { startLine: 111, endLine: 112 },
      { startLine: 121, endLine: 122 },
    ];
    state.droppedFiles = [{ ...activeFile, type: 'file', lineRanges: pinnedRanges }];
    expect(subtractContextLineRanges([state.editorContext.selection!], pinnedRanges)).toEqual([]);
    const send = () => buildSessionSendBody(state, 'ses-test', 'Review', () => true)?.body.parts;
    expect(send()).toEqual([
      { type: 'text', text: 'Review' },
      { type: 'text', text: '[Selection from app.kt lines 111-112, 121-122]' },
    ]);

    state.editorContext.selection = { startLine: 125, endLine: 126 };
    expect(send()).toEqual([
      { type: 'text', text: 'Review' },
      { type: 'text', text: '[Selection from app.kt lines 125-126]' },
      { type: 'text', text: '[Selection from app.kt lines 111-112, 121-122]' },
    ]);
  });

  it.each([false, true])(
    'omits automatic context and keeps manual files, unsaved=%s',
    (unsaved) => {
      const state = composer({
        ...editorContext,
        editorText: unsaved
          ? {
              ...activeFile,
              kind: 'selection',
              range: { startLine: 2, endLine: 3 },
              text: 'Private unsaved selection',
              truncated: false,
            }
          : null,
      });
      const send = () => buildSessionSendBody(state, 'ses-test', 'Review', () => true)?.body.parts;
      expect(send()).toEqual([{ type: 'text', text: 'Review' }]);
      state.droppedFiles = [{ ...activeFile, type: 'file' }];
      expect(send()).toEqual([
        { type: 'text', text: 'Review' },
        { type: 'text', text: '[Attached file: app.kt]' },
      ]);
      state.autoAttachActiveFile = true;
      expect(JSON.stringify(send())).toContain(
        unsaved ? 'Private unsaved selection' : 'Selection from'
      );
      expect(JSON.stringify(send())).not.toContain('Private diagnostic');
    }
  );

  it('controls diagnostics independently of automatic file context', () => {
    const state = composer(editorContext);
    state.autoAttachProblems = true;
    const sent = JSON.stringify(buildSessionSendBody(state, 'ses-test', 'Review', () => true));
    expect(sent).toContain('Private diagnostic');
    expect(sent).not.toContain('[Active file:');
    expect(sent).not.toContain('[Selection from');
  });

  it.each([false, true])(
    'honors the current opt-out for restored queued files, legacy=%s',
    async (legacy) => {
      appStore.setState('activeSessionId', 'ses-test');
      appStore.setState('autoAttachActiveFile', false);
      appStore.setState('autoAttachProblems', false);
      const [queued] = readStoredQueuedMessages([
        {
          id: 'q1',
          sessionId: 'ses-test',
          text: 'Review',
          droppedFiles: [
            { ...activeFile, type: 'file', lineRanges: [{ startLine: 2, endLine: 3 }] },
            {
              path: '/project/manual.kt',
              relativePath: 'manual.kt',
              type: 'file',
              attachmentSequence: 1,
            },
          ],
          queuedContext: {
            editorContext,
            currentDocumentEnabled: true,
            ...(legacy ? {} : { autoAttachedFilePath: activeFile.path }),
          },
        },
      ]);
      expect(queued?.queuedContext?.autoAttachedFilePath).toBe(legacy ? undefined : activeFile.path);
      const sendAsync = vi.fn(async () => {});
      const operations = new SessionSendOperations({
        createSession: async () => 'ses-test',
        clearPendingAbort() {},
        resetTodoSync() {},
        syncSessionMcps: async () => {},
        sendAsync,
        syncSession: async () => {},
        syncSessionMessages: async () => {},
        recheckSessionStatus: async () => {},
        setSessionStatusEntry() {},
        getMessageCount: () => 1,
        continueInterruptedSession: async () => {},
      });
      expect(
        await operations.prepareSendMessage(queued!.text, {
          targetSessionId: queued!.sessionId,
          queuedAttachments: { droppedFiles: queued!.droppedFiles },
          queuedContext: queued!.queuedContext,
        })()
      ).toBe(true);
      expect(sendAsync).toHaveBeenCalledWith(
        'ses-test',
        expect.objectContaining({
          parts: [
            { type: 'text', text: 'Review' },
            { type: 'text', text: '[Attached file: manual.kt]' },
          ],
        })
      );
    }
  );
});
