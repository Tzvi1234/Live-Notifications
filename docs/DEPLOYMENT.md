# Deploying Kickoff

Everything you need to get the backend live on Render and the app talking to it, with the
exact name of every environment variable and where each value comes from.

Work through it in order — each part depends on the one before.

---

## 0. What you are building

One always-on Render **web service** that serves the app's REST API *and* runs the live
poller in-process, plus a **Postgres** database for subscriptions and event de-duplication.

**Render's free tier cannot host this.** Not a preference — three hard limits:

- free web services **spin down after 15 idle minutes**, which kills the poll loop;
- free instances **are not available for background workers or cron jobs at all**;
- a free Postgres **expires 30 days after creation**.

So the poller lives inside the single paid web service, guarded by a Postgres advisory
lock so that an overlapping deploy cannot double-send notifications. Budget roughly
**$7/month** for the smallest always-on instance plus a paid Postgres.

---

## 1. Get an API-Football key

1. Go to **<https://dashboard.api-football.com/register>** and create an account.
2. Open the dashboard; your key is on the **Profile** page, labelled **API Key**.
   It looks like `a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6`.
3. Note your plan's daily quota. **Free is 100 requests/day**, resets at 00:00 UTC.

> **Which plan do you need?** The poller makes one `fixtures?live=all` request per tick
> covering *every* in-play match, plus one events request per match you actually have
> subscribers for. At the default 30-second cadence a single busy Saturday afternoon runs
> to a few thousand requests. **Free is a development key only.** The $19/month Pro plan
> (7,500 req/day) is the realistic floor for real users. Set `DAILY_REQUEST_BUDGET` to
> match your plan and the poller will back off instead of getting cut off mid-match.

> Sign up direct at api-football.com rather than through RapidAPI: same data, no
> marketplace markup, and the header name differs (`x-apisports-key` vs `x-rapidapi-key`).
> This project uses the direct API.

---

## 2. Create a Firebase project (for push)

Push is optional — the app works in polling mode without it — but it is what makes a goal
arrive in seconds.

1. **<https://console.firebase.google.com/>** → **Add project**. Analytics is not needed.
2. **Project settings → General → Your apps → Add app → Android.**
   - **Android package name:** `com.tzvi.kickoff`
     (for a debug build it is `com.tzvi.kickoff.debug` — add that as a second app, or
     build release).
   - Download **`google-services.json`** and put it at
     **`android/app/google-services.json`**. The Gradle build detects it and wires push up;
     with no file, the app simply builds without push.
3. **Project settings → Service accounts → Generate new private key.** This downloads a
   JSON file — this is the server's credential. Keep it out of git.
4. **Project settings → General** — note the **Project ID** (e.g. `kickoff-4f2a1`).

---

## 3. Deploy to Render

### 3a. Push this repository to GitHub

Render deploys from a Git repository. Make sure `render.yaml` is committed at the
repository root — that is where Render looks for a Blueprint by default.

### 3b. Create the services from the blueprint

1. **<https://dashboard.render.com/>** → **New → Blueprint**.
2. Connect the repository. Render reads **`render.yaml`** and proposes:
   - `kickoff-api` — a Node web service
   - `kickoff-db` — a Postgres database
3. Render will prompt for every secret marked `sync: false`. Fill them in from the table in
   §4 below.
4. **Apply**. The first build runs `npm ci --include=dev && npm run build`, then `npm start`.
   (`--include=dev` is not optional: Render exposes `NODE_ENV=production` to the build as
   well as the runtime, and npm skips devDependencies — typescript among them — when it is
   set, so a plain `npm ci` fails the build on `tsc: not found`.)

`DATABASE_URL` is wired automatically from `kickoff-db` — you do not set it by hand.

### 3c. Upload the Firebase service account as a Secret File

Do **not** paste the service-account JSON into an ordinary environment variable: the PEM
private key inside it contains newlines that env vars mangle.

1. Open the **kickoff-api** service → **Environment** → scroll to **Secret Files** →
   **Add Secret File**.
2. **Filename:** `firebase-service-account.json`
3. **Contents:** paste the entire JSON file from §2 step 3.
4. Save. Render mounts it at **`/etc/secrets/firebase-service-account.json`**, which is
   exactly what `GOOGLE_APPLICATION_CREDENTIALS` points at.

> Prefer a base64 env var instead? Set `FIREBASE_SERVICE_ACCOUNT_B64` to
> `base64 -w0 service-account.json` and leave `GOOGLE_APPLICATION_CREDENTIALS` unset. The
> server accepts either. The Secret File is cleaner.

### 3d. Check it came up

```bash
curl https://<your-service>.onrender.com/v1/health
```

```json
{ "ok": true, "version": "1.0.0", "provider": "api-football", "pollingEnabled": true }
```

If `ok` is false, open **Logs** on the service — the server logs its effective
configuration at startup with secrets redacted, which usually names the missing value.

---

## 4. Environment variables — the exact names

Set these on the **kickoff-api** service (**Environment → Environment Variables**).

### Required

| Name | Where the value comes from | Example |
|---|---|---|
| `API_FOOTBALL_KEY` | §1 — API-Football dashboard → Profile → API Key | `a1b2c3d4e5f6…` |

### Provided by Render — do not set by hand

| Name | Source |
|---|---|
| `PORT` | Injected by Render. The server binds `0.0.0.0:$PORT`. |
| `DATABASE_URL` | Wired from `kickoff-db` by the blueprint's `fromDatabase` block. |
| `NODE_VERSION` | Set to `22` by the blueprint. It outranks every other source, so pinning it here stops Render resolving `engines: ">=22"` to a newer major than `@types/node` describes. |

