-- Kickoff backend schema. Applied on every boot by src/store/postgres.ts, so every
-- statement must be idempotent (IF NOT EXISTS) and safe to run against a live database
-- while the previous instance is still serving during a Render deploy.

-- A device is identified by its FCM registration token, not by device_id: the token is
-- what FCM accepts, what comes back as unregistered when the app is uninstalled, and the
-- only value the client is guaranteed to still have after a reinstall wipes local state.
-- device_id is a server-minted opaque id handed back to the app for its own logging.
CREATE TABLE IF NOT EXISTS devices (
  token        TEXT PRIMARY KEY,
  device_id    UUID        NOT NULL,
  platform     TEXT        NOT NULL DEFAULT 'android',
  app_version  TEXT,
  time_zone    TEXT,
  locale       TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Retirement scan for pruneOlderThan().
CREATE INDEX IF NOT EXISTS devices_last_seen_at_idx ON devices (last_seen_at);

-- One row per device. The three id arrays are OR-ed at query time, so a device that
-- follows a league and one extra fixture needs no extra rows.
CREATE TABLE IF NOT EXISTS subscriptions (
  token       TEXT PRIMARY KEY REFERENCES devices (token) ON DELETE CASCADE,
  team_ids    INTEGER[]   NOT NULL DEFAULT '{}',
  league_ids  INTEGER[]   NOT NULL DEFAULT '{}',
  -- BIGINT because the client contract types matchIds as long, even though provider
  -- fixture ids are currently well inside INTEGER range.
  match_ids   BIGINT[]    NOT NULL DEFAULT '{}',
  preferences JSONB       NOT NULL DEFAULT '{}'::jsonb,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only the array-overlap operator (&&) can consult a GIN index; `id = ANY(column)` is
-- planned as a sequential scan over every subscription no matter how many exist. Verified
-- on PG16: `&&` gives BitmapOr over these three indexes, `= ANY` gives Seq Scan. Whether
-- the planner then prefers the index is its call, but tokensForMatch() must keep using `&&`
-- so the choice exists at all.
CREATE INDEX IF NOT EXISTS subscriptions_team_ids_idx   ON subscriptions USING GIN (team_ids);
CREATE INDEX IF NOT EXISTS subscriptions_league_ids_idx ON subscriptions USING GIN (league_ids);
CREATE INDEX IF NOT EXISTS subscriptions_match_ids_idx  ON subscriptions USING GIN (match_ids);

-- THE DEDUPE INVARIANT.
--
-- event_id is the primary key and is deterministic:
--   "<matchId>:<TYPE>:<minute ?? -1>:<teamId ?? -1>:<playerName ?? ''>"
-- (identical to eventId() in src/types.ts and to MatchEvent.key() on Android).
--
-- The provider ships no event ids and re-reports the same incident as minutes are
-- corrected or VAR resolves, and two instances overlap during a deploy. So "have we
-- already notified about this?" is answered by a uniqueness constraint rather than by
-- application logic: the push path does
--   INSERT ... ON CONFLICT (event_id) DO NOTHING RETURNING event_id
-- and pushes only when a row came back. The database, not the poller, decides the winner,
-- which is what makes the answer correct across concurrent instances.
--
-- Corollary: never UPDATE or re-insert a row here to "refresh" an event, and never delete
-- a row for a match that could still be in play — either would re-arm a duplicate push.
CREATE TABLE IF NOT EXISTS sent_events (
  event_id TEXT PRIMARY KEY,
  match_id BIGINT      NOT NULL,
  sent_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- getMatchState() rebuilds a match's in-flight dedupe set from this index.
CREATE INDEX IF NOT EXISTS sent_events_match_id_idx ON sent_events (match_id);
-- Retention sweep only; see pruneOlderThan().
CREATE INDEX IF NOT EXISTS sent_events_sent_at_idx  ON sent_events (sent_at);

-- Last observed state per tracked match: what the poller diffs the next fetch against,
-- and the monotonic sequence the client uses to discard out-of-order detail payloads.
CREATE TABLE IF NOT EXISTS match_state (
  match_id      BIGINT PRIMARY KEY,
  phase         TEXT        NOT NULL DEFAULT 'UNKNOWN',
  score_home    INTEGER,
  score_away    INTEGER,
  elapsed       INTEGER,
  -- Never decreases: nextSequence() increments it in one statement and putMatchState()
  -- raises it with GREATEST, so a stale writer cannot rewind a client past a payload it
  -- already holds.
  last_sequence INTEGER     NOT NULL DEFAULT 0,
  lineups_sent  BOOLEAN     NOT NULL DEFAULT FALSE,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS match_state_updated_at_idx ON match_state (updated_at);
