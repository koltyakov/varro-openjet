import type { ExtensionContext } from '../vendor/shared/extension-context';
import type { ContextProvider } from '../vendor/webview/host/extensions';
import {
  isDatabaseContext,
  databaseContextDetail,
  withDatabaseEnvironment,
} from './database-context';
import type { DatabaseContext } from './database-context';

export const databaseProvider: ContextProvider = {
  id: 'openjet.database',
  version: 1,
  validate: isDatabaseContext,
  capture(data) {
    if (!isDatabaseContext(data)) throw new Error('Invalid database context');
    return {
      text: JSON.stringify(data, null, 2),
      detail: databaseContextDetail(data),
      icon: 'table',
    };
  },
  readLegacyBlock(lines, index) {
    if (lines[index]?.trim() !== '[Database context]') return null;
    const opening = lines[index + 1]?.trim().match(/^(`{3,})json$/);
    if (!opening) return null;
    const end = lines.findIndex(
      (line, position) => position > index + 1 && line.trim() === opening[1]
    );
    if (end < 0) return null;
    try {
      const data: unknown = JSON.parse(lines.slice(index + 2, end).join('\n'));
      return isDatabaseContext(data) ? { context: databaseSnapshot(data), end } : null;
    } catch {
      return null;
    }
  },
};

export function databaseSnapshot(
  data: DatabaseContext,
  placement: ExtensionContext['placement'] = 'replace-document'
): ExtensionContext {
  return {
    provider: databaseProvider.id,
    version: 1,
    label: data.name,
    placement,
    data: JSON.parse(JSON.stringify(data)),
    captured: databaseProvider.capture(data),
  };
}

/** Gson omits null object fields on the native wire. Restore the database schema's nullable fields. */
function normalizeNativeDatabase(value: unknown): unknown {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
  const source = value as Record<string, unknown>;
  const result: Record<string, unknown> = { dataSource: null, dialect: null, ...source };
  if (Array.isArray(source.dataSources)) {
    result.dataSources = source.dataSources.map((item: unknown) =>
      item && typeof item === 'object' && !Array.isArray(item) ? { dialect: null, ...item } : item
    );
  }
  if (
    source.connection &&
    typeof source.connection === 'object' &&
    !Array.isArray(source.connection)
  ) {
    result.connection = {
      dataSourceId: null,
      catalog: null,
      schema: null,
      connected: null,
      ...source.connection,
    };
  }
  if (Array.isArray(source.objects)) {
    result.objects = source.objects.map((item: unknown) => {
      if (!item || typeof item !== 'object' || Array.isArray(item)) return item;
      const object = { comment: null, ...item } as Record<string, unknown>;
      if (Array.isArray(object.columns)) {
        object.columns = object.columns.map((column: unknown) =>
          column && typeof column === 'object' && !Array.isArray(column)
            ? { default: null, comment: null, ...column }
            : column
        );
      }
      return object;
    });
  }
  return result;
}

/** Translate the native host's existing wire format at the host boundary, including restored queues. */
export function adaptHostContext(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(adaptHostContext);
  if (!value || typeof value !== 'object') return value;
  const source = value as Record<string, unknown>;
  const result = { ...source };
  for (const key of ['editorContext', 'queuedContext', 'queuedMessages', 'varro.queuedMessages']) {
    if (Object.hasOwn(source, key)) result[key] = adaptHostContext(source[key]);
  }
  if (source.type === 'context/update') result.payload = adaptHostContext(source.payload);
  if (
    source.type === 'queued-messages/sync' &&
    source.payload &&
    typeof source.payload === 'object'
  ) {
    const payload = source.payload as Record<string, unknown>;
    result.payload = { ...payload, messages: adaptHostContext(payload.messages) };
  }
  if (!Object.hasOwn(source, 'databaseContext') && !Object.hasOwn(source, 'databaseEnvironment'))
    return result;
  const database = normalizeNativeDatabase(source.databaseContext);
  const environment = normalizeNativeDatabase(source.databaseEnvironment);
  if (database != null && !isDatabaseContext(database))
    throw new Error('Invalid native database context');
  if (environment != null && !isDatabaseContext(environment))
    throw new Error('Invalid native database environment');
  delete result.databaseContext;
  delete result.databaseEnvironment;
  result.extensionContexts = Array.isArray(result.extensionContexts)
    ? result.extensionContexts
    : [];
  const data = database
    ? withDatabaseEnvironment(database, environment as DatabaseContext | null)
    : environment;
  if (data) {
    const existing = Array.isArray(result.extensionContexts) ? result.extensionContexts : [];
    result.extensionContexts = [
      ...existing,
      databaseSnapshot(
        data as DatabaseContext,
        database ? 'replace-document' : 'alongside-document'
      ),
    ];
  }
  return result;
}
