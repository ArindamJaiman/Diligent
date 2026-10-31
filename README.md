# Diligent

> **Track your discipline. Build consistency.**

A minimalist monochrome productivity tracking app for Android with home screen widget support. Inspired by Nothing Phone's dot-matrix aesthetic.

---

## ✦ Design Philosophy

- **Pure monochrome** — Black background, white text, no unnecessary colors
- **Dot-matrix typography** — Cloister Black headlines, monospace body text
- **Nothing-inspired widget** — Premium home screen widget matching Nothing OS design language
- **Battery efficient** — Dark-first design, optimized widget updates, WorkManager scheduling

## ✦ Features

### Core
- Create, edit, delete trackable activities
- Increment/decrement progress directly from widget
- Mark activities complete with one tap
- Daily goal tracking with completion percentages
- **Schedules:** every day, chosen weekdays, N times a week or every N days; rest days and skipped days never break a streak, weekly targets count met weeks
- **Goal types:** at least (reach it), at most (a limit, e.g. max 1 h of YouTube) and quit (days clean)
- **Habit strength** (Loop-style 0–100%) next to the streak
- **Year heatmap** per activity: tap any day to change its progress, mark it done, skip it, rate it 1–5 or add a note
- **Long-term targets** ("200 h by 31 Dec") with ahead/behind, pace needed and projected finish
- **Day starts at** midnight to 6 AM, or follows your sleep, so 1 AM work counts for the evening before
- **Commitment mode:** easier goals start a week later; optional accountability partner on the missed-goal alert
- **Private activities** show as dots on widgets and the lock screen
- **Reminders per activity** that only ring while it's still open, can nag every 15/30/60 min until done, and have log / start timer / done / snooze buttons

### Widget
- Resizable Jetpack Glance widget
- Inline +/− controls (no app launch required); the tapped widget redraws in-process at once
- Tap or double-tap the quote panel for the next quote without waiting for the flip
- Real-time progress display
- Auto-refresh every 30 minutes

### Statistics
- Daily / Weekly / Monthly completion rates
- Activity streaks with consecutive day tracking
- Total hours tracked
- Visual bar charts for weekly overview

### Notifications
- Daily reminders via WorkManager
- Per-activity custom reminders
- Missed goal notifications
- Survives device reboots

### App updates
- Every commit on `main` is built, signed and published as a GitHub release by CI
- The release app checks when you open it (at most every 6 h, never in the background) or from Settings → App updates, asks **Update found**, verifies the download's SHA-256 and hands it to Android's installer
- One-time setup (release key, GitHub secrets, moving data from the debug app): [docs/UPDATES.md](docs/UPDATES.md)

### Data
- Room database with migration support
- JSON backup & restore
- CSV statistics export
- Persistent across reboots

### Quote Packs
- 11 widget quote packs (Wall Street plus 10 themed packs of 100 quotes each), and a Mix option
- Pick one in Settings → Widget Quotes; quotes appear in random order

### GitHub Contribution Graphs
- Track up to 50 developers in Settings → GitHub Contribution Graphs: add a username or paste a profile link, reorder with ↑/↓, tap a row to rename it or pick its colour
- The **GitHub** widget is a slideshow of every developer, flipped by the launcher. A launcher only takes a few MB of bitmaps per widget, so with a long list it loads the slides that fit (about 5 on a 4×2 widget) starting from the current one; tap a slide's header or double-tap the graph to step on to the next developer. A single tap on the graph opens the app after a short pause (to tell it from a double tap); the footer row opens it straight away
- The **GitHub Versus** widget compares the first two developers in the list
- Graphs sync only when you tap **Update Contribution Graphs** (one request per developer, 4 at a time)

### QuotaPulse (AI usage widget)
- Claude Code, Gemini, Antigravity and ChatGPT / Codex usage on the home screen
- Syncs only when you tap SYNC; live mode only runs when you start it, and has a STOP button
- Shows only real numbers, each labelled with its source and time
- Setup and supported metrics: [docs/QUOTAPULSE.md](docs/QUOTAPULSE.md)

