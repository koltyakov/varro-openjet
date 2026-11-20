import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  resolve: {
    conditions: ['browser'],
    // Node otherwise selects Solid's inert server entry for external packages.
    // Host contracts also exercise the browser's reactive stores without a DOM.
    alias: [{
      find: /^solid-js$/,
      replacement: fileURLToPath(new URL('./node_modules/solid-js/dist/solid.dev.js', import.meta.url)),
    }],
  },
  test: { include: ['src/*.test.ts'], environment: 'node' },
});
