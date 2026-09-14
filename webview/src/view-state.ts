export interface ViewStateHost {
  __varroInitialViewState?: Record<string, unknown>;
  __sendToExtension?: (message: unknown) => void;
  __vscodeWebviewState?: {
    getState(): Record<string, unknown>;
    setState(state: Record<string, unknown>): void;
  };
}

/** Keep synchronous reads local and send one snapshot per burst of writes. */
export function installViewStateChannel(host: ViewStateHost) {
  let state = { ...(host.__varroInitialViewState ?? {}) };
  let scheduled = false;

  host.__vscodeWebviewState = {
    getState() { return state; },
    setState(next) {
      next = next && typeof next === 'object' ? next : {};
      const keys = Object.keys(next);
      const unchanged = keys.length === Object.keys(state).length &&
        keys.every((key) => Object.hasOwn(state, key) && Object.is(next[key], state[key]));
      state = next;
      if (unchanged || scheduled) return;
      scheduled = true;
      // A microtask also covers lifecycle-triggered draft writes without waiting
      // for another frame or a timer, which may not run when the view closes.
      queueMicrotask(() => {
        scheduled = false;
        try {
          host.__sendToExtension?.({ type: 'host/view-state', payload: { state } });
        } catch {
          // Preserve this session's reads if the host is no longer available.
        }
      });
    },
  };
}
