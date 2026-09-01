/**
 * One JSON object per line on stdout — the shape Render's log drain and `jq` both expect.
 *
 * Reads LOG_LEVEL directly rather than importing config: the config module throws when
 * API_FOOTBALL_KEY is missing, and that failure has to be loggable.
 */

export const LOG_LEVELS = ['debug', 'info', 'warn', 'error'] as const;

export type LogLevel = (typeof LOG_LEVELS)[number];

const LEVEL_RANK: Readonly<Record<LogLevel, number>> = Object.freeze({
  debug: 10,
  info: 20,
  warn: 30,
  error: 40,
});

export type LogFields = Record<string, unknown>;

export interface Logger {
  debug(msg: string, fields?: LogFields): void;
  info(msg: string, fields?: LogFields): void;
  warn(msg: string, fields?: LogFields): void;
  error(msg: string, fields?: LogFields): void;
  /** Returns a logger that merges `bindings` into every line (e.g. { matchId }). */
  child(bindings: LogFields): Logger;
}

/**
 * Own keys only: `value in LEVEL_RANK` is also true for everything on Object.prototype, so
 * LOG_LEVEL=constructor would be accepted here and then rank as `undefined` in `write`,
 * which silently disables the level filter.
 */
export function isLogLevel(value: string): value is LogLevel {
  return Object.hasOwn(LEVEL_RANK, value);
}

function parseLevel(value: string | undefined, fallback: LogLevel): LogLevel {
  const candidate = value?.trim().toLowerCase();
  return candidate !== undefined && isLogLevel(candidate) ? candidate : fallback;
}

let activeLevel: LogLevel = parseLevel(process.env.LOG_LEVEL, 'info');

export function setLogLevel(level: LogLevel): void {
  activeLevel = level;
}

export function getLogLevel(): LogLevel {
  return activeLevel;
}

/** Errors do not survive JSON.stringify; unwrap them before the line is built. */
function normalize(value: unknown): unknown {
  if (value instanceof Error) {
    return {
      name: value.name,
      message: value.message,
      stack: value.stack,
      ...(value.cause !== undefined ? { cause: normalize(value.cause) } : {}),
    };
  }
  if (value instanceof Set) return [...value];
  if (value instanceof Map) return Object.fromEntries(value);
  if (typeof value === 'bigint') return value.toString();
  return value;
}

function write(level: LogLevel, bindings: LogFields, msg: string, fields?: LogFields): void {
  if (LEVEL_RANK[level] < LEVEL_RANK[activeLevel]) return;

  const line: LogFields = { ts: new Date().toISOString(), level, msg };
  for (const [key, value] of Object.entries({ ...bindings, ...fields })) {
    if (value === undefined) continue;
    // Never let a caller's field shadow the three the log pipeline keys off.
    line[key === 'ts' || key === 'level' || key === 'msg' ? `_${key}` : key] = normalize(value);
  }

  let serialized: string;
  try {
    serialized = JSON.stringify(line);
  } catch {
    // Circular structure somewhere in the fields: keep the message, drop the payload.
    serialized = JSON.stringify({ ts: line.ts, level, msg, fieldsUnserializable: true });
  }
  // Everything on stdout, including errors, so the ordering of a single run is preserved.
  process.stdout.write(`${serialized}\n`);
}

export function createLogger(bindings: LogFields = {}): Logger {
  return {
    debug: (msg, fields) => write('debug', bindings, msg, fields),
    info: (msg, fields) => write('info', bindings, msg, fields),
    warn: (msg, fields) => write('warn', bindings, msg, fields),
    error: (msg, fields) => write('error', bindings, msg, fields),
    child: (extra) => createLogger({ ...bindings, ...extra }),
  };
}

export const logger: Logger = createLogger();
