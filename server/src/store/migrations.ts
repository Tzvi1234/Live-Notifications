/**
 * Numbered, forward-only migrations.
 *
 * What this replaces: `schema.sql` was replayed in full on every boot, which worked only
 * because every statement in it was `CREATE ... IF NOT EXISTS`. That file could add a table
 * but could never alter one — an `ALTER TABLE` in it would run again on the next boot and
 * fail, and a `CREATE TABLE` amended in place would silently do nothing on a database that
 * already had the old shape. So the schema was frozen from the first deploy onwards.
 *
 * Here each file under `migrations/` is applied at most once, in numeric order, inside its
 * own transaction, and recorded in `schema_migrations`. A migration is therefore free to
 * alter, backfill and drop; the price is that it can never be edited once it has run
 * anywhere, because the row in `schema_migrations` is what stops it running again.
 *
 * The SQL execution is behind `MigrationClient` — a checked-out `pg.PoolClient` satisfies
 * it structurally — so the ordering and the applied-once rule are testable without a
 * database.
 */

import { readdir, readFile } from 'node:fs/promises';

import type { Logger } from '../logger.js';

/** `NNN_lower_snake_name.sql`; the number is the version and it orders the file. */
const MIGRATION_FILE = /^(\d{3,})_([a-z0-9_]+)\.sql$/;

export interface Migration {
  readonly version: number;
  readonly name: string;
  readonly sql: string;
}

/** The slice of `pg.PoolClient` a migration needs. Only `rows` is ever read. */
export interface MigrationClient {
  query(sql: string, values?: unknown[]): Promise<{ rows: unknown[] }>;
}

/**
 * Created before anything else and never migrated itself, so its shape is fixed forever:
 * anything added here could not be added by a migration, since every migration needs the
 * table to already exist in order to be recorded.
 */
const CREATE_SCHEMA_MIGRATIONS = `
  CREATE TABLE IF NOT EXISTS schema_migrations (
    version    INTEGER PRIMARY KEY,
    name       TEXT        NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
  )
`;

export function parseMigrationFileName(
  fileName: string,
): { version: number; name: string } | undefined {
  const match = MIGRATION_FILE.exec(fileName);
  if (!match) return undefined;
  const version = Number(match[1]);
  const name = match[2];
  if (!Number.isSafeInteger(version) || version <= 0 || name === undefined) return undefined;
  return { version, name };
}

/**
 * Sorted by version, with duplicates refused: two files claiming version 004 would apply in
 * whatever order the filesystem listed them and only one would ever be recorded, so the
 * database's history would depend on the directory read. That is worth failing the boot for.
 */
export function orderMigrations(migrations: readonly Migration[]): Migration[] {
  const ordered = [...migrations].sort((a, b) => a.version - b.version);
  for (let index = 1; index < ordered.length; index += 1) {
    const previous = ordered[index - 1]!;
    const current = ordered[index]!;
    if (previous.version === current.version) {
      throw new Error(
        `[store] two migrations share version ${current.version} ` +
          `("${previous.name}" and "${current.name}"); renumber one of them.`,
      );
    }
  }
  return ordered;
}

/**
 * SQL files are data, not code, so they do not follow the TypeScript build automatically.
 * The compiled location is tried first, then the source tree, so `tsx src/index.ts` and a
 * `dist/` deploy that copied the directory both work — the same two candidates the old
 * `readSchemaSql` used.
 */
export async function loadMigrations(): Promise<Migration[]> {
  const candidates = [
    new URL('./migrations/', import.meta.url),
    new URL('../../src/store/migrations/', import.meta.url),
  ];

  for (const directory of candidates) {
    let fileNames: string[];
    try {
      fileNames = await readdir(directory);
    } catch {
      continue;
    }

    const migrations: Migration[] = [];
    for (const fileName of fileNames) {
      const parsed = parseMigrationFileName(fileName);
      if (!parsed) continue;
      migrations.push({
        version: parsed.version,
        name: parsed.name,
        sql: await readFile(new URL(fileName, directory), 'utf8'),
      });
    }
    if (migrations.length > 0) return orderMigrations(migrations);
  }

  throw new Error(
    `[store] no migrations found (looked in ${candidates.map((c) => c.pathname).join(', ')}). ` +
      'The build must copy src/store/migrations into dist/store/migrations.',
  );
}

/**
 * Applies every migration the database has not recorded yet and returns the versions it
 * applied — empty on the second call, which is the property the whole design rests on.
 *
 * Each file gets its own transaction rather than one transaction for all of them: a failure
 * in 004 must not roll back a 003 that succeeded, or the next boot would replay 003 against
 * objects it had already created.
 *
 * The caller holds the advisory lock. Without it two instances booting together would both
 * read an empty `schema_migrations` and both run the same file; the transaction would make
 * one of them fail rather than corrupt anything, but a failed boot during a deploy is worse
 * than a serialized one.
 */
export async function applyMigrations(
  client: MigrationClient,
  migrations: readonly Migration[],
  logger: Logger,
): Promise<number[]> {
  await client.query(CREATE_SCHEMA_MIGRATIONS);

  const { rows } = await client.query('SELECT version FROM schema_migrations');
  const applied = new Set<number>();
  for (const row of rows) {
    const version = Number((row as { version?: unknown }).version);
    if (Number.isSafeInteger(version)) applied.add(version);
  }

  const pending = orderMigrations(migrations).filter(
    (migration) => !applied.has(migration.version),
  );
  if (pending.length === 0) {
    logger.info('schema up to date', { applied: applied.size });
    return [];
  }

  const ran: number[] = [];
  for (const migration of pending) {
    const started = Date.now();
    try {
      await client.query('BEGIN');
      await client.query(migration.sql);
      // ON CONFLICT DO NOTHING covers the one race the advisory lock cannot: a database
      // shared with an instance that was already past the lock when this one read the table.
      await client.query(
        'INSERT INTO schema_migrations (version, name) VALUES ($1, $2) ON CONFLICT (version) DO NOTHING',
        [migration.version, migration.name],
      );
      await client.query('COMMIT');
    } catch (error) {
      await client.query('ROLLBACK').catch(() => undefined);
      throw new Error(
        `[store] migration ${migration.version}_${migration.name} failed and was rolled back; ` +
          'the database is still on the previous version.',
        { cause: error },
      );
    }
    ran.push(migration.version);
    logger.info('migration applied', {
      version: migration.version,
      name: migration.name,
      durationMs: Date.now() - started,
    });
  }

  return ran;
}
