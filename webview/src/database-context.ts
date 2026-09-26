import type { DatabaseContext as GridContext } from '../vendor/shared/protocol';
import {
  isDatabaseContext as isGridContext,
  databaseAttachmentDetail,
} from '../vendor/shared/database-context';

export interface DatabaseContext extends Omit<GridContext, 'scope'>, DatabaseContextDetails {
  scope: GridContext['scope'] | 'console' | 'object' | 'datasource';
}

export function isDatabaseContext(value: unknown): value is DatabaseContext {
  const item = record(value);
  if (!item || !isDatabaseScope(item.scope)) return false;
  return (
    isGridContext({
      ...item,
      scope: ['console', 'object', 'datasource'].includes(String(item.scope))
        ? 'table'
        : item.scope,
    }) && isDatabaseContextDetails(item)
  );
}

export function cloneDatabaseContext(context: DatabaseContext): DatabaseContext {
  return JSON.parse(JSON.stringify(context)) as DatabaseContext;
}

export function databaseContextDetail(context: DatabaseContext): string {
  const extended =
    context.scope === 'console' || context.scope === 'object' || context.scope === 'datasource';
  const detail = extended
    ? `${context.scope}${context.truncated ? '; truncated' : ''}${context.pendingChanges ? '; unsubmitted edits' : ''}${context.cellEditing ? '; active cell edit not captured' : ''}`
    : databaseAttachmentDetail({
        ...context,
        scope: context.scope as GridContext['scope'],
        rowCount: context.rows.length,
      });
  return databaseDetailsLabel(context, detail);
}