### LeetCode Tracker
- Streak, today's status, Easy / Medium / Hard solved, daily challenge, contest rating and a submission heatmap, from your public profile (no login)
- Home-screen widget in three sizes; the code icon on the dashboard opens the full screen
- Reminders: daily challenge alert, streak guard before the UTC reset (with CHECK NOW / DONE TODAY buttons and quiet hours), contest heads-up
- Auto-logs problems solved today into your LeetCode activity
- How it works and what it costs in battery: [docs/LEETCODE.md](docs/LEETCODE.md)

### Focus Timer
- Time any activity measured in hrs or mins instead of tapping +/−. Start and stop it from the ⏱ button on the activity card, the **Focus Timer** widget (2×1 or 2×2) or the **Focus timer** Quick Settings tile
- On stop, the time is added to that day's progress, rounded to the nearest minute (under 30 s logs nothing). A session that runs past midnight is split between the two days
- Optional Pomodoro 25/5. Each 25-minute block is logged when it ends, and breaks aren't counted. The widget and tile start the last-timed activity in the last-used mode; on the widget, tap the name to change the activity or tap the mode to switch to 25/5
- Battery: nothing in the app ticks. The widget clock is a launcher-side `Chronometer`, and the ongoing notification (with STOP) uses the system chronometer. Pomodoro sets one non-wakeup alarm per block. The running session is just a start time in SharedPreferences, so it survives the app being killed and is picked up again on the next start

### Focus
- Start dialog: intention, timer or Pomodoro, planned length, shield; INTERRUPTED button; rate each session 1–5
- **Focus Shield** (accessibility service, window changes only): blocks apps marked distracting during a session; opening one anyway breaks the session's **stake**; a **mindful pause** before them the rest of the time
- **Hard mode:** a session with a planned length can't be stopped early; **Do Not Disturb** while focusing
- Every session is kept: best hours of the day, average length, stakes held; optional **calendar log**
- **Android 16 Live Update:** the running timer as a status-bar chip with progress

### More tools (Hub)
- **Plan:** auto-plans what's left today around your calendar, deep work in your energy peak; optional calendar blocks; morning plan notification
- **Shutdown:** evening close: mood, energy, a note, tomorrow's Top 3
- **Routines:** chains of activities run through the focus timer
- **Automations:** WakaTime, GitHub contribution days, Codeforces + AtCoder solves, app time (AnkiDroid, Kindle…), NFC tags, Tasker / `diligent://` links, places (geofences); Health Connect sleep, steps, mindfulness
- **Insights:** readiness score and burnout warning, what goes with your good days, period comparison, a **weekly coach letter written by Claude** with your own API key
- **Achievements:** level, badges, 10 → 10,000 h mastery ladder
- **Reports:** month / year wrap-up images, PDF report for any range, README stats card
- **Quick log:** "2h quantum", "+3 lc", "gym done yesterday", typed or spoken; QS tile and launcher shortcuts
- **Desk mode** and screen saver; **Glyph Matrix** toy for the Nothing Phone (3) ([docs/GLYPH.md](docs/GLYPH.md))
- **Cloud:** nightly backup to Drive / Dropbox / a private GitHub repo, Supabase sync, web dashboard, laptop browser extension, **study rooms** and **challenges**, webhook / Notion export ([docs/CLOUD.md](docs/CLOUD.md))
- **Now widget** (the card that fits the moment) and a **Wear OS tile** (`wear` module)
- Look & feel: red night and paper themes, four launcher icons, haptics and chimes, app lock

