import { afterEach, expect, it, vi } from 'vitest';
import { installProjectStorage } from './project-storage';

afterEach(() => vi.unstubAllGlobals());

it('restores IDE preferences and mirrors writes and removals for the next load', () => {
  const send = vi.fn();
  const host = Object.assign(new EventTarget(), {
    __initialWebviewState: { browserStorage: { preference: 'false' } },
    __sendToExtension: send,
  });
  const storage = installProjectStorage(host);
  expect(storage.getItem('preference')).toBe('false');
  storage.setItem('draft', 'text');
  storage.setItem('draft', 'text');
  expect(send).toHaveBeenCalledTimes(1);
  expect(send).toHaveBeenLastCalledWith({ type: 'host/storage', payload: { key: 'draft', value: 'text' } });
  storage.removeItem('preference');
  expect(send).toHaveBeenLastCalledWith({ type: 'host/storage', payload: { key: 'preference', value: null } });
  expect(storage.length).toBe(1);
  expect(storage.key(0)).toBe('draft');
  expect(storage.key(1)).toBeNull();
  storage.clear();
  expect(storage.length).toBe(0);
});

it('updates sibling views without echoing writes and isolates another project', () => {
  vi.stubGlobal('StorageEvent', class extends Event {
    constructor(type: string, init: StorageEventInit) { super(type); Object.assign(this, init); }
  });
  const send = vi.fn();
  const host = Object.assign(new EventTarget(), { __sendToExtension: send });
  const storage = installProjectStorage(host);
  const otherProject = installProjectStorage(new EventTarget());
  const events = vi.fn();
  host.addEventListener('storage', events);
  host.dispatchEvent(new MessageEvent('message', { data: { type: 'host/storage', payload: { key: 'auto-context', value: 'false' } } }));
  expect(storage.getItem('auto-context')).toBe('false');
  expect(otherProject.getItem('auto-context')).toBeNull();
  expect(send).not.toHaveBeenCalled();
  expect(events).toHaveBeenCalledTimes(1);
  expect(events.mock.calls[0]?.[0]).toMatchObject({ key: 'auto-context', oldValue: null, newValue: 'false' });
  host.dispatchEvent(new MessageEvent('message', { data: { type: 'host/storage', payload: { key: 'auto-context', value: null } } }));
  expect(storage.getItem('auto-context')).toBeNull();
  expect(send).not.toHaveBeenCalled();
});