### Push (set these to enable FCM)

| Name | Where the value comes from | Example |
|---|---|---|
| `GOOGLE_APPLICATION_CREDENTIALS` | Path to the Secret File from §3c | `/etc/secrets/firebase-service-account.json` |
| `FIREBASE_PROJECT_ID` | §2 step 4 — Firebase → Project settings → General | `kickoff-4f2a1` |
| `FIREBASE_SERVICE_ACCOUNT_B64` | *Alternative* to `GOOGLE_APPLICATION_CREDENTIALS`: base64 of the same JSON | `ewogICJ0eXBl…` |

### Tuning — all optional, sensible defaults

| Name | Default | What it does |
|---|---|---|
| `NODE_ENV` | `production` | Standard Node environment flag. |
| `LOG_LEVEL` | `info` | `debug` \| `info` \| `warn` \| `error`. |
| `API_FOOTBALL_BASE_URL` | `https://v3.football.api-sports.io` | Change only to point at a mock. |
| `POLL_ENABLED` | `true` | Set `false` to run a REST-only instance with no poller. |
| `POLL_INTERVAL_SECONDS` | `30` | Cadence while at least one tracked match is in play. |
| `POLL_IDLE_INTERVAL_SECONDS` | `300` | Cadence when nothing is live. Keeps the quota for match days. |
| `PREMATCH_LEAD_MINUTES` | `60` | How early the pre-match card and the line-up fetch start. |
| `DAILY_REQUEST_BUDGET` | `7500` | **Set this to your API-Football plan's daily quota.** The poller refuses to exceed it and backs off instead of being cut off. Free = `100`. |
| `FEATURED_LEAGUE_IDS` | `39,140,135,78,61,2,3,88,94,203,383,253,71,128` | Competitions offered during onboarding. See §6 for the ids. |
| `CACHE_TTL_SECONDS` | `60` | TTL for catalogue and fixtures-by-date responses. Live calls are never cached. |
| `ADMIN_TOKEN` | *(unset)* | When set, guards `/v1/admin/*` (quota, poller status, manual poll trigger) behind `Authorization: Bearer <token>`. Leave unset to disable those routes. |

---

## 5. Point the app at the backend

Two ways:

**In the app** — Settings → *Data source* → **Backend URL**, paste
`https://<your-service>.onrender.com`, save. The app registers its FCM token and pushes its
subscriptions immediately.

**During onboarding** — step 2 offers the same field.

You can also leave the backend blank and paste an API-Football key instead; the app then
talks to the provider directly, with no push. If both are set, the backend wins.

---

## 6. Useful API-Football league ids

| Id | Competition | Id | Competition |
|---|---|---|---|
| 39 | Premier League | 2 | UEFA Champions League |
| 140 | La Liga | 3 | UEFA Europa League |
| 135 | Serie A | 88 | Eredivisie |
| 78 | Bundesliga | 94 | Primeira Liga |
| 61 | Ligue 1 | 203 | Süper Lig |
| 383 | Ligat ha'Al (Israel) | 253 | Major League Soccer |
| 71 | Brasileirão Série A | 128 | Liga Profesional (Argentina) |
| 1 | World Cup | 4 | Euro Championship |

Full list: `GET /leagues` with your key, or the app's onboarding screen.

---

## 7. Verifying the live path end to end

1. **Backend is polling** — Logs should show a tick line whenever a match is in play.
   With `ADMIN_TOKEN` set, `GET /v1/admin/status` reports the poller state and the
   remaining provider quota.
2. **Device is registered** — after opening the app with a backend URL configured,
   `GET /v1/subscriptions/<fcm-token>` returns your team ids.
3. **Push arrives** — during a live match involving a followed team, a goal should produce
   a notification within seconds. If polling works but push does not, the service account
   or `google-services.json` is the usual culprit; check the server logs for
   `messaging/…` errors.
4. **The card is actually promoted** — on the device, Settings → *Live card* reports
   whether this device supports `ProgressStyle` and whether promoted notifications are
   currently allowed. If they are not, the same screen offers a one-tap deep link to the
   system toggle.

---

## 8. Troubleshooting

**Everything returns empty and there are no errors.**
API-Football answers authentication and quota failures with **HTTP 200** and puts the
problem in the response body. The server checks for this and turns it into a 502 with the
provider's message — look at the service logs for `ProviderError`.

**`QuotaExhaustedError` in the logs.**
The daily budget is spent. Either raise your plan and `DAILY_REQUEST_BUDGET`, or raise
`POLL_INTERVAL_SECONDS`. The quota resets at 00:00 UTC.

**Notifications arrive twice.**
Almost always means the store fell back to in-memory because `DATABASE_URL` was unset —
de-duplication state is then lost on every restart. The startup log says which store is
active.

**No notifications while the phone is idle.**
High-priority data messages do wake a dozing device, but FCM audits this over a rolling
7-day window and demotes an app instance whose high-priority messages do not consistently
produce a visible notification. Kickoff posts a notification for every push it receives,
which keeps it on the right side of that audit — but a device that has had notifications
disabled for the app will be silently demoted.

**No status-bar chip and nothing on the always-on display.**
Promotion has nine independent eligibility conditions and fails silently. Check Settings →
*Live card*: if it says promoted notifications are not allowed, tap through to the system
toggle. If the style is set to **Rich**, that is the cause — a custom scoreboard is
disqualified from promotion by platform rule. And note AOD is a system/OEM decision with no
API at all; the app can only make the notification eligible.
