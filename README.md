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

### Widget
- Resizable Jetpack Glance widget
- Inline +/− controls (no app launch required)
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

### Data
- Room database with migration support
- JSON backup & restore
- CSV statistics export
- Persistent across reboots

### Quote Packs
- 11 widget quote packs (Wall Street plus 10 themed packs of 100 quotes each), and a Mix option
- Pick one in Settings → Widget Quotes; quotes appear in random order

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
