-- A cache that survives a restart.
--
-- The provider client already holds one in memory, but Render replaces the instance on
-- every deploy and spins it down when idle, so that cache is cold several times a day and
-- the first request after each wake pays full upstream latency for every key. Worse, it
-- pays it in REQUESTS: the team list for thirty competitions is thirty calls that were
-- already made yesterday and answered identically.
--
-- Rows are keyed exactly as the in-memory cache keys them - `path?sorted&query` - so the
-- two layers are the same cache with different lifetimes, and a miss in one is a lookup in
-- the other rather than a different question.
CREATE TABLE IF NOT EXISTS response_cache (
  cache_key   TEXT        PRIMARY KEY,
  payload     JSONB       NOT NULL,
  -- Past this the value is refreshed on the next call...
  expires_at  TIMESTAMPTZ NOT NULL,
  -- ...but it stays servable until this, for when that refresh fails.
  stale_until TIMESTAMPTZ NOT NULL,
  written_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Sweeping deletes by stale_until, which is the only column ever ranged over.
CREATE INDEX IF NOT EXISTS response_cache_stale_until_idx ON response_cache (stale_until);
