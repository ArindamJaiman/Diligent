# Roadmap: features to build next

What Diligent already has is in the [README](../README.md). This is the to-do list of what
would make it better, grouped by area and roughly in priority order within each. ✅ marks what
has shipped since the list was started.

## Shipped in this round

- ✅ **Web app** (`web/`): liquid fluid background that follows the cursor, blob cursor,
  liquid-fill cards, focus timer, stats, rooms, command palette, three themes, offline install
  ([WEB.md](WEB.md))
- ✅ **Shared clipboard** across phone, web and rooms (Hub → Clipboard, the web Clipboard tab,
  *Share → Diligent clipboard* on Android)
- ✅ **Web import of the Android backup** and two-way day sync with the app through Supabase
- ✅ GitHub Pages deploy for the web app

## Clipboard & cross-device

- ✅ **Clipboard Quick Settings tile:** "Send clipboard" sends the phone clipboard in one tap
- ✅ **Clipboard widget:** the newest clip on the home screen with COPY, SEND MINE and ↻
- [ ] **Auto-send on copy** (opt-in, foreground only: Android 10+ only lets the focused app read
  the clipboard) and a "copy on the laptop → notification on the phone" mode using Supabase
  Realtime instead of polling
- [ ] **End-to-end encryption** for clips: derive a key from the owner id / room code so the
  Supabase project only stores ciphertext
- [ ] **Files** (PDFs, small zips) through Supabase Storage, with expiry
- ✅ **Pin and expire** clips (keep pinned, delete the rest after 24 h; 7 days or forever on the web)
- [ ] Browser extension: right-click → *Send to Diligent clipboard*

## Web app

- ✅ **Edit past days in the heatmap** (click a dot → that day's view), like the app's year heatmap
- [ ] **Schedules** (weekdays, N per week, every N days) and long-term targets on the web, to
  match the app's activity model
- ✅ **Supabase Realtime** for rooms and the clipboard: broadcast pings make changes instant; polling is the fallback
- [ ] **Plan view:** drag activity blocks onto a day timeline; import an .ics calendar
- ✅ **Weekly review card** as a shareable PNG (Stats → Weekly card): download, share, copy or send to the clipboard
- ✅ **Ambient sound** for focus (rain, brown noise, waves) generated with WebAudio
- ✅ **Picture-in-picture timer** (Document PiP API) so the countdown floats over other windows
- [ ] **Liquid avatars** in rooms: each member is a blob that grows while they focus
- ✅ Tests: a Playwright smoke test in CI against a mock PostgREST server (`tools/web-test`, `Web check` workflow)

## Tracking (app)

- [ ] **Sub-tasks / checklists** inside an activity ("LeetCode: 1 easy, 1 medium, 1 hard")
- [ ] **Numeric journal fields** (weight, resting heart rate, sleep score) charted next to activities
- [ ] **Smart reschedule:** a missed weekly target moves its remaining sessions into the days left
- [ ] **Undo snackbar** for every widget tap (reverts the last ± within 10 s)
- [ ] **Home-screen widget for one activity** with its own liquid fill, in Glance

## Focus

- [ ] **Focus music**: play a chosen playlist / app when a session starts, stop it on stop
- [ ] **Break suggestions** (stretch, water, eyes 20-20-20) during Pomodoro breaks
- [ ] **Session goals with notes:** "what did you finish?" on stop, kept with the session
- [ ] **Distraction log:** tap a button when you get distracted; see when it happens most

## Insights

- [ ] **Monthly letter** from Claude in addition to the weekly coach letter
- [ ] **Correlations** across sleep, steps, mood and output with confidence ranges
- [ ] **Forecast:** "at this pace you'll reach 200 h on 14 Dec" for every long-term target on the dashboard
- [ ] **Best-week replay:** what your best week looked like, hour by hour

## Social

- [ ] **Room chat** (short messages, gone after 24 h) and nudges ("ping Alex to focus")
- [ ] **Challenge templates:** 30-day LeetCode, 100 h quantum, 5 AM club
- [ ] **Public profile page** (opt-in): heatmap + streaks as a static page on GitHub Pages

## Platform

- [ ] **iOS / desktop** through the web app's PWA (done for reading and logging; push
  notifications next, via Web Push)
- [ ] **Wear OS complications** for the streak and today's percentage
- [ ] **Backup encryption** with a passphrase for Drive / GitHub backups
- [ ] **Localisation** (Hindi first)
