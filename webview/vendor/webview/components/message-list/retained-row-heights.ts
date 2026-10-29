import type { VirtualMetrics } from './virtualization';

type Snapshot = {
  itemIds: readonly string[];
  metrics: VirtualMetrics;
};

// Retain numbers and IDs, never rows, message bodies, or reactive owners. A reopen still
// measures its mounted content; these estimates only avoid bootstrapping the whole page again.
export class RetainedRowHeights {
  private readonly sessions = new Map<string, Snapshot>();
  private rowCount = 0;

  constructor(
    private readonly maxRows = 2000,
    private readonly maxSessions = 4
  ) {}

  remember(sessionId: string, itemIds: readonly string[], metrics: VirtualMetrics) {
    const previous = this.sessions.get(sessionId);
    if (previous) {
      this.rowCount -= previous.itemIds.length;
      this.sessions.delete(sessionId);
    }
    if (!itemIds.length || itemIds.length > this.maxRows || metrics.itemCount !== itemIds.length)
      return;
    this.sessions.set(sessionId, { itemIds, metrics });
    this.rowCount += itemIds.length;
    while (this.rowCount > this.maxRows || this.sessions.size > this.maxSessions) {
      const oldest = this.sessions.entries().next().value;
      if (!oldest) break;
      this.rowCount -= oldest[1].itemIds.length;
      this.sessions.delete(oldest[0]);
    }
  }

  restore(sessionId: string | null, itemIds: readonly string[]): Map<string, number> | null {
    if (!sessionId) return null;
    const snapshot = this.sessions.get(sessionId);
    if (
      !snapshot ||
      snapshot.itemIds.length !== itemIds.length ||
      snapshot.itemIds.some((id, index) => id !== itemIds[index])
    )
      return null;
    this.sessions.delete(sessionId);
    this.sessions.set(sessionId, snapshot);
    const heights = new Map<string, number>();
    for (let index = 0; index < itemIds.length; index += 1) {
      const height = snapshot.metrics.prefix.at(index + 1)! - snapshot.metrics.prefix.at(index)!;
      // Only the current projection can certify a zero. A formerly empty row may now have content.
      if (height > 0) heights.set(itemIds[index]!, height);
    }
    return heights;
  }
}
