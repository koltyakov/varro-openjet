/**
 * JetBrains host bridge for the vendored Varro webview.
 *
 * Upstream Varro talks to its VS Code host through exactly four `window`
 * globals, installed by an inline bootstrap in `src/extension/webview-html.ts`:
 *
 *   - `window.__initialWebviewState`  boot snapshot (serialized by the host)
 *   - `window.__initialTheme`         theme kind, mirrored from the snapshot
 *   - `window.__sendToExtension(msg)` webview -> host channel
 *   - `window.__vscodeWebviewState`   synchronous per-view key/value store
 *
 * Host -> webview traffic arrives as ordinary `window.postMessage` events, which
 * `src/webview/lib/bridge.ts` listens for.
 *
 * This module reimplements that contract on top of JCEF. The Kotlin host
 * generates the page shell (see `WebviewHtml.kt`), inlines the boot snapshot the
 * same way VS Code does, and installs one raw primitive for us:
 *
 *   `window.__varroHostSend(json)` - a `JBCefJSQuery` injection that hands a
 *   string to the Kotlin side.
 *
 * Everything above that primitive is implemented here so the vendored webview
 * stays byte-identical to upstream.
 */

type HostWindow = Window & {
  __varroHostSend?: (json: string) => void;
  __varroInitialViewState?: Record<string, unknown>;
  __initialWebviewState?: { theme?: unknown };
  __initialTheme?: unknown;
  __sendToExtension?: (message: unknown) => void;
  __vscodeWebviewState?: {
    getState(): Record<string, unknown>;
    setState(state: Record<string, unknown>): void;
  };
  __varroReceive?: (payload: unknown) => void;
};

const hostWindow = window as HostWindow;

/**
 * The host sends JSON strings, one message per call. Serializing here rather
 * than in Kotlin keeps structured-clone-style failures (functions, cycles,
 * DOM nodes) on the webview side, where upstream's `sendToExtension` already
 * reports them as transport errors instead of losing the message silently.
 */
function installSendChannel() {
  hostWindow.__sendToExtension = (message: unknown) => {
    const send = hostWindow.__varroHostSend;
    if (!send) throw new Error('Varro JetBrains host channel is unavailable');
    send(JSON.stringify(message));
  };
}

/**
 * `vscode.getState()/setState()` are synchronous, but every JCEF -> JVM call is
 * asynchronous. The host therefore inlines the last persisted snapshot into the
 * page, we serve reads from that snapshot, and writes are mirrored back to the
 * host so they survive an IDE restart. Upstream only uses this store as the
 * fallback behind `localStorage` (see `lib/browser-persistence.ts`), so a
 * one-frame write delay is not observable.
 */
function installViewStateChannel() {
  let state: Record<string, unknown> = { ...(hostWindow.__varroInitialViewState ?? {}) };

  hostWindow.__vscodeWebviewState = {
    getState() {
      return state;
    },
    setState(next) {
      state = next && typeof next === 'object' ? next : {};
      try {
        hostWindow.__sendToExtension?.({
          type: 'host/view-state',
          payload: { state },
        });
      } catch {
        // A rejected mirror only costs cross-restart durability; the in-memory
        // snapshot above still satisfies this session's reads.
      }
    },
  };
}

/**
 * Host -> webview delivery. `executeJavaScript` cannot post a `MessageEvent`
 * directly, so the host calls this shim and we re-dispatch it as the `message`
 * event upstream's bridge is already listening for.
 */
function installReceiveChannel() {
  hostWindow.__varroReceive = (payload: unknown) => {
    window.dispatchEvent(new MessageEvent('message', { data: payload }));
  };
}

/**
 * VS Code webviews never navigate, and neither should this one: a stray link or
 * form submit would replace the Solid app with a blank document that only an IDE
 * restart recovers from. Route those intents to the host instead.
 */
function guardNavigation() {
  document.addEventListener(
    'click',
    (event) => {
      if (event.defaultPrevented || event.button !== 0) return;
      const anchor = (event.target as Element | null)?.closest?.('a[href]');
      if (!anchor) return;
      const href = anchor.getAttribute('href') ?? '';
      if (!href || href.startsWith('#')) return;
      event.preventDefault();
      if (/^https?:/i.test(href)) {
        hostWindow.__sendToExtension?.({
          type: 'vscode/open-external',
          payload: { url: href },
        });
      }
    },
    true,
  );

  window.addEventListener('dragover', (event) => event.preventDefault());
  document.addEventListener('submit', (event) => event.preventDefault(), true);
}

