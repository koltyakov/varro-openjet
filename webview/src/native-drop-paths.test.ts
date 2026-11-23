import { describe, expect, it } from 'vitest';
import { matchNativeDropPaths } from './native-drop-paths';

describe('native drop paths', () => {
  it('maps same-named files to distinct paths in drop order', () => {
    expect(
      matchNativeDropPaths(
        [{ name: 'index.ts' }, { name: 'index.ts' }, { name: 'README.md' }],
        ['/repo/a/index.ts', 'C:\\repo\\b\\index.ts', '/repo/README.md']
      )
    ).toEqual(['/repo/a/index.ts', 'C:\\repo\\b\\index.ts', '/repo/README.md']);
  });

  it('leaves files without a native path unmatched', () => {
    expect(matchNativeDropPaths([{ name: 'a.ts' }, { name: 'a.ts' }], ['/x/a.ts'])).toEqual([
      '/x/a.ts',
      undefined,
    ]);
  });
});
