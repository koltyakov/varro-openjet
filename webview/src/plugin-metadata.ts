import packageJson from '../package.json';

// Vite supplies the version from gradle.properties at build time.
declare const __VARRO_PLUGIN_VERSION__: string;

export default {
  repository: packageJson.repository,
  version: __VARRO_PLUGIN_VERSION__,
};