/**
 * JCEF does not consistently start Chromium's native drag session for small
 * HTML drag handles. Drive the same drag events from pointer input so upstream
 * reorder controls keep their existing DataTransfer-based implementation.
 */
function installInternalDragBridge() {
  const DRAG_THRESHOLD = 5;
  let source: HTMLElement | null = null;
  let hovered: Element | null = null;
  let transfer: DataTransfer | null = null;
  let pointerID = -1;
  let originX = 0;
  let originY = 0;
  let dragging = false;
  let suppressClick = false;

  function dispatch(
    target: EventTarget,
    type: string,
    event: PointerEvent,
    relatedTarget: EventTarget | null = null,
  ) {
    return target.dispatchEvent(
      new DragEvent(type, {
        bubbles: true,
        cancelable: true,
        clientX: event.clientX,
        clientY: event.clientY,
        dataTransfer: transfer,
        relatedTarget,
      }),
    );
  }

  function restoreSource() {
    if (source) source.draggable = true;
  }

  function clear() {
    restoreSource();
    source = null;
    hovered = null;
    transfer = null;
    pointerID = -1;
    dragging = false;
  }

  document.addEventListener(
    'pointerdown',
    (event) => {
      if (event.button !== 0 || !event.isPrimary) return;
      const target = (event.target as Element | null)?.closest<HTMLElement>('[draggable="true"]');
      if (!target) return;

      source = target;
      pointerID = event.pointerId;
      originX = event.clientX;
      originY = event.clientY;
      // Prevent JCEF from starting a late native session alongside the bridge.
      source.draggable = false;
    },
    true,
  );

  window.addEventListener(
    'pointermove',
    (event) => {
      if (!source || event.pointerId !== pointerID) return;
      if (!dragging) {
        if (Math.hypot(event.clientX - originX, event.clientY - originY) < DRAG_THRESHOLD) return;
        transfer = new DataTransfer();
        dragging = dispatch(source, 'dragstart', event);
        if (!dragging) {
          clear();
          return;
        }
        suppressClick = true;
      }

      event.preventDefault();
      const next = document.elementFromPoint(event.clientX, event.clientY);
      if (next !== hovered) {
        if (hovered) dispatch(hovered, 'dragleave', event, next);
        if (next) dispatch(next, 'dragenter', event, hovered);
        hovered = next;
      }
      if (hovered) dispatch(hovered, 'dragover', event);
    },
    true,
  );

  window.addEventListener(
    'pointerup',
    (event) => {
      if (!source || event.pointerId !== pointerID) return;
      if (dragging && hovered) {
        const accepted = !dispatch(hovered, 'dragover', event);
        if (accepted) dispatch(hovered, 'drop', event);
        dispatch(source, 'dragend', event);
      }
      clear();
      window.setTimeout(() => {
        suppressClick = false;
      }, 0);
    },
    true,
  );

  window.addEventListener(
    'pointercancel',
    (event) => {
      if (!source || event.pointerId !== pointerID) return;
      if (dragging) dispatch(source, 'dragend', event);
      suppressClick = false;
      clear();
    },
    true,
  );

  document.addEventListener(
    'click',
    (event) => {
      if (!suppressClick) return;
      suppressClick = false;
      event.preventDefault();
      event.stopImmediatePropagation();
    },
    true,
  );
}

/**
 * JCEF delivers IDE-level shortcuts to the browser component, not to the IDE, so
 * the webview has to hand back the ones the host owns. `Shift+Escape` hides the
 * tool window; the rest ride the normal `commands/state` channel.
 */
function forwardHostShortcuts() {
  window.addEventListener(
    'keydown',
    (event) => {
      if (event.key === 'Escape' && event.shiftKey) {
        event.preventDefault();
        hostWindow.__sendToExtension?.({ type: 'host/hide-panel' });
      }
    },
    true,
  );
}

installSendChannel();
installViewStateChannel();
installReceiveChannel();
guardNavigation();
installInternalDragBridge();
forwardHostShortcuts();

hostWindow.__initialTheme = hostWindow.__initialWebviewState?.theme;

// The vendored entry point mounts the app and sends `ready`, which makes the
// host replay context, config, status and recovery state. Importing it last
// guarantees every global above is in place before that handshake starts.
await import('../vendor/webview/index');
