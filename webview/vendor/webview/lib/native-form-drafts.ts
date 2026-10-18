import { isNativeFormAnswer } from '../../shared/native-form';
import type { NativeFormAnswer } from '../../shared/protocol';
import { asRecord, isBoolean, isString } from '../../shared/type-utils';
import { STORAGE_KEYS, readStored, writeStored } from './state-storage';

export type NativeMultiSelectDraftRow = {
  id: string;
  value: string;
  selected: boolean;
};

type NativeFormDraft = {
  id: string;
  sessionID: string;
  values: NativeFormAnswer;
  custom: Record<string, string>;
  multiselectRows?: Record<string, NativeMultiSelectDraftRow[]>;
};

function isMultiselectRows(value: unknown): value is Record<string, NativeMultiSelectDraftRow[]> {
  const record = asRecord(value);
  return (
    !!record &&
    Object.values(record).every(
      (rows) =>
        Array.isArray(rows) &&
        rows.every((item) => {
          const row = asRecord(item);
          return !!row && isString(row.id) && isString(row.value) && isBoolean(row.selected);
        })
    )
  );
}

const MAX_DRAFTS = 100;
const MAX_DRAFT_CHARACTERS = 64 * 1024;
const MAX_CACHE_CHARACTERS = 256 * 1024;
const drafts = new Map<string, NativeFormDraft>();
let loaded = false;

function key(id: string, sessionID: string): string {
  return JSON.stringify([sessionID, id]);
}

function load(): void {
  if (loaded) return;
  loaded = true;
  const stored = asRecord(readStored<unknown>(STORAGE_KEYS.nativeFormDrafts));
  if (stored?.version !== 1 || !Array.isArray(stored.entries)) return;
  let restoredCharacters = 0;
  for (const entry of stored.entries.slice(-MAX_DRAFTS)) {
    const draft = asRecord(entry);
    const custom = asRecord(draft?.custom);
    if (
      !draft ||
      !isString(draft.id) ||
      !isString(draft.sessionID) ||
      !isNativeFormAnswer(draft.values) ||
      !custom ||
      !Object.values(custom).every(isString) ||
      (draft.multiselectRows !== undefined && !isMultiselectRows(draft.multiselectRows))
    )
      continue;
    const characters = JSON.stringify(draft).length;
    if (characters > MAX_DRAFT_CHARACTERS || restoredCharacters + characters > MAX_CACHE_CHARACTERS)
      continue;
    restoredCharacters += characters;
    const restored: NativeFormDraft = {
      id: draft.id,
      sessionID: draft.sessionID,
      values: draft.values,
      custom: Object.fromEntries(
        Object.entries(custom).filter((item): item is [string, string] => isString(item[1]))
      ),
    };
    if (draft.multiselectRows !== undefined) restored.multiselectRows = draft.multiselectRows;
    drafts.set(key(draft.id, draft.sessionID), restored);
  }
}

function persist(): void {
  while (
    drafts.size > MAX_DRAFTS ||
    JSON.stringify([...drafts.values()]).length > MAX_CACHE_CHARACTERS
  ) {
    const oldest = drafts.keys().next().value;
    if (oldest === undefined) break;
    drafts.delete(oldest);
  }
  writeStored(
    STORAGE_KEYS.nativeFormDrafts,
    drafts.size ? { version: 1, entries: [...drafts.values()] } : null
  );
}

export const nativeFormDrafts = {
  get(id: string, sessionID: string): NativeFormDraft | undefined {
    load();
    return drafts.get(key(id, sessionID));
  },
  save(draft: NativeFormDraft): boolean {
    load();
    const draftKey = key(draft.id, draft.sessionID);
    drafts.delete(draftKey);
    if (JSON.stringify(draft).length <= MAX_DRAFT_CHARACTERS) drafts.set(draftKey, draft);
    persist();
    return drafts.has(draftKey);
  },
  remove(id: string): void {
    load();
    let changed = false;
    for (const [draftKey, draft] of drafts) {
      if (draft.id !== id) continue;
      drafts.delete(draftKey);
      changed = true;
    }
    if (changed) persist();
  },
};