export function formatDatabaseContext(
  context: DatabaseContext,
  environment?: DatabaseContext | null
): string {
  const text = JSON.stringify(withDatabaseEnvironment(context, environment), null, 2);
  const fence = '`'.repeat(
    Math.max(3, ...[...text.matchAll(/`+/g)].map((match) => match[0].length + 1))
  );
  return `[Database context]\n${fence}json\n${text}\n${fence}`;
}

export interface DatabaseTarget {
  name: string;
  kind: string;
  catalog: string;
  schema: string;
  dataSourceId: string;
}

export interface DatabaseObject extends DatabaseTarget {
  comment: string | null;
  columns?: Array<{
    name: string;
    type: string;
    nullable: boolean;
    primaryKey: boolean;
    default: string | null;
    comment: string | null;
  }>;
  primaryKey?: string[];
  foreignKeys?: Array<{
    name: string;
    columns: string[];
    referencedTable: string;
    referencedColumns: string[];
  }>;
  indexes?: Array<{ name: string; unique: boolean; columns: string[] }>;
  truncated: boolean;
}

export interface DatabaseContextDetails {
  origin?: 'console' | 'grid' | 'ddl' | 'explorer' | 'attachment' | 'workspace';
  dataSources?: Array<{
    id: string;
    name: string;
    dialect: string | null;
    connected: boolean;
    catalogs: string[];
    schemas: string[];
  }>;
  dataSourceCount?: number;
  activeDataSourceId?: string | null;
  activeDataSourceReason?: string;
  connection?: {
    dataSourceId: string | null;
    catalog: string | null;
    schema: string | null;
    searchPath: string[];
    connected: boolean | null;
    readOnly?: boolean;
    autoCommit?: boolean;
  } | null;
  sql?: {
    text: string;
    kind: 'selection' | 'statement' | 'buffer' | 'executed';
    truncated: boolean;
    startLine?: number;
    endLine?: number;
    caretLine?: number;
  };
  target?: DatabaseTarget;
  selectedObjects?: DatabaseTarget[];
  selectedColumns?: string[];
  objects?: DatabaseObject[];
}

const record = (value: unknown): Record<string, unknown> | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
const text = (value: unknown, max = 1_000): value is string =>
  typeof value === 'string' && value.length <= max;
const nullableText = (value: unknown) => value === null || text(value);
const strings = (value: unknown, max = 64, length = 256): value is string[] =>
  Array.isArray(value) && value.length <= max && value.every((item) => text(item, length));
const boolean = (value: unknown) => typeof value === 'boolean';
const target = (value: unknown): boolean => {
  const item = record(value);
  return (
    !!item &&
    text(item.name) &&
    text(item.kind, 256) &&
    text(item.catalog) &&
    text(item.schema) &&
    text(item.dataSourceId)
  );
};

export function isDatabaseScope(value: unknown): boolean {
  return ['table', 'selected-rows', 'ddl', 'console', 'object', 'datasource'].includes(
    value as string
  );
}

/** Validate optional JetBrains fields without rejecting older stored snapshots. */
export function isDatabaseContextDetails(value: Record<string, unknown>): boolean {
  if (
    value.origin !== undefined &&
    !['console', 'grid', 'ddl', 'explorer', 'attachment', 'workspace'].includes(
      value.origin as string
    )
  )
    return false;
  if (value.activeDataSourceId !== undefined && !nullableText(value.activeDataSourceId))
    return false;
  if (value.activeDataSourceReason !== undefined && !text(value.activeDataSourceReason, 256))
    return false;
  if (
    value.dataSourceCount !== undefined &&
    !(Number.isSafeInteger(value.dataSourceCount) && (value.dataSourceCount as number) >= 0)
  )
    return false;
  if (value.dataSources !== undefined) {
    if (
      !Array.isArray(value.dataSources) ||
      value.dataSources.length > 20 ||
      JSON.stringify(value.dataSources).length > 41_000
    )
      return false;
    if (
      !value.dataSources.every((source) => {
        const item = record(source);
        return (
          item &&
          text(item.id) &&
          text(item.name) &&
          nullableText(item.dialect) &&
          boolean(item.connected) &&
          strings(item.catalogs, 16) &&
          strings(item.schemas, 16)
        );
      })
    )
      return false;
    if (
      typeof value.dataSourceCount === 'number' &&
      value.dataSourceCount < value.dataSources.length
    )
      return false;
  }
  if (value.scope === 'datasource' && !Array.isArray(value.dataSources)) return false;
  if (value.connection != null) {
    const item = record(value.connection);
    if (
      !item ||
      !nullableText(item.dataSourceId) ||
      !nullableText(item.catalog) ||
      !nullableText(item.schema) ||
      !strings(item.searchPath, 16, 1_000) ||
      !(item.connected === null || boolean(item.connected)) ||
      !(item.readOnly === undefined || boolean(item.readOnly)) ||
      !(item.autoCommit === undefined || boolean(item.autoCommit))
    )
      return false;
  }
  if (value.sql !== undefined) {
    const item = record(value.sql);
    if (
      !item ||
      !text(item.text, 40_000) ||
      !boolean(item.truncated) ||
      !['selection', 'statement', 'buffer', 'executed'].includes(item.kind as string)
    )
      return false;
    for (const key of ['startLine', 'endLine', 'caretLine']) {
      if (
        item[key] !== undefined &&
        !(Number.isSafeInteger(item[key]) && (item[key] as number) > 0)
      )
        return false;
    }
    if (
      typeof item.startLine === 'number' &&
      typeof item.endLine === 'number' &&
      item.endLine < item.startLine
    )
      return false;
  }
  if (value.scope === 'console' && value.sql === undefined) return false;
  if (value.target !== undefined && !target(value.target)) return false;
  if (
    value.selectedObjects !== undefined &&
    !(
      Array.isArray(value.selectedObjects) &&
      value.selectedObjects.length <= 8 &&
      value.selectedObjects.every(target)
    )
  )
    return false;
  if (value.selectedColumns !== undefined && !strings(value.selectedColumns)) return false;
  if (value.objects !== undefined) {
    if (
      !Array.isArray(value.objects) ||
      value.objects.length > 8 ||
      JSON.stringify(value.objects).length > 61_000
    )
      return false;
    for (const object of value.objects) {
      const item = record(object);
      if (!item || !target(item) || !nullableText(item.comment) || !boolean(item.truncated))
        return false;
      if (
        item.columns !== undefined &&
        !(
          Array.isArray(item.columns) &&
          item.columns.length <= 64 &&
          item.columns.every((column) => {
            const c = record(column);
            return (
              c &&
              text(c.name, 256) &&
              text(c.type, 256) &&
              boolean(c.nullable) &&
              boolean(c.primaryKey) &&
              nullableText(c.default) &&
              nullableText(c.comment)
            );
          })
        )
      )
        return false;
      if (item.primaryKey !== undefined && !strings(item.primaryKey)) return false;
      if (
        item.foreignKeys !== undefined &&
        !(
          Array.isArray(item.foreignKeys) &&
          item.foreignKeys.length <= 16 &&
          item.foreignKeys.every((key) => {
            const k = record(key);
            return (
              k &&
              text(k.name, 256) &&
              strings(k.columns) &&
              text(k.referencedTable) &&
              strings(k.referencedColumns)
            );
          })
        )
      )
        return false;
      if (
        item.indexes !== undefined &&
        !(
          Array.isArray(item.indexes) &&
          item.indexes.length <= 16 &&
          item.indexes.every((index) => {
            const i = record(index);
            return i && text(i.name, 256) && boolean(i.unique) && strings(i.columns);
          })
        )
      )
        return false;
    }
  }
  return true;
}

export function cloneDatabaseDetails(context: DatabaseContextDetails): DatabaseContextDetails {
  return {
    ...context,
    dataSources: context.dataSources?.map((source) => ({
      ...source,
      catalogs: [...source.catalogs],
      schemas: [...source.schemas],
    })),
    connection: context.connection
      ? { ...context.connection, searchPath: [...context.connection.searchPath] }
      : context.connection,
    sql: context.sql ? { ...context.sql } : undefined,
    target: context.target ? { ...context.target } : undefined,
    selectedObjects: context.selectedObjects?.map((item) => ({ ...item })),
    selectedColumns: context.selectedColumns ? [...context.selectedColumns] : undefined,
    objects: context.objects?.map((item) => ({
      ...item,
      columns: item.columns?.map((column) => ({ ...column })),
      primaryKey: item.primaryKey ? [...item.primaryKey] : undefined,
      foreignKeys: item.foreignKeys?.map((key) => ({
        ...key,
        columns: [...key.columns],
        referencedColumns: [...key.referencedColumns],
      })),
      indexes: item.indexes?.map((index) => ({ ...index, columns: [...index.columns] })),
    })),
  };
}

/** Merge project inventory into object context without replacing its connection or SQL. */
export function withDatabaseEnvironment(
  context: DatabaseContext,
  environment?: DatabaseContext | null
): DatabaseContext {
  if (!environment) return context;
  return {
    ...context,
    dataSources: environment.dataSources,
    dataSourceCount: environment.dataSourceCount,
    activeDataSourceId: environment.activeDataSourceId,
    activeDataSourceReason: environment.activeDataSourceReason,
    truncated: context.truncated || environment.truncated,
  };
}

export function databaseDetailsLabel(context: DatabaseContext, detail: string): string {
  const connection = context.connection;
  const namespace =
    connection?.schema || context.target?.schema || connection?.catalog || context.target?.catalog;
  const sql =
    context.scope === 'console' && context.sql
      ? {
          selection: 'selected SQL',
          statement: 'current statement',
          buffer: 'SQL buffer',
          executed: 'executed SQL',
        }[context.sql.kind]
      : null;
  return [
    context.dataSource,
    namespace,
    sql ? detail.replace(/^console/, sql) : detail,
    context.scope === 'console' ? context.target?.name : null,
    connection?.connected === false ? 'disconnected' : null,
    connection?.readOnly ? 'read-only' : null,
  ]
    .filter(Boolean)
    .join(' · ');
}
