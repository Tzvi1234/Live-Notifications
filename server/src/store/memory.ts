/**
 * In-memory store. Same semantics as the Postgres one, none of its durability: the whole
 * dataset dies with the process, so the dedupe set starts empty on every boot and events
 * already pushed before a restart are pushed again. Fine for tests and local runs, wrong
 * for production — see the warning `createStore` logs when it picks this.
 */

import { randomUUID } from 'node:crypto';

import { scorePrediction } from '../game/scoring.js';
import type {
  DeviceRecord,
  GroupMemberRecord,
  GroupMessageRecord,
  GroupRecord,
  LeaderboardRow,
  PredictionRecord,
  ScoreJson,
  SubscriptionRecord,
  TrackedMatchState,
  UserRecord,
} from '../types.js';
import type { Logger } from '../logger.js';
import {
  matchIdFromEventId,
  normalizePreferences,
  normalizeSubscription,
  type CreateGroupInput,
  type DeviceRegistration,
  type ListPredictionsInput,
  type MatchTargets,
  type PostGroupMessageInput,
  type PruneStats,
  type PushTarget,
  type PutPredictionInput,
  type Store,
  type StoreKind,
  type UpdateGroupPatch,
  type UserProfilePatch,
  type UserProfileSeed,
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

/** The `groups` row; membership, leagues and teams are the tables keyed off it. */
interface GroupRow {
  id: number;
  name: string;
  ownerId: string;
  inviteCode: string;
  leagueIds: number[];
  teamIds: number[];
  createdAt: number;
}

/** One `predictions` row. `memberKey` is its (group, fixture, user) primary key. */
interface PredictionRow {
  groupId: number;
  fixtureId: number;
  userId: string;
  home: number;
  away: number;
  kickoffAt: number;
  points: number | undefined;
  exact: boolean | undefined;
  correctOutcome: boolean | undefined;
  updatedAt: number;
}

interface MessageRow {
  id: number;
  groupId: number;
  userId: string;
  text: string;
  createdAt: number;
}

function predictionKey(groupId: number, fixtureId: number, userId: string): string {
  return `${groupId}:${fixtureId}:${userId}`;
}

function memberKey(groupId: number, userId: string): string {
  return `${groupId}:${userId}`;
}

class MemoryStore implements Store {
  readonly kind: StoreKind = 'memory';

  private readonly devices = new Map<string, DeviceRecord>();
  private readonly subscriptions = new Map<string, SubscriptionRecord>();
  private readonly sentEvents = new Map<string, SentEvent>();
  /** Secondary index over sentEvents, so hydrating a match's dedupe set is not a scan. */
  private readonly sentEventsByMatch = new Map<number, Set<string>>();
  private readonly matchStates = new Map<number, MatchStateRow>();

  private readonly users = new Map<string, UserRecord>();
  private readonly groups = new Map<number, GroupRow>();
  /** `${groupId}:${userId}` -> joinedAt, standing in for the group_members primary key. */
  private readonly members = new Map<string, number>();
  private readonly predictions = new Map<string, PredictionRow>();
  private readonly messages: MessageRow[] = [];
  /** BIGSERIAL, by hand. */
  private nextGroupId = 1;
  private nextMessageId = 1;

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
    // Resolved before the membership check so a malformed id fails the same way in both
    // stores, rather than only on the call that happens to be the first for that id.
    const resolvedMatchId = matchId ?? matchIdFromEventId(eventId);
    if (this.sentEvents.has(eventId)) return false;
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

  /* ---------------------------------------------------------------------- */
  /* Accounts                                                                */
  /* ---------------------------------------------------------------------- */

  async upsertUser(clerkUserId: string, profile?: UserProfileSeed | undefined): Promise<UserRecord> {
    const now = Date.now();
    const existing = this.users.get(clerkUserId);
    const record: UserRecord = {
      clerkUserId,
      // Fill in only; the same COALESCE the SQL upsert uses, and for the same reason —
      // a claim must never overwrite what PATCH /v1/me set.
      displayName: existing?.displayName ?? profile?.displayName,
      avatarUrl: existing?.avatarUrl ?? profile?.avatarUrl,
      createdAt: existing?.createdAt ?? now,
      lastSeenAt: now,
    };
    this.users.set(clerkUserId, record);
    return { ...record };
  }

  async getUser(clerkUserId: string): Promise<UserRecord | undefined> {
    const record = this.users.get(clerkUserId);
    return record ? { ...record } : undefined;
  }

  async updateUserProfile(
    clerkUserId: string,
    patch: UserProfilePatch,
  ): Promise<UserRecord | undefined> {
    const existing = this.users.get(clerkUserId);
    if (!existing) return undefined;
    const updated: UserRecord = {
      ...existing,
      displayName:
        patch.displayName === undefined ? existing.displayName : (patch.displayName ?? undefined),
      avatarUrl: patch.avatarUrl === undefined ? existing.avatarUrl : (patch.avatarUrl ?? undefined),
      lastSeenAt: Date.now(),
    };
    this.users.set(clerkUserId, updated);
    return { ...updated };
  }

  /* ---------------------------------------------------------------------- */
  /* Groups                                                                  */
  /* ---------------------------------------------------------------------- */

  async createGroup(input: CreateGroupInput): Promise<GroupRecord> {
    const row: GroupRow = {
      id: this.nextGroupId++,
      name: input.name,
      ownerId: input.ownerId,
      inviteCode: input.inviteCode,
      leagueIds: [...input.leagueIds],
      teamIds: [...input.teamIds],
      createdAt: Date.now(),
    };
    this.groups.set(row.id, row);
    // The owner is a member like anyone else, so every membership check has one answer.
    this.members.set(memberKey(row.id, row.ownerId), row.createdAt);
    return this.toGroupRecord(row);
  }

  async getGroup(groupId: number): Promise<GroupRecord | undefined> {
    const row = this.groups.get(groupId);
    return row ? this.toGroupRecord(row) : undefined;
  }

  async getGroupByInviteCode(inviteCode: string): Promise<GroupRecord | undefined> {
    for (const row of this.groups.values()) {
      if (row.inviteCode === inviteCode) return this.toGroupRecord(row);
    }
    return undefined;
  }

  async listGroupsForUser(userId: string): Promise<GroupRecord[]> {
    const records: GroupRecord[] = [];
    for (const row of this.groups.values()) {
      if (this.members.has(memberKey(row.id, userId))) records.push(this.toGroupRecord(row));
    }
    return records.sort((a, b) => a.createdAt - b.createdAt || a.id - b.id);
  }

  async updateGroup(groupId: number, patch: UpdateGroupPatch): Promise<GroupRecord | undefined> {
    const row = this.groups.get(groupId);
    if (!row) return undefined;
    if (patch.name !== undefined) row.name = patch.name;
    if (patch.leagueIds !== undefined) row.leagueIds = [...patch.leagueIds];
    if (patch.teamIds !== undefined) row.teamIds = [...patch.teamIds];
    return this.toGroupRecord(row);
  }

  async deleteGroup(groupId: number): Promise<boolean> {
    if (!this.groups.delete(groupId)) return false;
    // Standing in for ON DELETE CASCADE.
    for (const key of [...this.members.keys()]) {
      if (key.startsWith(`${groupId}:`)) this.members.delete(key);
    }
    for (const [key, row] of this.predictions) {
      if (row.groupId === groupId) this.predictions.delete(key);
    }
    for (let index = this.messages.length - 1; index >= 0; index -= 1) {
      if (this.messages[index]!.groupId === groupId) this.messages.splice(index, 1);
    }
    return true;
  }

  async listGroupMembers(groupId: number): Promise<GroupMemberRecord[]> {
    const group = this.groups.get(groupId);
    if (!group) return [];
    const members: GroupMemberRecord[] = [];
    for (const [key, joinedAt] of this.members) {
      if (!key.startsWith(`${groupId}:`)) continue;
      const userId = key.slice(String(groupId).length + 1);
      const user = this.users.get(userId);
      members.push({
        userId,
        displayName: user?.displayName,
        avatarUrl: user?.avatarUrl,
        joinedAt,
        isOwner: userId === group.ownerId,
      });
    }
    // Owner first, then by when they joined — the order the Postgres query produces.
    return members.sort(
      (a, b) => Number(b.isOwner) - Number(a.isOwner) || a.joinedAt - b.joinedAt,
    );
  }

  async isGroupMember(groupId: number, userId: string): Promise<boolean> {
    return this.members.has(memberKey(groupId, userId));
  }

  async addGroupMember(groupId: number, userId: string): Promise<boolean> {
    const key = memberKey(groupId, userId);
    if (this.members.has(key)) return false;
    this.members.set(key, Date.now());
    return true;
  }

  async removeGroupMember(groupId: number, userId: string): Promise<boolean> {
    return this.members.delete(memberKey(groupId, userId));
  }

  /* ---------------------------------------------------------------------- */
  /* Predictions                                                             */
  /* ---------------------------------------------------------------------- */

  async putPrediction(input: PutPredictionInput): Promise<PredictionRecord | undefined> {
    // The same condition the SQL upsert carries in its WHERE clause: the store refuses a
    // late write on its own, so a route that skipped the 409 still cannot record one.
    if (input.kickoffAt.getTime() <= input.now.getTime()) return undefined;

    const key = predictionKey(input.groupId, input.fixtureId, input.userId);
    const existing = this.predictions.get(key);
    if (existing !== undefined && existing.kickoffAt <= input.now.getTime()) return undefined;

    const row: PredictionRow = {
      groupId: input.groupId,
      fixtureId: input.fixtureId,
      userId: input.userId,
      home: input.home,
      away: input.away,
      kickoffAt: input.kickoffAt.getTime(),
      points: undefined,
      exact: undefined,
      correctOutcome: undefined,
      updatedAt: input.now.getTime(),
    };
    this.predictions.set(key, row);
    return this.toPredictionRecord(row);
  }

  async listPredictions(input: ListPredictionsInput): Promise<PredictionRecord[]> {
    const wanted = new Set(input.fixtureIds);
    const now = input.now.getTime();
    const visible: PredictionRecord[] = [];
    for (const row of this.predictions.values()) {
      if (row.groupId !== input.groupId || !wanted.has(row.fixtureId)) continue;
      // Mirrors `(p.user_id = $3 OR p.kickoff_at <= now())`: another member's prediction is
      // not read at all before kick-off, rather than read and then hidden.
      if (row.userId !== input.viewerId && row.kickoffAt > now) continue;
      visible.push(this.toPredictionRecord(row));
    }
    return visible.sort(
      (a, b) => a.fixtureId - b.fixtureId || a.userId.localeCompare(b.userId),
    );
  }

  async leaderboard(groupId: number): Promise<LeaderboardRow[]> {
    const group = this.groups.get(groupId);
    if (!group) return [];

    const rows = new Map<string, LeaderboardRow>();
    for (const member of await this.listGroupMembers(groupId)) {
      rows.set(member.userId, {
        userId: member.userId,
        displayName: member.displayName,
        avatarUrl: member.avatarUrl,
        points: 0,
        exactCount: 0,
        correctOutcomeCount: 0,
        settledCount: 0,
      });
    }

    for (const prediction of this.predictions.values()) {
      if (prediction.groupId !== groupId || prediction.points === undefined) continue;
      const row = rows.get(prediction.userId);
      // A member who left keeps their rows but leaves the board, as the JOIN in SQL does.
      if (!row) continue;
      row.points += prediction.points;
      row.settledCount += 1;
      if (prediction.exact === true) row.exactCount += 1;
      if (prediction.correctOutcome === true) row.correctOutcomeCount += 1;
    }

    return [...rows.values()].sort(
      (a, b) =>
        b.points - a.points ||
        b.exactCount - a.exactCount ||
        (a.displayName ?? a.userId).localeCompare(b.displayName ?? b.userId),
    );
  }

  async fixturesAwaitingSettlement(
    notBefore: Date,
    notAfter: Date,
    limit: number,
  ): Promise<number[]> {
    const from = notBefore.getTime();
    const to = notAfter.getTime();
    const due = new Map<number, number>();
    for (const row of this.predictions.values()) {
      if (row.points !== undefined) continue;
      if (row.kickoffAt < from || row.kickoffAt > to) continue;
      due.set(row.fixtureId, Math.min(due.get(row.fixtureId) ?? row.kickoffAt, row.kickoffAt));
    }
    // Oldest kick-off first, so a backlog drains in the order the matches were played.
    return [...due.entries()]
      .sort((a, b) => a[1] - b[1])
      .slice(0, limit)
      .map(([fixtureId]) => fixtureId);
  }

  async settleFixture(fixtureId: number, finalScore: ScoreJson): Promise<number> {
    let settled = 0;
    for (const row of this.predictions.values()) {
      if (row.fixtureId !== fixtureId || row.points !== undefined) continue;
      const score = scorePrediction({ home: row.home, away: row.away }, finalScore);
      row.points = score.points;
      row.exact = score.exact;
      row.correctOutcome = score.correctOutcome;
      row.updatedAt = Date.now();
      settled += 1;
    }
    return settled;
  }

  async rescheduleFixture(fixtureId: number, kickoffAt: Date): Promise<number> {
    let moved = 0;
    for (const row of this.predictions.values()) {
      if (row.fixtureId !== fixtureId || row.points !== undefined) continue;
      row.kickoffAt = kickoffAt.getTime();
      row.updatedAt = Date.now();
      moved += 1;
    }
    return moved;
  }

  /* ---------------------------------------------------------------------- */
  /* Chat                                                                    */
  /* ---------------------------------------------------------------------- */

  async postGroupMessage(input: PostGroupMessageInput): Promise<GroupMessageRecord> {
    const row: MessageRow = {
      id: this.nextMessageId++,
      groupId: input.groupId,
      userId: input.userId,
      text: input.text,
      createdAt: Date.now(),
    };
    this.messages.push(row);
    return this.toMessageRecord(row);
  }

  async listGroupMessages(groupId: number, since: Date): Promise<GroupMessageRecord[]> {
    const cutoff = since.getTime();
    return this.messages
      .filter((row) => row.groupId === groupId && row.createdAt >= cutoff)
      .map((row) => this.toMessageRecord(row));
  }

  async countRecentGroupMessages(groupId: number, userId: string, since: Date): Promise<number> {
    const cutoff = since.getTime();
    return this.messages.filter(
      (row) => row.groupId === groupId && row.userId === userId && row.createdAt >= cutoff,
    ).length;
  }

  private toGroupRecord(row: GroupRow): GroupRecord {
    let memberCount = 0;
    for (const key of this.members.keys()) {
      if (key.startsWith(`${row.id}:`)) memberCount += 1;
    }
    return {
      id: row.id,
      name: row.name,
      ownerId: row.ownerId,
      inviteCode: row.inviteCode,
      leagueIds: [...row.leagueIds],
      teamIds: [...row.teamIds],
      memberCount,
      createdAt: row.createdAt,
    };
  }

  private toPredictionRecord(row: PredictionRow): PredictionRecord {
    const user = this.users.get(row.userId);
    return {
      groupId: row.groupId,
      fixtureId: row.fixtureId,
      userId: row.userId,
      displayName: user?.displayName,
      avatarUrl: user?.avatarUrl,
      home: row.home,
      away: row.away,
      kickoffAt: row.kickoffAt,
      points: row.points,
      exact: row.exact,
      correctOutcome: row.correctOutcome,
      updatedAt: row.updatedAt,
    };
  }

  private toMessageRecord(row: MessageRow): GroupMessageRecord {
    const user = this.users.get(row.userId);
    return {
      id: row.id,
      groupId: row.groupId,
      userId: row.userId,
      displayName: user?.displayName,
      avatarUrl: user?.avatarUrl,
      text: row.text,
      createdAt: row.createdAt,
    };
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
    this.users.clear();
    this.groups.clear();
    this.members.clear();
    this.predictions.clear();
    this.messages.length = 0;
    this.logger.debug('memory store cleared');
  }
}

export function createMemoryStore(logger: Logger): Store {
  return new MemoryStore(logger.child({ component: 'store.memory' }));
}
