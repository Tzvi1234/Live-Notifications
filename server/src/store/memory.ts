/**
 * In-memory store. Same semantics as the Postgres one, none of its durability: the whole
 * dataset dies with the process, so the dedupe set starts empty on every boot and events
 * already pushed before a restart are pushed again. Fine for tests and local runs, wrong
 * for production — see the warning `createStore` logs when it picks this.
 */

import { randomUUID } from 'node:crypto';

import type { DeviceRecord, SubscriptionRecord, TrackedMatchState } from '../types.js';
import type { Logger } from '../logger.js';
import {
  matchIdFromEventId,
  normalizePreferences,
  normalizeSubscription,
  type DeviceRegistration,
  type MatchTargets,
  type PruneStats,
  type PushTarget,
  type Store,
  type StoreKind,
} from './index.js';

interface SentEvent {
  matchId: number;
  sentAt: number;
}

/** Mirrors the match_state columns; `sentEventIds` is derived, exactly as in Postgres. */
interface MatchStateRow {
  phase: TrackedMatchState['phase'];
  score: TrackedMatchState['score'];
  elapsed: number | undefined;
  lastSequence: number;
  lineupsSent: boolean;
  updatedAt: number;
}

class MemoryStore implements Store {
  readonly kind: StoreKind = 'memory';

  private readonly devices = new Map<string, DeviceRecord>();
  private readonly subscriptions = new Map<string, SubscriptionRecord>();
  private readonly sentEvents = new Map<string, SentEvent>();
  /** Secondary index over sentEvents, so hydrating a match's dedupe set is not a scan. */
  private readonly sentEventsByMatch = new Map<number, Set<string>>();
  private readonly matchStates = new Map<number, MatchStateRow>();
  private readonly logger: Logger;

  constructor(logger: Logger) {
    this.logger = logger;
  }

  async upsertDevice(device: DeviceRegistration): Promise<DeviceRecord> {
    const now = Date.now();
    const existing = this.devices.get(device.token);
    const record: DeviceRecord = {
      token: device.token,
      // A re-register keeps the id the app already stored.
      deviceId: existing?.deviceId ?? randomUUID(),
      platform: device.platform ?? 'android',
      appVersion: device.appVersion ?? undefined,
      timeZone: device.timeZone ?? undefined,
      locale: device.locale ?? undefined,
      createdAt: existing?.createdAt ?? now,
      lastSeenAt: now,
    };
    this.devices.set(record.token, record);
    return { ...record };
  }

  async deleteDevice(token: string): Promise<boolean> {
    this.subscriptions.delete(token);
    return this.devices.delete(token);
  }

  async touchDevice(token: string): Promise<void> {
    const device = this.devices.get(token);
    if (device) device.lastSeenAt = Date.now();
  }

  async putSubscription(subscription: SubscriptionRecord): Promise<void> {
    const normalized = normalizeSubscription(subscription);
    // Matches the Postgres path's placeholder insert: a subscription that arrives before
    // registration must not be dropped.
    if (!this.devices.has(normalized.token)) {
      await this.upsertDevice({ token: normalized.token });
    }
    this.subscriptions.set(normalized.token, normalized);
  }

  async getSubscription(token: string): Promise<SubscriptionRecord | undefined> {
    const record = this.subscriptions.get(token);
    if (!record) return undefined;
    return {
      token: record.token,
      teamIds: [...record.teamIds],
      leagueIds: [...record.leagueIds],
      matchIds: [...record.matchIds],
      preferences: normalizePreferences(record.preferences),
    };
  }

  async tokensForMatch(match: MatchTargets): Promise<PushTarget[]> {
    const targets: PushTarget[] = [];
    for (const subscription of this.subscriptions.values()) {
      if (!this.devices.has(subscription.token)) continue;
      const wanted =
        subscription.matchIds.includes(match.id) ||
        subscription.leagueIds.includes(match.leagueId) ||
        subscription.teamIds.includes(match.home.id) ||
        subscription.teamIds.includes(match.away.id);
      if (!wanted) continue;
      targets.push({
        token: subscription.token,
        preferences: normalizePreferences(subscription.preferences),
      });
    }
    return targets;
  }

