-- Migration 002: signed-in accounts and the prediction game.
--
-- Everything in 001 is keyed by an FCM registration token, which is a device, not a person.
-- A person is a Clerk user id, and it is what the game is scoped to: the same account
-- predicting from a phone and a reinstalled phone is one player with one score.

-- Mirror of the Clerk user, written by requireUser on every authenticated request. Clerk
-- owns the identity; this table exists so a group membership, a prediction and a chat line
-- have something local to join against, and so a display name survives a token whose claims
-- do not carry one.
CREATE TABLE IF NOT EXISTS users (
  clerk_user_id TEXT PRIMARY KEY,
  display_name  TEXT,
  avatar_url    TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS groups (
  id          BIGSERIAL PRIMARY KEY,
  name        TEXT        NOT NULL,
  owner_id    TEXT        NOT NULL REFERENCES users (clerk_user_id) ON DELETE CASCADE,
  -- The whole of the join flow: a friend who has the code is allowed in. Unique so a
  -- colliding mint is rejected by the database rather than silently joining the wrong group.
  invite_code TEXT        NOT NULL UNIQUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS groups_owner_id_idx ON groups (owner_id);

CREATE TABLE IF NOT EXISTS group_members (
  group_id  BIGINT      NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
  user_id   TEXT        NOT NULL REFERENCES users (clerk_user_id) ON DELETE CASCADE,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, user_id)
);

-- "Which groups am I in?" is the first query every authenticated screen makes.
CREATE INDEX IF NOT EXISTS group_members_user_id_idx ON group_members (user_id);

-- Rows, not arrays, unlike subscriptions.league_ids: these are never tested for overlap
-- against a fixture, only listed for one group at a time, so the ordinary index wins and
-- the FK keeps them from outliving the group.
CREATE TABLE IF NOT EXISTS group_leagues (
  group_id  BIGINT  NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
  league_id INTEGER NOT NULL,
  PRIMARY KEY (group_id, league_id)
);

CREATE TABLE IF NOT EXISTS group_teams (
  group_id BIGINT  NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
  team_id  INTEGER NOT NULL,
  PRIMARY KEY (group_id, team_id)
);

-- THE KICK-OFF LOCK.
--
-- kickoff_at is a snapshot of the fixture's kick-off taken from the provider when the row
-- is written, and it is here rather than being looked up because it is what makes both
-- rules of the game enforceable in SQL rather than in a handler:
--
--   * a write is refused once `kickoff_at <= now()`   (predictions are final at kick-off);
--   * a read of somebody else's row is filtered out until `kickoff_at <= now()`.
--
-- The second one is the reason it must be a column: a WHERE clause cannot consult the
-- provider. A route that forgot the check would still be unable to leak a prediction.
--
-- points/exact/outcome are NULL until the fixture is settled by the poller, which is also
-- what `fixturesAwaitingSettlement` keys off.
CREATE TABLE IF NOT EXISTS predictions (
  group_id   BIGINT      NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
  fixture_id BIGINT      NOT NULL,
  user_id    TEXT        NOT NULL REFERENCES users (clerk_user_id) ON DELETE CASCADE,
  home       SMALLINT    NOT NULL,
  away       SMALLINT    NOT NULL,
  kickoff_at TIMESTAMPTZ NOT NULL,
  points     SMALLINT,
  exact      BOOLEAN,
  outcome    BOOLEAN,
  settled_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, fixture_id, user_id)
);

-- The settlement sweep asks one question every tick — "which fixtures have unscored
-- predictions and have had time to finish?" — and this partial index is the whole answer,
-- so it stays small however many settled predictions accumulate behind it.
CREATE INDEX IF NOT EXISTS predictions_unsettled_idx
  ON predictions (kickoff_at)
  WHERE points IS NULL;

-- Leaderboard: one pass per member of one group.
CREATE INDEX IF NOT EXISTS predictions_group_user_idx ON predictions (group_id, user_id);

CREATE TABLE IF NOT EXISTS group_messages (
  id         BIGSERIAL PRIMARY KEY,
  group_id   BIGINT      NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
  user_id    TEXT        NOT NULL REFERENCES users (clerk_user_id) ON DELETE CASCADE,
  text       TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Both chat queries are "this group, since this instant": the 24-hour read and the
-- per-user rate-limit count.
CREATE INDEX IF NOT EXISTS group_messages_group_created_idx
  ON group_messages (group_id, created_at DESC);
CREATE INDEX IF NOT EXISTS group_messages_user_created_idx
  ON group_messages (group_id, user_id, created_at DESC);
