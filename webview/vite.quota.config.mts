import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

const root = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  build: {
    ssr: resolve(root, 'quota/main.ts'),
    outDir: resolve(root, '../src/main/resources/quota'),
    emptyOutDir: true,
    target: 'node22',
    minify: false,
    rollupOptions: { output: { entryFileNames: 'provider-quota.mjs' } },
  },
});
