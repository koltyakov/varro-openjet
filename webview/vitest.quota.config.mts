import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['vendor/extension/**/*.test.ts', 'quota/**/*.test.ts'],
    restoreMocks: true,
  },
});
