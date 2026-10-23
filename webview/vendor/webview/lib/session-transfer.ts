import { isSameWorkspacePath } from '../../shared/workspace-path';
import type { Session } from '../types';

export function isSessionTransferHidden(session: Session, directory?: string | null): boolean {
  const transfer = session.transfer;
  if (!transfer || isSameWorkspacePath(session.directory, directory)) return false;
  return Boolean(
    transfer.hidden ||
    transfer.hiddenOrigins?.some((origin) => isSameWorkspacePath(origin, directory))
  );
}
