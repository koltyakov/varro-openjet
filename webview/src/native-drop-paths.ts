/**
 * Pairs dropped `File`s with the native paths JCEF reported for the same drop.
 * Chromium hides `File.path`, so only the name can be matched. Each path is
 * consumed once: two `index.ts` files from different folders must map to their
 * own paths instead of both resolving to the first.
 */
export function matchNativeDropPaths(
  files: readonly { name: string }[],
  paths: readonly string[]
): (string | undefined)[] {
  const remaining = [...paths];
  return files.map((file) => {
    const index = remaining.findIndex(
      (path) => path.replace(/\\/g, '/').split('/').pop() === file.name
    );
    return index < 0 ? undefined : remaining.splice(index, 1)[0];
  });
}
