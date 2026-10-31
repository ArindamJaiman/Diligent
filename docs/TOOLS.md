# Productivity tools (Hub)

Open the **Hub** with the grid icon on the dashboard. Each tool keeps working offline from cached data. It goes online only when you tap SYNC, open its screen, or at its scheduled time.

| Tool | What it does | Widget | Network |
|---|---|---|---|
| **Today's Top 3** | Each morning, pick up to 3 activities that would make the day a win. Shows progress, a + button and your calendar's busy and free time. | ✓ | none |
| **LeetCode Versus** | You and up to 3 friends: problems solved today, streaks, totals. Evening nudge when a friend is ahead. | ✓ | one LeetCode request for everyone |
| **Contest Radar** | Upcoming Codeforces, AtCoder, CodeChef and LeetCode contests in one list, your ratings, and a heads-up before each start | ✓ | once a day at 08:00 + when opened (data > 6 h old) |
| **Paper of the Day** | One arXiv pick a day from your categories (quant-ph and q-fin.TR by default), a reading queue, and **READ +30m** to log time to Research Papers | ✓ | arXiv RSS, at most once a day |
| **Streaks & Goals** | Streak freezes and the goal coach (see below) | – | none |
| **Weekly Review** | Sunday 20:00 notification and a screen: % of goals hit, hours, best day, per-activity bars, LeetCode/GitHub, one tip. **SHARE AS IMAGE** creates a 1080×1350 card. | – | none |
| **Screen vs Focus** | Hours logged on time-based activities vs time in apps you mark as distracting (Android usage data, read on the phone). Evening nudge if scrolling wins. | ✓ | none |
| **Health → Gym** | Reads today's exercise sessions from Health Connect and raises your Gym activity; optionally sleep, steps and mindfulness into the day log | – | none (on-device) |
| **Plan** | Auto-plans what's left today around calendar events; deep work goes into your energy peak; START runs a timed session; optional calendar blocks; morning plan | Now | none |
| **Shutdown** | Evening close: done/open, mood and energy, a note, tomorrow's Top 3 | Now | none |
| **Routines** | Ordered activity chains; timed steps run the focus timer | – | none |
| **Focus** | Shield, mindful pause, hard mode, DND, calendar log, 30-day focus stats and best hours | Focus Timer | none |
| **Automations** | WakaTime, GitHub contribution days, Codeforces + AtCoder solves, app time; NFC tags, Tasker and `diligent://` links; places | – | WakaTime, GitHub, CF/AtCoder when synced |
| **Insights** | Readiness and burnout check, patterns, period comparison, weekly coach letter (Claude, your key) | – | api.anthropic.com only when you ask for a letter |
| **Achievements** | Level, badges, mastery ladder | – | none |
| **Reports** | Wrap-up images, PDF report, README card (shared through the system share sheet) | – | none |
| **Quick log / Desk mode / Glyph Matrix** | Natural-language logging; dot-matrix clock and screen saver; Phone (3) back matrix | QS tile, shortcuts | none |
| **Study room / Challenges / Cloud** | Live rooms, leaderboards, backup, sync, export (docs/CLOUD.md) | – | your Supabase / GitHub / webhook / Notion, only once set up |

## Streak freezes

Every **7 completed days in a row** earn a freeze, up to **2** banked. A missed day uses one automatically, so the streak continues: the missed day doesn't count, but it doesn't reset either. With no freeze left, a missed day resets the streak. Today never counts as missed, because it isn't over yet.

The rule is computed from your existing history; no new data is stored. All streak displays use it: dashboard cards, details, statistics.

## Goal coach

The coach looks at the last 14 finished days. It needs at least 7 days since the activity was created.

- Goal hit on fewer than 35% of days → suggests lowering it to what a typical active day reaches (60th percentile, rounded to ¼ h, 5 min or 1).
- Hit on 90%+ of days and averaging 15%+ above it → suggests raising it toward the average (at most +50%).

**APPLY** changes the goal. **NOT NOW** hides the suggestion for 14 days.

## Permissions (all optional, asked only when you use the feature)

