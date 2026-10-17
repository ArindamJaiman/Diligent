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
| **Health → Gym** | Reads today's exercise sessions from Health Connect and raises your Gym activity | – | none (on-device) |

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

- Health Connect is pinned to `1.1.0-beta01`, the newest version that builds with compileSdk 35 and AGP 8.6. Version 1.1.0 needs compileSdk 36 and AGP 8.9.
- No tool stores settings in Room, so no database migration was needed. Settings and caches live in SharedPreferences, which Android Auto Backup covers.
