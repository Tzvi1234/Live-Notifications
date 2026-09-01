import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// The module builds and validates the process config on import, so the required key has
// to exist before it is loaded. The value is never used - nothing here calls the provider.
process.env.API_FOOTBALL_KEY ??= 'test-key-for-config-load';
const { loadConfig } = await import('../config.js');

/**
 * The two league lists have to agree.
 *
 * They are written in different languages in different trees, and they drifted once: the
 * server offered fourteen competitions while the app's own-key path offered thirty, so
 * choosing the recommended server silently cost you the Championship, the FA Cup and every
 * Israeli competition below the top flight. A test is the only thing that keeps two hand
 * -maintained lists honest.
 */
const ANDROID_SOURCE = fileURLToPath(
  new URL(
    '../../../android/app/src/main/java/com/tzvi/kickoff/data/repository/ApiFootballDataSource.kt',
    import.meta.url,
  ),
);

function androidFeaturedIds(): number[] {
  const source = readFileSync(ANDROID_SOURCE, 'utf8');
  const block = /val FEATURED_LEAGUE_IDS = setOf\(([\s\S]*?)\n {8}\)/.exec(source);
  assert.ok(block, 'FEATURED_LEAGUE_IDS not found in ApiFootballDataSource.kt');
  return [...(block[1] ?? '').matchAll(/^\s*(\d+),/gm)].map((match) => Number(match[1]));
}

describe('featured league parity', () => {
  test('the server offers exactly what the app’s own-key path offers', () => {
    const server = loadConfig({ API_FOOTBALL_KEY: 'test' }).featuredLeagueIds;
    const android = androidFeaturedIds();

    assert.deepEqual(
      [...server].sort((a, b) => a - b),
      [...android].sort((a, b) => a - b),
    );
  });

  test('the competitions the user asked for are in it', () => {
    const server = loadConfig({ API_FOOTBALL_KEY: 'test' }).featuredLeagueIds;
    const required: Array<[number, string]> = [
      [40, 'England Championship'],
      [45, 'England FA Cup'],
      [48, 'England League Cup'],
      [383, "Israel Ligat ha'Al"],
      [382, 'Israel Liga Leumit'],
      [384, 'Israel State Cup'],
      [140, 'Spain La Liga'],
      [143, 'Spain Copa del Rey'],
    ];
    for (const [id, name] of required) {
      assert.ok(server.includes(id), `${name} (${id}) is missing`);
    }
  });

  test('the deployment blueprint does not freeze the list', () => {
    // This is the failure that made the whole exercise pointless once already: render.yaml
    // pinned FEATURED_LEAGUE_IDS to fourteen ids, config.ts resolves `env ?? default`, so
    // the environment won and every competition added in code was dead on arrival. The
    // constant being right is not the same as the deployment being right.
    const blueprint = readFileSync(
      fileURLToPath(new URL('../../../render.yaml', import.meta.url)),
      'utf8',
    );
    for (const key of ['FEATURED_LEAGUE_IDS', 'FEATURED_LEAGUE_IDS_OVERRIDE']) {
      const pinned = new RegExp(`^\\s*-\\s*key:\\s*${key}\\s*$`, 'm').test(blueprint);
      assert.equal(pinned, false, `render.yaml pins ${key}; it must inherit the code default`);
    }
  });

  test('a stale environment value cannot freeze the list any more', () => {
    // The trap, the second time: the blueprint was fixed but the value it had once written
    // into the Render dashboard stayed there, and env ?? default meant fourteen leagues
    // for ever. The server now reads a new name, so the stale one is inert...
    const stale = loadConfig({ API_FOOTBALL_KEY: 'test', FEATURED_LEAGUE_IDS: '39,140' });
    assert.equal(stale.featuredLeagueIds.length, loadConfig({ API_FOOTBALL_KEY: 'test' }).featuredLeagueIds.length);
    // ...and deviating on purpose is still possible under the name that says so.
    const deliberate = loadConfig({ API_FOOTBALL_KEY: 'test', FEATURED_LEAGUE_IDS_OVERRIDE: '39,140' });
    assert.deepEqual([...deliberate.featuredLeagueIds], [39, 140]);
  });

  test('the same holds for the daily budget', () => {
    // Same dashboard, same mechanism, and this one pinned a 75,000-a-day plan at 7,500.
    assert.equal(loadConfig({ API_FOOTBALL_KEY: 'test', DAILY_REQUEST_BUDGET: '7500' }).dailyRequestBudget, undefined);
    assert.equal(loadConfig({ API_FOOTBALL_KEY: 'test', DAILY_REQUEST_BUDGET_OVERRIDE: '7500' }).dailyRequestBudget, 7500);
  });

  test('the order is the order, and it has no duplicates', () => {
    // pickFeatured uses this list as the app's tab order as well as its filter, so a
    // duplicate would draw a competition twice.
    const server = loadConfig({ API_FOOTBALL_KEY: 'test' }).featuredLeagueIds;
    assert.equal(new Set(server).size, server.length);
    assert.equal(server[0], 39, 'the Premier League leads the list');
  });
});
