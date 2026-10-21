import { expect, it } from 'vitest';
import { staticImports } from '../scripts/static-imports.mjs';

it('preloads only reachable static chunks, without cycles or lazy modules', () => {
  const bundle = {
    'webview.mjs': { type: 'chunk', imports: ['chunks/a-hash.js'] },
    'chunks/a-hash.js': { type: 'chunk', imports: ['chunks/b-hash.js'] },
    'chunks/b-hash.js': { type: 'chunk', imports: ['chunks/a-hash.js', 'external'] },
    'chunks/mermaid-hash.js': { type: 'chunk', imports: [] },
    'webview.css': { type: 'asset' },
  };
  expect(staticImports(bundle)).toEqual(['chunks/a-hash.js', 'chunks/b-hash.js']);
});