| Permission | Used by | Notes |
|---|---|---|
| Notifications | every reminder | |
| Calendar (read) | Top 3 | Timed busy events for today only. Read locally. |
| Usage access | Screen vs Focus | Special access, granted in Settings → Usage access |
| Health Connect: exercise (+ background read) | Health → Gym | Today's sessions only. Nothing is written. |
| Health Connect: sleep, steps, mindfulness | Health, Insights, Plan, day start | Last two weeks of sleep, today's steps and mindfulness. Nothing is written. |
| Calendar (write) | Focus → calendar log, Plan blocks | Only into the calendar you pick; events are marked free. |
| Accessibility service | Focus Shield | Window changes only (which app is in front), never screen content. |
| Do Not Disturb access | Focus | Priority-only while a session runs, restored after. |
| Location (+ background) | Automations → places | Geofences around places you save; no location history. |
| NFC | Automations → write a tag | Reader mode only while the "hold a tag" dialog is open. |
| Biometric | App lock | System prompt; Diligent never sees biometrics. |

## Scheduled jobs

All of them are one-time WorkManager jobs that schedule the next run when they finish. They survive reboots, and nothing polls in between.

| Job | Default | Runs only if |
|---|---|---|
| Top 3 prompt | 08:00 | prompt on and nothing picked yet |
| Contest Radar refresh | 08:00 | at least one platform on |
| Paper notification | 08:30 | notification on (off by default) |
| Versus nudge | 20:00 | friends added, nudge on, a friend ahead today |
| Health evening read | 20:50 | connected and background read allowed |
| Screen-time nudge | 21:30 | usage access, nudge on, 30+ min distracting > focus |
| Weekly review | Sun 20:00 | enabled |
| Auto-log | 20:45 | a source is linked (Automations) |
| Morning plan | 07:30 | "Plan every morning" on |
| Shutdown prompt | 21:30 | on, and the day isn't closed |
| Cloud nightly | 23:40 | backup, sync or export set up |
| Activity reminders (+ nags) | per reminder | the activity is still open today |

Contest heads-ups use `setAndAllowWhileIdle` alarms, which every sync and app start re-arm. LeetCode contests keep their own reminder from the LeetCode tracker.

## Data sources

- **Codeforces:** official API `contest.list` (~400 KB) and `user.info`
- **AtCoder:** the "running" and "upcoming" tables on `atcoder.jp/contests`, and `users/<id>/history/json`
- **CodeChef:** `api/list/contests/all`, and the rating history embedded in the profile page (~180 KB, refreshed at most every 3 days)
- **arXiv:** `rss.arxiv.org/rss/<categories>` (~300 KB for two categories)
- **LeetCode Versus:** the same public GraphQL endpoint as the tracker, with one aliased query for all players

## Code map

| Package | Contents |
|---|---|
| `tools/` | Hub, routes (`diligent://tool/<name>`), shared UI and widget kit, `ToolJobs` scheduler and worker, receiver, app-start bootstrap |
| `goals/` | `StreakEngine` (freezes), `GoalCoach`, Streaks & Goals screen |
| `review/` | Weekly review builder, notification, share card |
| `versus/` | Aliased GraphQL query, leaderboard, screen, widget |
| `contests/` | Parsers per platform, `ContestRadar` (sync, alarms), screen, widget |
| `papers/` | arXiv RSS parser, daily pick, queue, screen, widget |
| `top3/` | Picks, calendar busy maths, screen, widget |
| `screentime/` | Usage-event counter, distracting-app list, screen, widget |
| `health/` | Health Connect reader, workout maths, privacy screen |

Tests: `app/src/test/java/dev/diligent/app/goals/StreakEngineTest.kt`, `tools/ToolsLogicTest.kt`, `tools/FeedParsersTest.kt`.

## Notes

- Health Connect is on `1.1.0`, which needs compileSdk 36 and AGP 8.9+ (the app builds with compileSdk 36 and AGP 8.10; targetSdk stays 35).
- No tool stores settings in Room, so no database migration was needed. Settings and caches live in SharedPreferences, which Android Auto Backup covers.
