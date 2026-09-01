<div align="center">

<img src="docs/logo.png" width="120" alt="Kickoff logo" />

# Kickoff

**Live football on your lock screen. Your calendar in the same place.**

Android 16 Live Updates · Material 3 · Jetpack Compose · Node backend on Render

</div>

---

Kickoff puts a live, self-updating card on your phone from an hour before kick-off until
the final whistle. Before the match it counts down and shows the confirmed line-ups; once
the ball is rolling it carries the score, the match minute, and every goal, card and
substitution as it happens — on the lock screen, in a status-bar chip, and on the
always-on display where the device supports it.

It also reads your device calendar, so the same live card can count you into your next
meeting.

<img src="docs/notification-designs.png" alt="The three live card renderings: status-bar chip and Dynamic Island, the promoted ProgressStyle Live Update, and the rich RemoteViews scoreboard" />

## What it does

| | |
|---|---|
| **Live match card** | A promoted [Live Update](https://developer.android.com/develop/ui/views/notifications/live-update) whose progress bar *is* the match clock: two 45-minute segments, a marker at the minute of every goal, and the ball tracker sitting on the current minute. Team crests bookend the bar. |
| **Status-bar chip** | The scoreline compressed to seven characters (`2-1`), visible while you use other apps. |
| **Pre-match line-ups** | From T-60, the card shows the countdown and, once the clubs publish them, both starting XIs and formations. |
| **Goal / card / foul alerts** | Goals with scorer and assist, yellows and reds with the player, substitutions, VAR decisions. Each one alerts at most once, whether it arrived by push or by poll. |
| **Dynamic Island** | A pill under the status bar carrying the crests and the score, which springs open into a full match card when tapped. Optionally floats over every other app. |
| **Calendar** | Reads the device calendar provider — Google, Exchange, CalDAV, local — and drives the same live card for your next event. Nothing leaves the device. |
| **Onboarding** | Pick competitions, then favourite teams, then decide what you want to be interrupted for. |
| **Fixtures & teams** | A date-strip schedule, per-competition browsing, team search, and a match screen with a timeline, a drawn pitch with both line-ups, and the stats. |

## The honest part: what Android actually allows

Android forces a choice that iOS does not, and it is worth understanding before you judge
the design.

A **promoted ongoing notification** — Android 16's Live Update — is the only kind of
notification that can appear as a status-bar chip, stay expanded on the lock screen, and
be rendered on an always-on display. To qualify, the notification **must not use custom
`RemoteViews`**. That is a hard eligibility rule, not a limitation of this app.

A **custom scoreboard** with crests, a big scoreline and an event ticker requires
`RemoteViews`, and is therefore *permanently* disqualified from all of those surfaces. It
also never renders on AOD, on any Android version.

So Kickoff builds both and picks at post time:

| Setting | What you get | Where it appears |
|---|---|---|
| **Auto** (default) | `ProgressStyle` Live Update, promoted | Shade, lock screen, status-bar chip, AOD where supported |
| **Rich** | Custom scoreboard with crests | Shade and lock screen only — never the chip, never AOD |
| **Plain** | System template | Everywhere, plainly |

**On always-on display specifically:** there is no API to request, control, or query AOD
placement. The platform documents it in exactly one sentence, hedged — a promoted
notification "may … permanently appear on always-on-displays". It is a system and OEM
decision. Being promoted is the only lever an app has, and Kickoff pulls it: it declares
`POST_PROMOTED_NOTIFICATIONS`, requests promotion, avoids every disqualifier, and reads
`FLAG_PROMOTED_ONGOING` back after posting to confirm the system agreed. Settings shows
you the real answer for your device rather than a promise. On Samsung One UI, Live Updates
surface in the Now Bar, which does render on AOD; third-party access to it has been
reported as gated behind a developer-options toggle on some firmware.

## Architecture

```
┌─────────────────────────────┐         ┌──────────────────────────────┐
│  Android app (Kotlin)       │         │  Kickoff backend (Node/TS)   │
│                             │  REST   │                              │
│  Compose UI ── ViewModels   │◄───────►│  Express 5                   │
│       │                     │         │       │                      │
│  Repositories ── Room cache │  FCM    │  Live poller (leader-locked) │
│       │                     │◄────────│       │                      │
│  Notification engine        │  data-  │  Event diff ── idempotency   │
│   ├ ProgressStyle (promoted)│  only   │       │                      │
│   ├ RemoteViews scoreboard  │  push   │  Postgres                    │
│   └ plain template          │         │       │                      │
│                             │         │       ▼                      │
│  CalendarContract (local)   │         │  API-Football v3             │
└─────────────────────────────┘         └──────────────────────────────┘
```

Two data paths, and the app works on either:

- **Backend mode** (recommended). The server polls API-Football once on behalf of every
  user, diffs the events, and pushes only what changed as high-priority data-only FCM
  messages. One provider request serves everybody, the API key never ships in the APK, and
  a goal reaches the phone in seconds.
- **Direct mode.** Paste your own API-Football key in Settings and the app talks to the
  provider itself. Nothing to deploy — good for trying it out, but a free key is 100
  requests a day, which one live match at a one-minute cadence already exceeds.

### Notable design decisions

- **Events are deduplicated on a deterministic id**, `matchId:TYPE:minute:teamId:player`,
  derived identically on the server and the client. Push and polling both insert through
  the same `INSERT OR IGNORE` gate, so whichever arrives first alerts and the other
  silently no-ops. This is also what makes restarts and overlapping deploys safe.
- **The calendar uses `CalendarContract`, not the Google Calendar REST API.** The provider
  already holds every synced calendar, needs no OAuth client, no consent screen and no
  sensitive-scope verification, works offline, and returns recurring events already
  expanded. The REST API would have added a launch-blocking Google review and still only
  seen Google calendars.
- **Two notification channels.** The scoreboard is `IMPORTANCE_LOW` and silent for ninety
  minutes; goals and red cards go out on a separate `IMPORTANCE_HIGH` channel. Channel
  importance is immutable after creation, so this had to be right the first time.
- **Reads always come from Room.** The network only ever writes into the cache, so every
  screen renders instantly and offline.
- **Colour is generated, not hand-picked.** The whole Material 3 scheme is derived from one
  brand seed (`#00C853`) with the Content scheme variant, which keeps the seed itself as
  `primaryContainer` and holds the surfaces near-neutral so crests and scorelines stay the
  loudest thing on screen. Dynamic colour is opt-in — Material You would otherwise replace
  club identity with wallpaper hues.

## Project layout

```
android/          Kotlin app (single module)
  app/src/main/java/com/tzvi/kickoff/
    core/model/         domain types
    data/               remote (API-Football + backend), Room, calendar provider, repositories
    notifications/      Live Update engine, foreground service, FCM
    work/               WorkManager + exact alarms
    ui/                 theme, design system, motion, Dynamic Island, navigation
    feature/            one package per screen
server/           Node 22 + TypeScript backend
render.yaml       Render Blueprint (root, where Render looks for it)
docs/             logo, deployment guide
```

## Running it

**App**

```bash
cd android
./gradlew assembleDebug
```

The APK lands in `android/app/build/outputs/apk/debug/`. Requires JDK 17+ and an Android
SDK with platform 37; Android Studio will fetch both. No API key is needed to build — the
app asks for one during onboarding.

Firebase is optional. Without an `android/app/google-services.json` the app builds and runs
in polling-only mode; drop one in and push wiring activates itself.

**Backend**

```bash
cd server
npm install
cp .env.example .env      # fill in API_FOOTBALL_KEY at minimum
npm run dev
```

**Deploying** — see **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** for the Render blueprint,
every environment variable, and exactly where to obtain each secret.

**Checks**

```bash
cd android && ./gradlew testDebugUnitTest lintRelease assembleRelease
cd server  && npm run typecheck && npm test && npm run lint
```

The unit tests cover the parts that fail silently rather than loudly: the scoreline
accumulator (an own goal is credited to the other side), deterministic event ids derived
identically on both sides of the wire, `kickoffAt` in seconds rather than milliseconds,
all-day calendar events read back in UTC, and the diff that has to tell a VAR retraction
apart from a provider hiccup.

## Requirements

- Android 8.0 (API 26) and up
- Android 16 QPR1 (API 36.1) or newer for promoted Live Updates — the status-bar chip and
  AOD placement. Below that the app degrades to the rich scoreboard automatically.
- An [API-Football](https://www.api-football.com/) key, or a deployed Kickoff backend

## Data and attribution

Football data comes from [API-Football](https://www.api-football.com/). Club crests and
league badges are served from their CDN and remain the trademarks of their respective
clubs and competitions — check the provider's terms before publishing an app that uses
them. Calendar data is read from the device and is never transmitted anywhere.

## Licence

MIT — see [LICENSE](LICENSE).
