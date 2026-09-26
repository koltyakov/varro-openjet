import { isDatabaseContext } from '../vendor/shared/database-context';

/** Backends may return automatic context as its own text part or joined to the prompt. */
export function stripDatabaseContextForHistory(text: string): string {
  if (!text.includes('[Database context]')) return text;
  const lines = text.split(/\r?\n/);
  const visible: string[] = [];
  let fence: string | null = null;
  let removed = false;

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]!;
    const trimmed = line.trim();
    if (!fence && trimmed === '[Database context]') {
      const opening = lines[index + 1]?.trim().match(/^(`{3,})json$/);
      if (opening) {
        let end = index + 2;
        while (end < lines.length && lines[end]!.trim() !== opening[1]) end += 1;
        if (end < lines.length) {
          try {
            if (isDatabaseContext(JSON.parse(lines.slice(index + 2, end).join('\n')))) {
              removed = true;
              index = end;
              // Reuse the separator before the attachment rather than leaving a blank gap.
              while (index + 1 < lines.length && lines[index + 1]!.trim() === '') index += 1;
              continue;
            }
          } catch {
            // A literal or malformed example is user text, not a hidden attachment.
          }
        }
      }
    }

    const marker = trimmed.match(/^(`{3,}|~{3,})(.*)$/);
    if (marker) {
      if (!fence) fence = marker[1]!;
      else if (marker[1]![0] === fence[0] && marker[1]!.length >= fence.length && !marker[2]!.trim()) fence = null;
    }
    visible.push(line);
  }

  return removed ? visible.join('\n') : text;
}