### Hub: productivity tools
The grid icon on the dashboard opens the Hub. Details: [docs/TOOLS.md](docs/TOOLS.md)
- **Today's Top 3**: pick your three must-dos each morning; a widget with + buttons and today's calendar busy and free time
- **LeetCode Versus**: you vs up to 3 friends (solved today, streaks), with a widget and an evening nudge when a friend is ahead
- **Contest Radar**: Codeforces, AtCoder, CodeChef and LeetCode contests plus your ratings in one list, with a widget and heads-up alerts
- **Paper of the Day**: a daily arXiv pick (quant-ph, q-fin.TR, …), a reading queue, and READ to log time to Research Papers
- **Streaks & Goals**: streak freezes (7 days in a row earn one, max 2) and a goal coach that suggests realistic goals
- **Weekly Review**: Sunday summary notification and a shareable image card
- **Screen vs Focus**: hours logged vs time in distracting apps (on-device usage data), with a widget and an evening nudge
- **Health → Gym**: auto-logs workouts from Health Connect (phone, watch, fitness apps)

## ✦ Architecture

```
MVVM + Repository Pattern
├── data/
│   ├── local/
│   │   ├── entity/          # Room entities
│   │   ├── dao/             # Data Access Objects
│   │   └── DiligentDatabase # Room database
│   └── repository/          # Single source of truth
├── di/                      # Hilt modules
├── focus/                   # Focus timer: session, Pomodoro, notification, widget, QS tile
├── leetcode/                # LeetCode tracker: API, sync, reminders, widget, UI
├── notifications/           # WorkManager + BroadcastReceiver
├── quotapulse/              # AI usage widget: sources, sync, live service, UI
├── ui/
│   ├── components/          # Reusable Compose components
│   ├── navigation/          # NavGraph + routes
│   ├── screens/             # Full-screen composables
│   ├── theme/               # Colors, Typography, Theme
│   └── viewmodel/           # Hilt ViewModels
└── widget/                  # Jetpack Glance widget
```

**Tech Stack:**
- Kotlin + Jetpack Compose
- Hilt (Dependency Injection)
- Room (Database)
- Jetpack Glance (Widget)
- WorkManager (Notifications)
- StateFlow (Reactive state)
- Compose Navigation

## ✦ Build & Run

### Prerequisites
- Android Studio Hedgehog+ (2023.1.1+)
- JDK 17
- Android SDK 35
- Min SDK 26 (Android 8.0)

### Steps

```bash
# Clone
git clone <repo-url> && cd Diligent

# Open in Android Studio
# File → Open → select Diligent folder

# Build
./gradlew assembleDebug

# Run
./gradlew installDebug
```

### Font Setup
The Cloister Black font (`cloister_black.ttf`) is included in `app/src/main/res/font/`.

## ✦ Widget Setup

1. Long-press home screen
2. Tap "Widgets"
3. Find "Diligent Tracker"
4. Drag to home screen
5. Resize as needed (supports 2x2 to 5x4)

### Lock screen
- Every widget declares `home_screen|keyguard`, so any lock screen that hosts third-party widgets can show it: Android 16 QPR2+ "widgets on lock screen" (Pixel and other AOSP-based phones) and tablet hubs
- Buttons inside widgets (+/−, next graph or quote, SYNC, Focus start/stop, STOP LIVE) are broadcasts and work without unlocking; anything that opens the app asks you to unlock first
- **Nothing phones:** as of Nothing OS 5.0, Nothing's own lock screen only accepts Nothing's widgets and has no developer API, so these widgets can't be placed there yet. They are ready if Nothing opens it up

## ✦ Default Activities

| Activity | Unit | Daily Goal |
|---|---|---|
| Quantum Computing | hrs | 5 |
| LeetCode | qs | 3 |
| HFT Preparation | hrs | 2 |
| Research Papers | hrs | 2 |
| Gym | hrs | 1 |
| Reading | hrs | 1 |
| Coding | hrs | 3 |

## ✦ Author & Connect

Developed with dedication by **Arindam Jaiman**. Let's connect!

- 💻 **Portfolio:** [aj-the-dev.vercel.app](https://aj-the-dev.vercel.app/)
- 🐙 **GitHub:** [@ArindamJaiman](https://github.com/ArindamJaiman)
- 💼 **LinkedIn:** [Arindam Jaiman](https://www.linkedin.com/in/arindam-jaiman-6149a82ab/)
- 📸 **Instagram:** [@thearindamjaiman](https://www.instagram.com/thearindamjaiman)

## ✦ License

MIT License — Free to use and modify.
