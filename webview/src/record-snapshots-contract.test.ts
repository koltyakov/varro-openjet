import { createRoot, createSignal, createStore, flush, OBSERVE, untrack } from 'solid-js';
import { storePath } from '@solidjs/signals';
import { expect, it } from 'vitest';
import { createRecordSnapshots } from '../vendor/webview/lib/record-snapshots';

it('updates scan snapshots without replacing canonical records or unchanged views', () => {
  const [state, setState] = createStore({
    entries: [
      { id: 'first', parts: [{ text: 'Before' }] },
      { id: 'second', parts: [{ text: 'Stable' }] },
    ],
  });
  const mounted = createRoot((dispose) => ({
    read: createRecordSnapshots(() => state.entries),
    dispose,
  }));
  try {
    const canonical = untrack(() => state.entries[0]);
    const before = untrack(mounted.read);
    setState(storePath('entries', 0, 'parts', 0, 'text', 'After'));
    flush();
    const after = untrack(mounted.read);
    expect(after[0]!.parts[0]!.text).toBe('After');
    expect(before[0]!.parts[0]!.text).toBe('Before');
    expect(after[0]).not.toBe(before[0]);
    expect(after[1]).toBe(before[1]);
    expect(untrack(() => state.entries[0])).toBe(canonical);
  } finally {
    mounted.dispose();
  }
});

it('keeps keyed views through reorder, removal and reinsertion', () => {
  const [first] = createStore({ id: 'first' });
  const [second] = createStore({ id: 'second' });
  const [third] = createStore({ id: 'third' });
  const [entries, setEntries] = createSignal([first, second]);
  const mounted = createRoot((dispose) => ({ read: createRecordSnapshots(entries), dispose }));
  try {
    const before = untrack(mounted.read);
    setEntries([third, second, first]);
    flush();
    expect(untrack(mounted.read)[1]).toBe(before[1]);
    expect(untrack(mounted.read)[2]).toBe(before[0]);
    setEntries([second]);
    flush();
    expect(untrack(mounted.read)).toEqual([before[1]]);
    setEntries([second, third]);
    flush();
    expect(untrack(mounted.read).map((entry) => entry.id)).toEqual(['second', 'third']);
    expect(untrack(mounted.read)[0]).toBe(before[1]);
  } finally {
    mounted.dispose();
  }
});

it('bounds aggregation subscriptions across 3000 records without suppressing diagnostics', () => {
  const [state, setState] = createStore({
    entries: Array.from({ length: 3000 }, (_, id) => ({ id, nested: { value: id } })),
  });
  const capture = OBSERVE!.diagnostics.capture();
  const mounted = createRoot((dispose) => ({
    read: createRecordSnapshots(() => state.entries),
    dispose,
  }));
  try {
    const before = untrack(mounted.read);
    for (let update = 1; update <= 5; update++) {
      setState(storePath('entries', 2999, 'nested', 'value', -update));
      flush();
      const after = untrack(mounted.read);
      expect(after[2999]!.nested.value).toBe(-update);
      for (let index = 0; index < 2999; index++) expect(after[index]).toBe(before[index]);
    }
    expect(capture.events.filter((event) => event.code === 'HUGE_FAN_IN')).toEqual([]);
  } finally {
    mounted.dispose();
    capture.stop();
  }
});
