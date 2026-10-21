/** Static imports only. Lazy diagram/highlighter chunks must stay lazy. */
export function staticImports(bundle, entryName = 'webview.mjs') {
  const seen = new Set();
  function visit(name) {
    const chunk = bundle[name];
    if (chunk?.type !== 'chunk') return;
    for (const dependency of chunk.imports) {
      if (dependency === entryName || seen.has(dependency) || !bundle[dependency]) continue;
      seen.add(dependency);
      visit(dependency);
    }
  }
  visit(entryName);
  return [...seen].sort();
}
