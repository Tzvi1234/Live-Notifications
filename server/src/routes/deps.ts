/**
 * What every router is handed. Declared here rather than in app.ts so that a router never
 * imports the module that mounts it.
 */

import type { KickoffConfig } from '../config.js';
import type { Logger } from '../logger.js';
import type { ApiFootballClient } from '../provider/apiFootball.js';
import type { Store } from '../store/index.js';

/**
 * The poller as the HTTP layer sees it: something that can be asked how it is doing and
 * told to run one tick now. Deliberately structural and untyped in its returns — the admin
 * route serialises the status verbatim, so the poller can add fields without touching this.
 */
export interface PollerHandle {
  getStatus(): unknown;
  /** Runs one poll immediately, outside the schedule; resolves with the poller's own summary. */
  pollOnce(): Promise<unknown>;
}

export interface ApiDeps {
  readonly config: KickoffConfig;
  readonly store: Store;
  readonly provider: ApiFootballClient;
  readonly logger: Logger;
  /** Absent when POLL_ENABLED is false: the REST surface works either way. */
  readonly poller?: PollerHandle | undefined;
}
