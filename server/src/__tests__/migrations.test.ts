/**
 * The migration runner's one guarantee: a migration is applied at most once, ever. The
 * whole design rests on it — the file `001_initial_schema.sql` is the schema that was
 * replayed on every boot, and it is only safe to fold in because a second run cannot happen.
 *
 * The SQL is executed against a fake client rather than a database, which is the point of
 * `MigrationClient` being an interface: what is under test is the ordering, the recording
 * and the applied-once rule, none of which needs Postgres to be true.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import {
  applyMigrations,
  loadMigrations,
  orderMigrations,
  parseMigrationFileName,
  type Migration,
  type MigrationClient,
} from '../store/migrations.js';
import type { Logger } from '../logger.js';

const silentLogger: Logger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
  child: () => silentLogger,
};

/** Records every statement and keeps a `schema_migrations` table in a Set. */
class FakeClient implements MigrationClient {
  readonly statements: string[] = [];
  readonly applied = new Set<number>();
  /** SQL fragment that should throw when executed, standing in for a broken migration. */
  failOn: string | undefined;

  async query(sql: string, values?: unknown[]): Promise<{ rows: unknown[] }> {
    this.statements.push(sql.trim().split('\n')[0]!.trim());

    if (this.failOn !== undefined && sql.includes(this.failOn)) {
      throw new Error(`fake failure on ${this.failOn}`);
    }
    if (sql.startsWith('SELECT version FROM schema_migrations')) {
      return { rows: [...this.applied].map((version) => ({ version })) };
    }
    if (sql.startsWith('INSERT INTO schema_migrations')) {
      this.applied.add(Number(values?.[0]));
      return { rows: [] };
    }
    return { rows: [] };
  }
}

const MIGRATIONS: Migration[] = [
  { version: 1, name: 'initial_schema', sql: '-- 001\nCREATE TABLE IF NOT EXISTS devices ()' },
  { version: 2, name: 'accounts', sql: '-- 002\nCREATE TABLE IF NOT EXISTS users ()' },
];

describe('parseMigrationFileName', () => {
  test('reads the version and the name out of NNN_name.sql', () => {
    assert.deepEqual(parseMigrationFileName('001_initial_schema.sql'), {
      version: 1,
      name: 'initial_schema',
    });
    assert.deepEqual(parseMigrationFileName('017_add_column.sql'), {
      version: 17,
      name: 'add_column',
    });
  });

  test('ignores anything that is not a numbered migration', () => {
    // A stray README, an editor backup or a file whose number was forgotten must not be
    // executed as SQL against the production database.
    for (const name of ['README.md', 'schema.sql', 'initial.sql', '1_x.sql', '001-x.sql']) {
      assert.equal(parseMigrationFileName(name), undefined, name);
    }
  });
});

describe('orderMigrations', () => {
  test('sorts by version, not by file order', () => {
    const ordered = orderMigrations([MIGRATIONS[1]!, MIGRATIONS[0]!]);
    assert.deepEqual(
      ordered.map((migration) => migration.version),
      [1, 2],
    );
  });

  test('refuses two migrations sharing a version', () => {
    // Whichever ran first would be the only one ever recorded, so the schema would depend
    // on the order the filesystem happened to list them in.
    assert.throws(
      () => orderMigrations([...MIGRATIONS, { version: 2, name: 'other', sql: '' }]),
      /share version 2/,
    );
  });
});

describe('applyMigrations', () => {
  test('applies everything on a fresh database and records each one', async () => {
    const client = new FakeClient();
    const applied = await applyMigrations(client, MIGRATIONS, silentLogger);
    assert.deepEqual(applied, [1, 2]);
    assert.deepEqual([...client.applied], [1, 2]);
  });

  test('is idempotent: a second run applies nothing and issues no migration SQL', async () => {
    const client = new FakeClient();
    await applyMigrations(client, MIGRATIONS, silentLogger);

    const before = client.statements.length;
    const second = await applyMigrations(client, MIGRATIONS, silentLogger);
    assert.deepEqual(second, []);

    // Exactly two statements on the second pass: create-if-not-exists and the read. No
    // BEGIN, no migration body, no INSERT — the property the whole design rests on.
    const issued = client.statements.slice(before);
    assert.equal(issued.length, 2);
    assert.ok(issued[0]?.startsWith('CREATE TABLE IF NOT EXISTS schema_migrations'));
    assert.ok(issued[1]?.startsWith('SELECT version FROM schema_migrations'));
  });

  test('applies only what is new when a migration is added later', async () => {
    const client = new FakeClient();
    await applyMigrations(client, [MIGRATIONS[0]!], silentLogger);

    const applied = await applyMigrations(client, MIGRATIONS, silentLogger);
    assert.deepEqual(applied, [2]);
    assert.ok(!client.statements.slice(-6).some((sql) => sql.startsWith('-- 001')));
  });

  test('an already-migrated database records nothing twice even out of order', async () => {
    const client = new FakeClient();
    client.applied.add(2);
    const applied = await applyMigrations(client, MIGRATIONS, silentLogger);
    assert.deepEqual(applied, [1]);
  });

  test('each migration runs in its own transaction', async () => {
    const client = new FakeClient();
    await applyMigrations(client, MIGRATIONS, silentLogger);
    assert.equal(client.statements.filter((sql) => sql === 'BEGIN').length, 2);
    assert.equal(client.statements.filter((sql) => sql === 'COMMIT').length, 2);
  });

  test('a failing migration rolls back and leaves the earlier ones applied', async () => {
    const client = new FakeClient();
    client.failOn = '-- 002';

    await assert.rejects(
      () => applyMigrations(client, MIGRATIONS, silentLogger),
      /migration 2_accounts failed and was rolled back/,
    );
    // 001 committed and stays recorded; 002 did not, so the next boot retries only it.
    assert.deepEqual([...client.applied], [1]);
    assert.ok(client.statements.includes('ROLLBACK'));
  });
});

describe('the migrations that actually ship', () => {
  test('load from disk, in order, with no duplicate versions', async () => {
    const migrations = await loadMigrations();
    assert.ok(migrations.length >= 2);
    assert.deepEqual(
      migrations.map((migration) => migration.version),
      [...migrations.map((migration) => migration.version)].sort((a, b) => a - b),
    );
    assert.equal(migrations[0]?.version, 1);
    assert.equal(migrations[0]?.name, 'initial_schema');
  });

  test('001 is idempotent against a database that already has those tables', async () => {
    const [initial] = await loadMigrations();
    // The live database predates the runner and already holds every object in 001, so the
    // file has to be a no-op there. Every CREATE in it says IF NOT EXISTS; a later
    // migration may not, which is why only this one is checked.
    const creates = (initial?.sql ?? '').match(/^\s*CREATE\s+(?:TABLE|INDEX)\b.*/gim) ?? [];
    assert.ok(creates.length > 0);
    for (const statement of creates) {
      assert.match(statement, /IF NOT EXISTS/i, statement.trim());
    }
  });

  test('applies cleanly and then does nothing, twice over', async () => {
    const client = new FakeClient();
    const migrations = await loadMigrations();
    const first = await applyMigrations(client, migrations, silentLogger);
    assert.equal(first.length, migrations.length);
    assert.deepEqual(await applyMigrations(client, migrations, silentLogger), []);
    assert.deepEqual(await applyMigrations(client, migrations, silentLogger), []);
  });
});
