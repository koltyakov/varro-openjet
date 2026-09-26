import { expect, it } from 'vitest';
import { adaptHostContext } from './database-extension';
import { parseExtensionMessage } from '../vendor/shared/extension-message';

const database = {
  name: 'orders',
  dataSource: 'local',
  dialect: 'SQLite',
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
const editorContext = {
  workspacePath: '/repo',
  activeFile: null,
  selection: null,
  diagnostics: [],
  databaseContext: database,
};

it('restores nullable fields omitted by Gson in the startup database environment', () => {
  const environment = {
    ...database,
    dataSource: null,
    dialect: null,
    name: '0 datasources',
    scope: 'datasource',
    origin: 'workspace',
    dataSources: [],
    dataSourceCount: 0,
  };
  const wire = JSON.parse(
    JSON.stringify(environment, (_key, value: unknown) => (value === null ? undefined : value))
  );
  const initialState = {
    editorContext: { ...editorContext, databaseContext: null, databaseEnvironment: wire },
  };
  expect(adaptHostContext(initialState)).toMatchObject({
    editorContext: {
      extensionContexts: [
        { data: environment, captured: { text: expect.stringContaining('"dialect": null') } },
      ],
    },
  });
  expect(wire).not.toHaveProperty('dialect');
});

it('normalizes nullable database details while rejecting malformed native values', () => {
  const data = {
    ...database,
    dataSources: [{ id: 'db', name: 'local', connected: false, catalogs: [], schemas: [] }],
    connection: { searchPath: [] },
    objects: [
      {
        name: 'orders',
        kind: 'table',
        catalog: '',
        schema: '',
        dataSourceId: 'db',
        truncated: false,
        columns: [{ name: 'id', type: 'int', nullable: false, primaryKey: true }],
      },
    ],
  };
  expect(adaptHostContext({ databaseContext: data })).toMatchObject({
    extensionContexts: [
      {
        data: {
          dataSources: [{ dialect: null }],
          connection: { dataSourceId: null, catalog: null, schema: null, connected: null },
          objects: [{ comment: null, columns: [{ default: null, comment: null }] }],
        },
      },
    ],
  });
  expect(() => adaptHostContext({ databaseContext: { ...data, dialect: 42 } })).toThrow(
    'Invalid native database context'
  );
});

it('clears contributed chips and normalizes restored queues without mutating their source', () => {
  expect(
    adaptHostContext({ ...editorContext, databaseContext: null, databaseEnvironment: null })
  ).toMatchObject({ extensionContexts: [] });
  const message = {
    type: 'queued-messages/sync',
    payload: {
      messages: [
        {
          id: 'q1',
          sessionId: 's1',
          text: 'Explain',
          queuedContext: { currentDocumentEnabled: true, editorContext },
        },
      ],
    },
  };
  const adapted = adaptHostContext(message);
  expect(parseExtensionMessage(adapted)).toEqual(adapted);
  expect(adapted).toMatchObject({
    payload: {
      messages: [
        {
          queuedContext: {
            editorContext: {
              extensionContexts: [
                {
                  provider: 'openjet.database',
                  version: 1,
                  label: 'orders',
                  captured: { icon: 'table' },
                },
              ],
            },
          },
        },
      ],
    },
  });
  expect(editorContext.databaseContext).toBe(database);
  expect(Object.hasOwn(editorContext, 'extensionContexts')).toBe(false);
});

it('preserves unknown provider data and leaves server history untouched', () => {
  const context = {
    provider: 'other.feature',
    version: 99,
    label: 'Future',
    placement: 'alongside-document',
    data: { databaseContext: 'opaque' },
  };
  const snapshot = { ...editorContext, databaseContext: null, extensionContexts: [context] };
  expect(adaptHostContext(snapshot)).toMatchObject({ extensionContexts: [context] });
  const event = { type: 'server/event', payload: { data: { databaseContext: 'opaque' } } };
  expect(adaptHostContext(event)).toEqual(event);
});
