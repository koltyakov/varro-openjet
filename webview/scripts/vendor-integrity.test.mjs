import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { vendorHash } from './vendor-integrity.mjs';

test('vendor hash includes directory contents and individual asset files', () => {
  const root = mkdtempSync(join(tmpdir(), 'varro-vendor-integrity-'));
  try {
    mkdirSync(join(root, 'vendor/webview/nested'), { recursive: true });
    mkdirSync(join(root, 'assets'), { recursive: true });
    writeFileSync(join(root, 'vendor/webview/nested/component.ts'), 'component');
    writeFileSync(join(root, 'vendor/webview/app.ts'), 'app');
    writeFileSync(join(root, 'assets/icon.png'), 'icon');
    const entries = [
      { to: 'vendor/webview' },
      { to: 'assets/icon.png' },
    ];
    const expected = createHash('sha256')
      .update('vendor/webview/app.ts\0app\0')
      .update('vendor/webview/nested/component.ts\0component\0')
      .update('assets/icon.png\0icon\0')
      .digest('hex');
    assert.equal(vendorHash(root, entries), expected);
    writeFileSync(join(root, 'assets/icon.png'), 'changed icon');
    assert.notEqual(vendorHash(root, entries), expected);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
