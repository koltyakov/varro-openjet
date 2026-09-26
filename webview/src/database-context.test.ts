import { describe, expect, it, vi } from 'vitest';
import type { DatabaseContext } from './database-context';
import type { EditorContext } from '../vendor/shared/protocol';
import { cloneDatabaseContext, databaseContextDetail, isDatabaseContext } from './database-context';
import { parseExtensionMessage } from '../vendor/shared/extension-message';
import { formatDatabaseContext } from './database-context';
import { adaptHostContext, databaseSnapshot } from './database-extension';
import {
  formatExtensionContext,
  readExtensionContextBlock,
} from '../vendor/shared/extension-context';
import { buildSessionSendBody } from '../vendor/webview/hooks/session/session-send';

// Exercise the real send builder with an inert host bridge in the Node test runner.
vi.hoisted(() => {
  vi.stubGlobal('window', { addEventListener() {}, removeEventListener() {} });
  vi.stubGlobal('document', { addEventListener() {}, removeEventListener() {} });
});

const target = {
  name: 'app.public.orders',
  kind: 'table',
  catalog: 'app',
  schema: 'public',
  dataSourceId: 'ds-1',
};
function adaptContext(value: unknown): EditorContext {
  const message = parseExtensionMessage({
    type: 'context/update',
    payload: adaptHostContext(value),
  });
  if (message?.type !== 'context/update') throw new Error('Invalid adapted editor context');
  return message.payload;
}
const consoleContext: DatabaseContext = {
  name: 'console.sql',
  dataSource: 'staging',
  dialect: 'PostgreSQL',
  filter: '',
  scope: 'console',
  origin: 'console',
  rows: [],
  columns: [],
  selectedRowCount: 0,
  pendingChanges: true,
  cellEditing: false,
  pageStart: 0,
  truncated: false,
  connection: {
    dataSourceId: 'ds-1',
    catalog: 'app',
    schema: 'public',
    searchPath: ['app.public'],
    connected: false,
    readOnly: true,
    autoCommit: false,
  },
  sql: {
    text: 'select * from orders',
    kind: 'statement',
    startLine: 2,
    endLine: 2,
    caretLine: 2,
    truncated: false,
  },
  target,
  objects: [
    {
      ...target,
      comment: null,
      truncated: false,
      columns: [
        {
          name: 'id',
          type: 'bigint',
          nullable: false,
          primaryKey: true,
          default: null,
          comment: null,
        },
      ],
      primaryKey: ['id'],
      foreignKeys: [
        {
          name: 'customer_fk',
          columns: ['customer_id'],
          referencedTable: 'public.customers',
          referencedColumns: ['id'],
        },
      ],
      indexes: [{ name: 'pk', columns: ['id'], unique: true }],
    },
  ],
};

const environment: DatabaseContext = {
  name: 'Demo SQLite',
  dataSource: 'Demo SQLite',
  dialect: 'SQLite',
  scope: 'datasource',
  origin: 'workspace',
  filter: '',
  columns: [],
  rows: [],
  selectedRowCount: 0,
  pendingChanges: false,
  cellEditing: false,
  pageStart: 0,
  truncated: false,
  activeDataSourceId: 'demo-id',
  activeDataSourceReason: 'explorer',
  dataSourceCount: 2,
  dataSources: [
    {
      id: 'demo-id',
      name: 'Demo SQLite',
      dialect: 'SQLite',
      connected: true,
      catalogs: [],
      schemas: ['main'],
    },
    {
      id: 'other-id',
      name: 'Other SQLite',
      dialect: 'SQLite',
      connected: false,
      catalogs: [],
      schemas: ['main'],
    },
  ],
};

