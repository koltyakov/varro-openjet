export interface ProjectStorageHost extends EventTarget {
  __initialWebviewState?: { browserStorage?: Record<string, string> };
  __sendToExtension?: (message: unknown) => void;
}

// JCEF's browser cache is not project storage. The IDE owns shared preferences;
// drafts remain in the separate per-view state channel.
export function installProjectStorage(host: ProjectStorageHost): Storage {
  const values = new Map(Object.entries(host.__initialWebviewState?.browserStorage ?? {}));
  const storage: Storage = {
    get length() { return values.size; },
    key(index) { return [...values.keys()][index] ?? null; },
    getItem(key) { return values.get(String(key)) ?? null; },
    setItem(key, value) {
      key = String(key);
      value = String(value);
      if (values.get(key) === value) return;
      values.set(key, value);
      host.__sendToExtension?.({ type: 'host/storage', payload: { key, value } });
    },
    removeItem(key) {
      key = String(key);
      if (!values.delete(key)) return;
      host.__sendToExtension?.({ type: 'host/storage', payload: { key, value: null } });
    },
    clear() { for (const key of [...values.keys()]) storage.removeItem(key); },
  };
  Object.defineProperty(host, 'localStorage', { value: storage, configurable: true });
  host.addEventListener('message', (event) => {
    const message = (event as MessageEvent).data;
    if (message?.type !== 'host/storage') return;
    const { key, value } = message.payload;
    const oldValue = storage.getItem(key);
    if (oldValue === value) return;
    if (value === null) values.delete(key); else values.set(key, value);
    host.dispatchEvent(new StorageEvent('storage', { key, oldValue, newValue: value }));
  });
  return storage;
}
