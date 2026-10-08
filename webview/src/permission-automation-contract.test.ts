import { beforeEach, expect, it, vi } from 'vitest';
import type { Permission } from '../vendor/webview/types';
import { respondPermissionWithDependencies } from '../vendor/webview/hooks/session/session-approvals';

const fixture = vi.hoisted(() => ({
  handlers: new Map<string, (event: { properties: Permission }) => void>(),
  addPermission: vi.fn(),
}));
vi.mock('../vendor/webview/lib/client', () => ({
  serverEvents: {
    on: (type: string, handler: (event: { properties: Permission }) => void) => {
      fixture.handlers.set(type, handler);
      return () => fixture.handlers.delete(type);
    },
  },
}));
vi.mock('../vendor/webview/lib/stores/permissions-store', () => ({
  permissionsStore: { addPermission: fixture.addPermission },
}));
vi.mock('../vendor/webview/hooks/session/session-event-utils', () => ({
  getPermissionReplyId: vi.fn(),
  getQuestionReplyId: vi.fn(),
}));

import { registerApprovalEventHandlers } from '../vendor/webview/hooks/session/session-approval-events';

beforeEach(() => {
  fixture.handlers.clear();
  vi.clearAllMocks();
});

it.each(['question', 'todowrite', 'bash'])(
  'keeps child %s permissions actionable in non-owner views',
  (type) => {
    const respond = vi.fn();
    const judge = vi.fn();
    const visible = vi.fn();
    const cleanups = registerApprovalEventHandlers({
      isPermissionAutomationOwner: () => false,
      shouldAutoApprovePermissions: () => true,
      shouldAutoJudgePermissions: () => true,
      respondPermission: respond,
      respondAutomaticPermission: respond,
      judgePermission: judge,
      permissionVisible: visible,
      logError: vi.fn(),
    });
    const permission: Permission = {
      id: 'request',
      sessionID: 'child',
      messageID: 'message',
      type,
      title: type,
      metadata: {},
      time: { created: 0 },
    };
    fixture.handlers.get('permission.asked')!({ properties: permission });
    expect(fixture.addPermission).toHaveBeenCalledWith(permission);
    expect(visible).toHaveBeenCalledWith('request');
    expect(respond).not.toHaveBeenCalled();
    expect(judge).not.toHaveBeenCalled();
    cleanups.forEach((cleanup) => cleanup());
  }
);

it.each([
  [true, 'Permission automation ownership changed', false],
  [false, 'Permission automation ownership changed', true],
  [true, 'Permission backend unavailable', true],
] as const)(
  'preserves pending requests for automatic=%s error=%s',
  async (automatic, message, showError) => {
    const error = new Error(message);
    const removePermission = vi.fn();
    const setError = vi.fn();
    await expect(
      respondPermissionWithDependencies(
        {
          respondPermission: vi.fn().mockRejectedValue(error),
          removePermission,
          setError,
        },
        'child',
        'request',
        'once',
        { automatic, rethrow: true }
      )
    ).rejects.toBe(error);
    expect(removePermission).not.toHaveBeenCalled();
    expect(setError).toHaveBeenCalledTimes(showError ? 1 : 0);
  }
);
