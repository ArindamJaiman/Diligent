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
- ✅ **Auto-send on copy** (opt-in, Hub → Clipboard): when Diligent comes to the front, text
  copied since goes to your clips (Android 10+ only lets the focused app read the clipboard);
  its own copies, sensitive ones (password managers) and repeats are skipped
- [ ] "Copy on the laptop → notification on the phone" using Supabase Realtime instead of polling
- ✅ **End-to-end encryption** for your own clips with a clipboard passphrase (AES-256-GCM,
  PBKDF2): the Supabase project only stores ciphertext. Keying from the owner id or room code
  would protect nothing, since both are stored next to the clips
- ✅ End-to-end encryption for room clips: a room passphrase, shared apart from the code
- ✅ **Files** through Supabase Storage (web): up to 10 MB, always encrypted before upload,
  deleted with their clip
- ✅ **Pin and expire** clips (keep pinned, delete the rest after 24 h; 7 days or forever on the web)
- ✅ Browser extension: right-click → *Send to Diligent clipboard* (selection, link, image address
  or page; encrypted with the clipboard passphrase when set)

## Web app

- ✅ **Edit past days in the heatmap** (click a dot → that day's view), like the app's year heatmap
- ✅ **Schedules** (some days, N× a week, every N days) and long-term targets on the web, with
  the app's rules (rest days, week streaks, ahead/behind, projected finish) and imported from
  the app's backup
- ✅ **Supabase Realtime** for rooms and the clipboard: broadcast pings make changes instant; polling is the fallback
- ✅ **Plan view:** drag activity blocks onto a day timeline, move and resize them, import an
  .ics calendar (daily / weekly recurrences), auto-plan what's left around it, start focus from
  a block, an alert when a block starts
- ✅ **Weekly review card** as a shareable PNG (Stats → Weekly card): download, share, copy or send to the clipboard
- ✅ **Ambient sound** for focus (rain, brown noise, waves) generated with WebAudio
- ✅ **Picture-in-picture timer** (Document PiP API) so the countdown floats over other windows
- ✅ **Liquid avatars** in rooms: each member is a blob that grows while they focus and melts into the room
- ✅ Tests: a Playwright smoke test in CI against a mock PostgREST server (`tools/web-test`, `Web check` workflow)

## Tracking (app)

- ✅ **Sub-tasks / checklists** inside an activity ("LeetCode: 1 easy, 1 medium, 1 hard"; the
  app's activity screen, and the web's cards and editor; app backups carry them to the web): each item logs an equal share of the goal, the last tick fills it,
  and they go into backups
- ✅ **Numeric journal fields** on the web (Settings → Journal fields): your own daily numbers in
  the day log, charted in Stats and included in What goes with your good days
- ✅ **Smart reschedule:** "N× a week" spreads what's left of the target evenly over the days
  left; a missed session moves the next one onto today. Cards say "1/3 · next Thu", "0/3 ·
  every day to Sun" or "3 left, only 2 days", and the planners (app and web) only bring a
  weekly activity up on its days, first when it can't move
- ~~**Undo snackbar** for every widget tap~~ dropped: the widget's − already undoes a +, and an
  extra control would crowd the small widget layouts
- ✅ **Home-screen widget for one activity** with its own liquid fill, in Glance (Activity
  widget: the tank is drawn as a bitmap, so it sloshes a little on each tap)

## Focus

- ✅ **Focus music** (app, Hub → Focus; the web opens a playlist link): press play on what played last, or open a playlist
  link, when a session starts; pause on stop (only music it started) and during Pomodoro breaks
- ✅ **Break suggestions** (stretch, water, eyes 20-20-20) during Pomodoro breaks (web)
- ✅ **Session goals with notes:** "what did you finish?" and a 1–5 rating on stop, kept with the session (web)
- ✅ **Distraction log:** Distracted (or D) during a session; a 7-day card of when it happens most (web)

## Insights

- ✅ **Monthly letter** from Claude in addition to the weekly coach letter (web: Week / Month)
- ✅ **Correlations** on the web (Stats → What goes with your good days): mood, energy, focus
  minutes, distractions and the day before vs goals hit, with r, the number of days and a t-test
  confidence label; best and hardest weekday. (Sleep and steps stay in the app's Insights.)
- ✅ **Forecast** on the app's dashboard cards for every long-term target: "▲ 3.5 hrs ahead · done
  by 14 Dec" or "▼ 12 hrs behind · need 2.4/day" (the web shows the same on its cards and in Stats)
- ✅ **Coach letter on the web** with your own Anthropic API key (the app's weekly coach, from the browser)
- ✅ **Best-week replay** (web, Stats): your strongest finished week next to this one as liquid
  day columns, when its focus happened, and Replay to pour it in again

## Social

- ✅ **Room chat** (web Room tab: short messages, gone after 24 h, encrypted with the room
  passphrase when set) and **nudges** for room mates who aren't focusing
- ✅ **Challenge templates** (LeetCode 30, Quantum 100 h, Deep work week, Gym 12, Reading 20 h,
  Papers sprint) in the app, and **challenges on the web** (Room tab) sharing the app's leaderboard
- ✅ **Public profile page** (opt-in, web Settings → Public page): one self-contained HTML file
  of your year (heatmap, streaks, totals for the activities you tick), no scripts and no notes;
  host it anywhere

## Platform

- [ ] **iOS / desktop** through the web app's PWA (done for reading and logging; push
  notifications next, via Web Push)
- [ ] **Wear OS complications** for the streak and today's percentage
- ✅ **Backup encryption** with a passphrase for Drive / GitHub backups (AES-256-GCM, the
  clipboard's format; restore and the web import ask for it; the web exports encrypted too)
- [ ] **Localisation** (Hindi first)
