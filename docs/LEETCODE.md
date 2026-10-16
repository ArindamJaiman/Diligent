# LeetCode Tracker

Tracks a LeetCode account from its **public profile**, shows it on a home-screen widget and in the app, and reminds you at the moments that matter: when the daily challenge is out, before your streak breaks, and before a contest starts.

## Setup

1. Tap the **code icon** on the dashboard.
2. Enter your username, or paste your profile link (`leetcode.com/u/<name>`).
3. Allow notifications when asked.
4. Optional: long-press the home screen → Widgets → **LeetCode Tracker**.

No login, password or cookie is needed. The app reads the same public GraphQL endpoint (`leetcode.com/graphql`) that the profile page uses.

## What is shown

| Item | Source | Notes |
|---|---|---|
| Streak | Submission calendar + recent accepted list | Days in a row with a submission. Today counts once you submit. Until then, the streak continues from yesterday. |
| Today | Same | `SAFE TODAY`, `NOT YET TODAY`, or `SYNC TO CHECK` when the cached data is from before today's reset |
| Solved E / M / H | `submitStatsGlobal` | Out of the total number of questions |
| Daily challenge | `activeDailyCodingChallengeQuestion` | Marked solved when it is in your recent accepted list since the reset |
| Contest rating, next contest | `userContestRanking`, `topTwoContests` | |
| Heatmap | `userCalendar.submissionCalendar` | Same shading levels as LeetCode |

**LeetCode's day runs on UTC.** It resets at 05:30 in India and at 17:00 (16:00 in winter) on the US west coast. Streak, "today" and the daily challenge all follow that reset, not local midnight. The app shows the reset as a local clock time.

## Reminders

| Reminder | Default | Behaviour |
|---|---|---|
| Daily challenge | 09:00, on | One fetch, then a notification with title, difficulty and acceptance rate. Tapping it opens the problem. Skipped if you've already solved it. |
| Streak guard | 21:00, on | One fetch. If nothing was submitted this LeetCode day, you get "N-day streak at risk", the time until reset, and three buttons: **SOLVE DAILY**, **CHECK NOW** (re-checks right away and replaces the alert with "streak safe" if you've submitted), **DONE TODAY** (no more alerts until the next reset). |
| Repeat until solved | on | Re-checks every 2 h for up to 6 h, plus a last call 1 h before the reset. Repeats never ring between 00:00 and 07:00 local time; the time you choose always does. In India, the default gives checks at 21:00 and 23:00. |
| Only the daily challenge counts | off | The streak guard then wants the daily challenge solved, not just any submission |
| Contest heads-up | 30 min, on | 10, 30 or 60 min before each weekly and biweekly contest. Uses an alarm that rings even when the phone is asleep. |

If LeetCode can't be reached when a check runs, the alert says so and shows when the data was last synced. It doesn't guess that you're safe.

## Auto-log into the tracker

When on (default), each sync counts the problems you got accepted during the **local** day and raises today's progress on the activity you picked. If you don't pick one, it uses the first activity with "LeetCode" in its name. Auto-log only raises progress and never lowers it, so you can still log by hand with +/−.

## Battery and data

- **No background polling.** A network request happens only when you tap SYNC, open the screen (if the data is over 5 minutes old), or a reminder check runs. With the defaults that's 3–4 small requests a day, about 5 KB each.
- One GraphQL request returns everything (profile, calendar, recent accepts, contest data, daily challenge, schedule). Repeated taps within 30 s reuse the last result.
- The widget never fetches. It redraws after a sync and at the next UTC reset or contest start/end, using a non-wakeup alarm. Times are shown as clock times ("RESETS 05:30") so the widget doesn't need redrawing every minute.
- Daily and streak checks are WorkManager jobs, so they survive reboots. Each one schedules the next.
- Contest alarms use `setAndAllowWhileIdle`, which needs no exact-alarm permission. Android can shift them by a few minutes. Every sync and app start re-arms them, because a reboot clears them.

## Code map (`app/src/main/java/dev/diligent/app/leetcode/`)

| File | Role |
|---|---|
| `data/LeetCodeApi.kt` | The one GraphQL query, HTTP call, pure parser |
| `data/LeetCodeModels.kt` | Snapshot, config, sync status |
| `data/LeetCodePrefs.kt` | Settings + cached snapshot (SharedPreferences, StateFlow) |
| `domain/LeetCodeDay.kt` | UTC-day math, streak, solved today, `StreakSchedule` (quiet hours, repeats, last call) |
| `sync/LeetCodeSync.kt` | Coalesced fetch, contest alarms, auto-log, widget refresh |
| `notify/` | Notifications, WorkManager jobs, alarms, notification buttons |
| `presentation/LeetCodePresenter.kt` | What the widget and screen show (pure) |
| `widget/` | Glance widget (compact / standard / large), link hop activity |
| `ui/` | Screen and ViewModel |

Tests are in `app/src/test/java/dev/diligent/app/leetcode/`: parser, streak/day math, schedule across India, US Pacific and UTC time zones, and presenter states.

## Not verified on a device yet

- Widget rendering at all three sizes on the Nothing launcher
- Notification buttons, contest alarm timing in Doze, behaviour after a reboot
- LeetCode may rate-limit or change its unofficial API. Failures appear as `SYNC FAILED`, and cached data stays on screen.
