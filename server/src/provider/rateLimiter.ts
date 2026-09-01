/**
 * A token bucket in front of the provider, so a burst can never become a 429.
 *
 * The client already backed off once the provider's headers said the minute window was
 * nearly spent, but that is a reaction: it only learns the window is tight from a response
 * that has already been issued, which means the burst that filled it has already happened.
 * A bucket paces the calls before they go out, so the window is never filled in the first
 * place - and a caller that arrives when there is nothing left waits its turn rather than
 * being refused.
 *
 * Two dimensions, because the provider enforces two:
 *  - PER MINUTE, which is a wall you can wait out. Callers queue on it.
 *  - PER DAY, which is a wall you cannot. That one stays where it was, in the client's
 *    own budget counter, because waiting eight hours for midnight is not a thing a request
 *    handler can do.
 */

/** How much of the plan's per-minute allowance this limiter will actually spend. */
const DEFAULT_HEADROOM = 0.8;

/** Used until the provider has told us what the real limit is. */
const DEFAULT_PER_MINUTE = 240;

const MINUTE_MS = 60_000;

export interface RateLimiterOptions {
  /** Requests per minute. Omit to start at a conservative default and learn the real one. */
  perMinute?: number | undefined;
  /** Fraction of the allowance to spend. The rest absorbs anything else on the same key. */
  headroom?: number | undefined;
  /** Injectable clock and timer, so the queueing behaviour is testable without waiting. */
  now?: (() => number) | undefined;
  sleep?: ((ms: number) => Promise<void>) | undefined;
}

export interface RateLimiterState {
  perMinute: number;
  available: number;
  waiting: number;
}

export class RateLimiter {
  #capacity: number;
  #tokens: number;
  #lastRefill: number;
  #waiting = 0;
  readonly #headroom: number;
  readonly #now: () => number;
  readonly #sleep: (ms: number) => Promise<void>;

  /** Serialises the waiters so they leave in the order they arrived. */
  #queue: Promise<void> = Promise.resolve();

  constructor(options: RateLimiterOptions = {}) {
    this.#headroom = options.headroom ?? DEFAULT_HEADROOM;
    this.#now = options.now ?? Date.now;
    this.#sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
    this.#capacity = Math.max(1, Math.floor((options.perMinute ?? DEFAULT_PER_MINUTE) * this.#headroom));
    this.#tokens = this.#capacity;
    this.#lastRefill = this.#now();
  }

  /**
   * Adopts the provider's own per-minute allowance once it has stated one.
   *
   * Widening takes effect at once - there is no reason to keep throttling below a limit
   * the account has been raised past. Narrowing does too, but never below one token, or
   * the limiter would deadlock every caller.
   */
  observeLimit(perMinute: number | undefined): void {
    if (perMinute === undefined || perMinute <= 0) return;
    const next = Math.max(1, Math.floor(perMinute * this.#headroom));
    if (next === this.#capacity) return;
    // Grant the difference immediately when widening, so an upgraded plan is usable within
    // the same minute rather than after the next refill.
    if (next > this.#capacity) this.#tokens += next - this.#capacity;
    this.#capacity = next;
    this.#tokens = Math.min(this.#tokens, next);
  }

  getState(): RateLimiterState {
    return {
      perMinute: this.#capacity,
      available: Math.floor(this.#refilled()),
      waiting: this.#waiting,
    };
  }

  /**
   * Waits until a request may be issued.
   *
   * Callers are served in arrival order: each one chains onto the last, so a burst of
   * fifty is paced out rather than all fifty racing for the same token and half of them
   * spinning. The chain is the only shared state, which is what keeps this correct under
   * concurrency without a lock.
   */
  async acquire(): Promise<void> {
    this.#waiting += 1;
    const mine = this.#queue.then(() => this.#take());
    // Swallow on the chain itself: one caller's failure must not reject every caller
    // queued behind it. The error still reaches the caller through `mine`.
    this.#queue = mine.then(
      () => undefined,
      () => undefined,
    );
    try {
      await mine;
    } finally {
      this.#waiting -= 1;
    }
  }

  async #take(): Promise<void> {
    for (;;) {
      this.#tokens = this.#refilled();
      this.#lastRefill = this.#now();
      if (this.#tokens >= 1) {
        this.#tokens -= 1;
        return;
      }
      // Long enough for exactly one token to appear, so a queue drains at the refill rate
      // rather than in a spin.
      await this.#sleep(Math.ceil(MINUTE_MS / this.#capacity));
    }
  }

  /** Tokens continuously rather than in windows: sixty seconds' worth per minute. */
  #refilled(): number {
    const elapsed = this.#now() - this.#lastRefill;
    if (elapsed <= 0) return this.#tokens;
    const gained = (elapsed / MINUTE_MS) * this.#capacity;
    return Math.min(this.#capacity, this.#tokens + gained);
  }
}
