import { expect, it } from 'vitest';
import { stripContextForHistory } from '../vendor/webview/lib/context-history';

it('recalls prompt text without the JetBrains active-file context', () => {
  const text = 'Sync with latest Varro\n\n[Active file: src/main/resources/META-INF/plugin.xml]';
  expect(stripContextForHistory(text).trim()).toBe('Sync with latest Varro');
});

it('removes file and directory context without discarding prompt lines', () => {
  const text = '[Working directory: /project]\nReview this\n[Selection from src/app.ts lines 3-5, 9]\n[Attached file: README.md]\nKeep this instruction';
  expect(stripContextForHistory(text)).toBe('Review this\nKeep this instruction');
});

it.each(['```', '~~~~'])('preserves context examples inside %s fences', (fence) => {
  const text = `${fence}text\n[Active file: app.ts]\n[Selection from app.ts lines 3-5]\n[Attached file: README.md]\n[Working directory: /project]\n${fence}`;
  expect(stripContextForHistory(text)).toBe(text);
});

it('preserves malformed references and inline mentions', () => {
  const text = '[Active file: ]\n[Selection from app.ts lines invalid]\nExplain [Active file: app.ts]\nReview @src/app.ts';
  expect(stripContextForHistory(text)).toBe(text);
});
