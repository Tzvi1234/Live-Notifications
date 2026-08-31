/**
 * Query, path and body coercion for the REST layer.
 *
 * Every failure here throws an `HttpError`, which the app's error handler renders as
 * `{error, requestId}`. A route never formats its own failure response, so the body shape
 * is identical whether the request was rejected by a validator, by the provider or by a bug.
 */

/** An error whose message is safe to hand to the client; `status` is what gets sent. */
export class HttpError extends Error {
  readonly status: number;

  constructor(status: number, message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'HttpError';
    this.status = status;
  }
}

export function badRequest(message: string): HttpError {
  return new HttpError(400, message);
}

export function unauthorized(message: string): HttpError {
  return new HttpError(401, message);
}

export function notFound(message: string): HttpError {
  return new HttpError(404, message);
}

export function serviceUnavailable(message: string): HttpError {
  return new HttpError(503, message);
}

/** An FCM registration token is ~200 characters; this is pure abuse defence. */
const MAX_TOKEN_LENGTH = 4096;

/** The provider's `search` filter rejects anything shorter. */
export const MIN_SEARCH_LENGTH = 3;

/** A query cannot name more ids than a screen could ever render. */
const MAX_QUERY_IDS = 100;

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Anything outside printable ASCII: whitespace, control bytes, smuggled newlines. */
const NOT_PRINTABLE_ASCII = /[^!-~]/;

const DAY_MS = 86_400_000;

/**
 * Express hands a query parameter over as `string | string[] | ParsedQs`; only the first
 * scalar is meaningful, and an empty string means "sent but blank", which is not a value.
 */
export function queryValue(raw: unknown): string | undefined {
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

const TRUE_VALUES: ReadonlySet<string> = new Set(['true', '1', 'yes', 'on']);
const FALSE_VALUES: ReadonlySet<string> = new Set(['false', '0', 'no', 'off']);

/** `?featured` with no value is the flag being named, so it reads as true. */
export function parseBooleanFlag(raw: unknown, field: string): boolean | undefined {
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'boolean') return value;
  if (typeof value !== 'string') {
    throw badRequest(`Query parameter "${field}" must be true or false.`);
  }
  const text = value.trim().toLowerCase();
  if (text.length === 0) return true;
  if (TRUE_VALUES.has(text)) return true;
  if (FALSE_VALUES.has(text)) return false;
  throw badRequest(`Query parameter "${field}" must be true or false; got "${value}".`);
}

export function parsePositiveInt(raw: unknown, field: string): number | undefined {
  const value = queryValue(raw);
  if (value === undefined) return undefined;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw badRequest(`Query parameter "${field}" must be a positive whole number; got "${value}".`);
  }
  return parsed;
}

export function requirePositiveInt(raw: unknown, field: string): number {
  const parsed = parsePositiveInt(raw, field);
  if (parsed === undefined) {
    throw badRequest(`"${field}" is required and must be a positive whole number.`);
  }
  return parsed;
}

/** Comma-separated ids -> a de-duplicated number array, order preserved. */
export function parseIdList(raw: unknown, field: string): number[] {
  const value = queryValue(raw);
  if (value === undefined) return [];

  const ids: number[] = [];
  const seen = new Set<number>();
  for (const part of value.split(',')) {
    const text = part.trim();
    if (text.length === 0) continue;
    const id = Number(text);
    if (!Number.isSafeInteger(id) || id <= 0) {
      throw badRequest(
        `Query parameter "${field}" must be a comma-separated list of positive ids; got "${text}".`,
      );
    }
    if (seen.has(id)) continue;
    seen.add(id);
    ids.push(id);
    if (ids.length >= MAX_QUERY_IDS) break;
  }
  return ids;
}

/** YYYY-MM-DD *and* a real calendar day: the provider answers 2026-02-30 with an empty list. */
export function parseDate(raw: unknown, field: string): string | undefined {
  const value = queryValue(raw);
  if (value === undefined) return undefined;
  if (!ISO_DATE.test(value) || toIsoDate(new Date(`${value}T00:00:00Z`)) !== value) {
    throw badRequest(
      `Query parameter "${field}" must be a date in YYYY-MM-DD form; got "${value}".`,
    );
  }
  return value;
}

/** Empty string for an invalid date, which never equals a well-formed input. */
export function toIsoDate(date: Date): string {
  return Number.isNaN(date.getTime()) ? '' : date.toISOString().slice(0, 10);
}

/** UTC, matching the provider's default account timezone. */
export function todayIso(now: Date = new Date()): string {
  return toIsoDate(now);
}

export function addDays(isoDate: string, days: number): string {
  return toIsoDate(new Date(Date.parse(`${isoDate}T00:00:00Z`) + days * DAY_MS));
}

export function requireObjectBody(body: unknown): Record<string, unknown> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw badRequest('Request body must be a JSON object.');
  }
  return body as Record<string, unknown>;
}

/**
 * The FCM registration token is the primary key of a device everywhere in this service, so
 * its shape is checked before it becomes one: printable ASCII only, and nothing long enough
 * to bloat a row or a log line.
 */
export function requireDeviceToken(raw: unknown, field: string = 'token'): string {
  if (typeof raw !== 'string') {
    throw badRequest(`"${field}" is required and must be a string.`);
  }
  const token = raw.trim();
  if (token.length === 0) {
    throw badRequest(`"${field}" must not be empty.`);
  }
  if (token.length > MAX_TOKEN_LENGTH) {
    throw badRequest(`"${field}" must be at most ${MAX_TOKEN_LENGTH} characters.`);
  }
  if (NOT_PRINTABLE_ASCII.test(token)) {
    throw badRequest(`"${field}" must not contain whitespace or control characters.`);
  }
  return token;
}

export function optionalBodyString(
  raw: unknown,
  field: string,
  maxLength: number,
): string | undefined {
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw !== 'string') {
    throw badRequest(`"${field}" must be a string.`);
  }
  const value = raw.trim();
  if (value.length === 0) return undefined;
  if (value.length > maxLength) {
    throw badRequest(`"${field}" must be at most ${maxLength} characters.`);
  }
  return value;
}

/**
 * A missing list is an empty list, since the app omits keys it has nothing for, but a list
 * of the wrong type is a client bug worth reporting. The values themselves are normalised
 * and capped by the store (`normalizeIds`), which is the side that knows the row budget.
 */
export function requireIdArray(raw: unknown, field: string): number[] {
  if (raw === undefined || raw === null) return [];
  if (!Array.isArray(raw)) {
    throw badRequest(`"${field}" must be an array of ids.`);
  }
  return raw.map((entry) => {
    const id = typeof entry === 'string' ? Number(entry) : entry;
    if (typeof id !== 'number' || !Number.isSafeInteger(id)) {
      throw badRequest(`"${field}" must contain whole numbers only; got ${JSON.stringify(entry)}.`);
    }
    return id;
  });
}