describe('DataGrip context contract', () => {
  it('sends the current database and inventory with no open editor or grid', () => {
    const editorContext = adaptContext({
      workspacePath: '/project',
      activeFile: null,
      selection: null,
      diagnostics: [],
      databaseContext: null,
      databaseEnvironment: environment,
    });
    expect(
      parseExtensionMessage({ type: 'context/update', payload: editorContext })
    ).not.toBeNull();
    const composer = {
      selectedAgent: null,
      selectedModel: null,
      providers: [],
      providerDefaults: {},
      modelVariantSelections: {},
      editorContext,
      terminalSelection: null,
      droppedFiles: [],
      clipboardImages: [],
    };
    const sent = buildSessionSendBody(composer, 'ses-test', "What's the current DB?", () => true);
    expect(JSON.stringify(sent)).toContain('Demo SQLite');
    expect(JSON.stringify(sent)).toContain('Other SQLite');
    expect(JSON.stringify(sent)).toContain('activeDataSourceId');
    expect(
      JSON.stringify(buildSessionSendBody(composer, 'ses-test', 'hello', () => false))
    ).not.toContain('Demo SQLite');
  });

  it('preserves file context while also sending the database environment', () => {
    const sent = buildSessionSendBody(
      {
        selectedAgent: null,
        selectedModel: null,
        providers: [],
        providerDefaults: {},
        modelVariantSelections: {},
        terminalSelection: null,
        droppedFiles: [],
        clipboardImages: [],
        editorContext: adaptContext({
          workspacePath: '/project',
          activeFile: {
            path: '/project/readme.md',
            relativePath: 'readme.md',
            language: 'markdown',
          },
          selection: null,
          diagnostics: [],
          databaseEnvironment: environment,
        }),
      },
      'ses-test',
      'Which DB does this describe?',
      () => true
    );
    expect(JSON.stringify(sent)).toContain('Demo SQLite');
    expect(JSON.stringify(sent)).toContain('readme.md');
  });

  it('freezes datasource inventory and merges it without replacing the console connection', () => {
    const original = structuredClone(environment);
    const queued = cloneDatabaseContext(original)!;
    original.dataSources![0]!.schemas[0] = 'changed';
    expect(queued.dataSources![0]!.schemas).toEqual(['main']);
    const formatted = formatDatabaseContext(consoleContext, environment);
    expect(formatted).toContain('Demo SQLite');
    expect(formatted).toContain('select * from orders');
    expect(formatted).toContain('app.public.orders');
    expect(formatted).toContain('staging');
  });

  it('passes console, Explorer, grid, and clearing snapshots through the real message parser', () => {
    for (const databaseContext of [
      consoleContext,
      {
        ...consoleContext,
        scope: 'object',
        origin: 'explorer',
        sql: undefined,
        selectedObjects: [target],
      },
      {
        ...consoleContext,
        scope: 'selected-rows',
        origin: 'grid',
        selectedRowCount: 1,
        rows: [['1']],
        columns: [{ name: 'id', type: 'bigint' }],
        selectedColumns: ['id'],
      },
      null,
    ]) {
      const message = JSON.parse(
        JSON.stringify({
          type: 'context/update',
          payload: {
            workspacePath: '/project',
            activeFile: null,
            selection: null,
            diagnostics: [],
            databaseContext,
          },
        })
      );
      const adapted = adaptHostContext(message);
      expect(parseExtensionMessage(adapted)).toEqual(adapted);
    }
  });

  it('freezes connection and nested schema details for queued messages', () => {
    const original = structuredClone(consoleContext);
    original.selectedObjects = [{ ...target }];
    const queued = cloneDatabaseContext(original)!;
    original.connection!.schema = 'private';
    original.connection!.searchPath[0] = 'app.private';
    original.sql!.text = 'delete from orders';
    original.objects![0]!.columns![0]!.name = 'changed';
    original.objects![0]!.foreignKeys![0]!.referencedColumns[0] = 'changed';
    original.objects![0]!.indexes![0]!.columns[0] = 'changed';
    original.selectedObjects[0]!.name = 'changed';
    expect(queued.connection!.schema).toBe('public');
    expect(queued.connection!.searchPath).toEqual(['app.public']);
    expect(queued.sql!.text).toBe('select * from orders');
    expect(queued.objects).toEqual(consoleContext.objects);
    expect(queued.selectedObjects).toEqual([target]);
  });

  it('names the datasource, schema, SQL focus, and disconnected state in the chip', () => {
    expect(databaseContextDetail(consoleContext)).toContain('staging · public · current statement');
    expect(databaseContextDetail(consoleContext)).toContain('disconnected');
    expect(databaseContextDetail(consoleContext)).toContain('read-only');
  });

  it('keeps older table snapshots valid and readable', () => {
    const legacy = {
      name: 'orders',
      dataSource: null,
      dialect: null,
      filter: '',
      columns: [],
      rows: [],
      selectedRowCount: 0,
      scope: 'table',
      pendingChanges: false,
      cellEditing: false,
      pageStart: 0,
      truncated: false,
    };
    expect(isDatabaseContext(legacy)).toBe(true);
    expect(databaseContextDetail(legacy as DatabaseContext)).toBe('table');
  });

  it('rejects malformed or oversized context before it reaches the composer', () => {
    for (const override of [
      { sql: undefined },
      { sql: { ...consoleContext.sql, text: 'x'.repeat(40_001) } },
      { sql: { ...consoleContext.sql, startLine: 3, endLine: 2 } },
      { connection: { ...consoleContext.connection, connected: 'yes' } },
      { selectedObjects: Array(9).fill(target) },
      {
        objects: [
          { ...consoleContext.objects![0], columns: [{ name: 'id', type: 'int', nullable: 'no' }] },
        ],
      },
      { selectedColumns: ['x'.repeat(257)] },
    ])
      expect(isDatabaseContext({ ...consoleContext, ...override })).toBe(false);
  });

  it('includes SQL and relationships in model context and round-trips console attachments', () => {
    const formatted = formatDatabaseContext(consoleContext);
    expect(formatted).toContain('select * from orders');
    expect(formatted).toContain('public.customers');
    const snapshot = databaseSnapshot(consoleContext);
    const lines = formatExtensionContext(snapshot).split('\n');
    expect(readExtensionContextBlock(lines, 0)?.context).toEqual(snapshot);
  });
});
