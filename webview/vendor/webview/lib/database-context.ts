import type { DatabaseContext } from '../../shared/protocol';
import { withDatabaseEnvironment } from '../../../src/database-context';

export function formatDatabaseContext(context: DatabaseContext, environment?: DatabaseContext | null): string {
  const text = JSON.stringify(withDatabaseEnvironment(context, environment), null, 2);
  const fence = '`'.repeat(
    Math.max(3, ...[...text.matchAll(/`+/g)].map((match) => match[0].length + 1))
  );
  return `[Database context]\n${fence}json\n${text}\n${fence}`;
}
