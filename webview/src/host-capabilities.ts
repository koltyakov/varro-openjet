/** Host capabilities are fixed for the lifetime of a webview. */
export function supportsDetachedEditors(): boolean {
  const host = window as Window & {
    __initialWebviewState?: { supportsDetachedEditors?: boolean };
  };
  return host.__initialWebviewState?.supportsDetachedEditors === true;
}
