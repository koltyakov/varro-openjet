import tailwindcss from '@tailwindcss/vite';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import solid from 'vite-plugin-solid';
import { defineConfig, type Plugin } from 'vite';

const projectRoot = dirname(fileURLToPath(import.meta.url));
const gradleProperties = readFileSync(resolve(projectRoot, '../gradle.properties'), 'utf8');
const pluginVersion = gradleProperties.match(/^pluginVersion\s*=\s*(\S+)\s*$/m)?.[1];
if (!pluginVersion) throw new Error('Missing pluginVersion in gradle.properties');

/**
 * Mirrors upstream Varro's asset-version plugin. The Kotlin host appends this
 * hash as a `?v=` cache key so JCEF's disk cache cannot serve a stale bundle
 * after a plugin update - JCEF caches far more aggressively than the VS Code
 * webview loader does.
 */
const assetVersionPlugin: Plugin = {
  name: 'varro-webview-asset-version',
  generateBundle(_options, bundle) {
    const hash = createHash('sha256');
    for (const fileName of Object.keys(bundle).toSorted()) {
      const output = bundle[fileName]!;
      hash.update(fileName);
      hash.update(output.type === 'chunk' ? output.code : output.source);
    }
    this.emitFile({
      type: 'asset',
      fileName: 'webview.version',
      source: hash.digest('hex').slice(0, 16),
    });
  },
};

export default defineConfig({
  base: './',
  define: { __VARRO_PLUGIN_VERSION__: JSON.stringify(pluginVersion) },
  plugins: [solid(), tailwindcss(), assetVersionPlugin],
  build: {
    // Emitted straight into the plugin's resources; `processResources` then
    // packages it like any other static asset.
    outDir: resolve(projectRoot, '../src/main/resources/webview'),
    emptyOutDir: true,
    rollupOptions: {
      input: resolve(projectRoot, 'src/host-bridge.ts'),
      output: {
        entryFileNames: 'webview.mjs',
        chunkFileNames: 'chunks/[name]-[hash].js',
        // The stylesheet keeps a stable name so the Kotlin page shell can
        // reference it without scanning the output directory for a hash;
        // `webview.version` already busts the cache on every rebuild.
        assetFileNames: (asset) =>
          asset.names?.some((name) => name.endsWith('.css'))
            ? 'webview.css'
            : 'assets/[name]-[hash][extname]',
      },
    },
    // Upstream's icon imports resolve to URLs. Inlining the small ones as data:
    // URIs keeps the number of round trips through the JCEF resource handler
    // low; the handler still serves whatever stays on disk.
    assetsInlineLimit: 8192,
    // Vite 8 is rolldown-based and no longer bundles esbuild; `oxc` is the
    // in-tree minifier and the one upstream Varro builds with.
    minify: 'oxc',
    sourcemap: false,
    target: 'es2022',
    chunkSizeWarningLimit: 4096,
  },
});