  async markEventSent(eventId: string, matchId?: number): Promise<boolean> {
    if (this.sentEvents.has(eventId)) return false;
    const resolvedMatchId = matchId ?? matchIdFromEventId(eventId);
    this.sentEvents.set(eventId, { matchId: resolvedMatchId, sentAt: Date.now() });
    let ids = this.sentEventsByMatch.get(resolvedMatchId);
    if (!ids) {
      ids = new Set<string>();
      this.sentEventsByMatch.set(resolvedMatchId, ids);
    }
    ids.add(eventId);
    // Single-threaded and synchronous between the has() and the set(): no interleaving is
    // possible, so this claim is as exclusive as the ON CONFLICT DO NOTHING it stands in for.
    return true;
  }

  async getMatchState(matchId: number): Promise<TrackedMatchState | undefined> {
    const row = this.matchStates.get(matchId);
    if (!row) return undefined;
    return {
      matchId,
      phase: row.phase,
      score: row.score ? { ...row.score } : undefined,
      elapsed: row.elapsed,
      lastSequence: row.lastSequence,
      sentEventIds: new Set(this.sentEventsByMatch.get(matchId) ?? []),
      lineupsSent: row.lineupsSent,
    };
  }

  async putMatchState(state: TrackedMatchState): Promise<void> {
    const existing = this.matchStates.get(state.matchId);
    this.matchStates.set(state.matchId, {
      phase: state.phase,
      score: state.score ? { ...state.score } : undefined,
      elapsed: state.elapsed,
      // Never rewinds, and lineupsSent latches — same guards as the SQL upsert.
      lastSequence: Math.max(existing?.lastSequence ?? 0, state.lastSequence),
      lineupsSent: (existing?.lineupsSent ?? false) || state.lineupsSent,
      updatedAt: Date.now(),
    });
    // state.sentEventIds is ignored on purpose: markEventSent owns that set.
  }

  async nextSequence(matchId: number): Promise<number> {
    const existing = this.matchStates.get(matchId);
    if (!existing) {
      this.matchStates.set(matchId, {
        phase: 'UNKNOWN',
        score: undefined,
        elapsed: undefined,
        lastSequence: 1,
        lineupsSent: false,
        updatedAt: Date.now(),
      });
      return 1;
    }
    existing.lastSequence += 1;
    existing.updatedAt = Date.now();
    return existing.lastSequence;
  }

  async pruneOlderThan(date: Date): Promise<PruneStats> {
    const cutoff = date.getTime();
    const stats: PruneStats = { devices: 0, subscriptions: 0, sentEvents: 0, matchStates: 0 };

    for (const [eventId, event] of this.sentEvents) {
      if (event.sentAt >= cutoff) continue;
      this.sentEvents.delete(eventId);
      const ids = this.sentEventsByMatch.get(event.matchId);
      if (ids) {
        ids.delete(eventId);
        if (ids.size === 0) this.sentEventsByMatch.delete(event.matchId);
      }
      stats.sentEvents += 1;
    }

    for (const [matchId, row] of this.matchStates) {
      if (row.updatedAt >= cutoff) continue;
      this.matchStates.delete(matchId);
      stats.matchStates += 1;
    }

    for (const [token, device] of this.devices) {
      if (device.lastSeenAt >= cutoff) continue;
      this.devices.delete(token);
      if (this.subscriptions.delete(token)) stats.subscriptions += 1;
      stats.devices += 1;
    }

    return stats;
  }

  async removeTokens(tokens: string[]): Promise<number> {
    let removed = 0;
    for (const token of tokens) {
      this.subscriptions.delete(token);
      if (this.devices.delete(token)) removed += 1;
    }
    return removed;
  }

  /**
   * Always granted. There is nothing to coordinate with: this store is one process's heap,
   * so a second instance would have its own empty copy and could not be excluded anyway.
   * Another reason not to run the in-memory store with more than one instance.
   */
  async acquireLeaderLock(_ttlMs: number): Promise<boolean> {
    return true;
  }

  async releaseLeaderLock(): Promise<void> {
    // Nothing held.
  }

  async close(): Promise<void> {
    this.devices.clear();
    this.subscriptions.clear();
    this.sentEvents.clear();
    this.sentEventsByMatch.clear();
    this.matchStates.clear();
    this.logger.debug('memory store cleared');
  }
}

export function createMemoryStore(logger: Logger): Store {
  return new MemoryStore(logger.child({ component: 'store.memory' }));
}
