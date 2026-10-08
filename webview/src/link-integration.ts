import { isSafeExternalHref } from '../vendor/webview/lib/external-link';

type Send = (message: unknown) => void;
type LinkContext =
  | { webviewSection: 'varroExternalLink'; varroLinkUrl: string }
  | { webviewSection: 'varroFileLink'; varroFilePath: string };

/** Let the shared UI handle its links first, then catch otherwise unhandled anchors. */
export function installLinkIntegration(
  document: Pick<Document, 'addEventListener'>,
  window: Pick<Window, 'addEventListener'>,
  send: Send
) {
  // Solid delegates clicks to document. Window runs after those handlers regardless
  // of registration order, avoiding a second open from the shared UI.
  window.addEventListener('click', (event) => {
    if (event.defaultPrevented || event.button !== 0) return;
    const anchor = (event.target as Element | null)?.closest?.('a[href]');
    const href = anchor?.getAttribute('href');
    if (!href || href.startsWith('#')) return;
    event.preventDefault();
    if (isSafeExternalHref(href)) {
      send({ type: 'vscode/open-external', payload: { url: href } });
    }
  });

  // VS Code consumes these attributes natively. JCEF needs an explicit host popup.
  document.addEventListener(
    'contextmenu',
    (event) => {
      if (event.defaultPrevented) return;
      const target = (event.target as Element | null)?.closest?.('[data-vscode-context]');
      const context = readLinkContext(target?.getAttribute('data-vscode-context'));
      if (!context) return;
      event.preventDefault();
      event.stopPropagation();
      send({
        type: 'host/link-context-menu',
        payload: { ...context, x: event.clientX, y: event.clientY },
      });
    },
    true
  );
}

export function readLinkContext(raw: string | null | undefined): LinkContext | null {
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return null;
    const context = parsed as Record<string, unknown>;
    if (
      context?.webviewSection === 'varroExternalLink' &&
      typeof context.varroLinkUrl === 'string' &&
      isSafeExternalHref(context.varroLinkUrl)
    ) {
      return { webviewSection: context.webviewSection, varroLinkUrl: context.varroLinkUrl };
    }
    if (
      context?.webviewSection === 'varroFileLink' &&
      typeof context.varroFilePath === 'string' &&
      context.varroFilePath.trim()
    ) {
      return { webviewSection: context.webviewSection, varroFilePath: context.varroFilePath };
    }
  } catch {
    // Unknown or malformed context leaves the normal browser menu alone.
  }
  return null;
}
