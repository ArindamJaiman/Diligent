# Diligent for the web (`web/`)

A browser version of Diligent with a liquid feel: a GPU fluid simulation behind the page that
follows your cursor, a blob cursor that chases the pointer on a spring, cards that fill with
liquid as you log, and a goo tab bar. It works on its own (data in the browser) and, with your
Supabase project, alongside the app.

## Run it

- **Hosted:** the `Pages` workflow publishes `web/` to GitHub Pages on every push to `main`.
  One-time: repo **Settings → Pages → Source: GitHub Actions**.
- **Locally:** `cd web && python3 -m http.server 8080`, then open http://localhost:8080.
  Opening `index.html` straight from disk also works, without offline mode.
- **Install:** in Chrome / Edge, *Install app* from the address bar; it then works offline.

## What's in it

| Tab | What it does |
|---|---|
| Today | Liquid orb with today's share of goals, a quote from the app's packs, quick log ("2h quantum", "+3 lc", "gym done yesterday", "skip reading", "=4 lc", "focus coding 50"), activity cards with +/−, done, skip, focus and exact values, past days with `[` / `]`, Top 3 and a day log (mood, energy, note) |
| Plan | Today (or any day two weeks ahead) as a timeline: drag what's still open onto hours, move blocks and resize them from the bottom edge (snaps to 15 min), **Auto-plan** puts the biggest first into free time in blocks of up to 90 minutes with 15 minutes between; import an **.ics** calendar (Google Calendar / Outlook / Apple Calendar export; daily and weekly recurrences, exceptions) to plan around meetings; ▶ starts focus for a block, and you get an alert when a block starts |
| Focus | Timer with an optional target (25 / 50 / 90 min) or Pomodoro 25/5; the liquid rises as time passes; logs to the activity when you stop (under 30 s logs nothing); intention, session list and best hours; focus sounds (rain, brown noise, waves, generated in the browser); **Float** keeps the timer in a small always-on-top window (Chrome / Edge); **Distracted** (or D) logs a distraction, with a card of when they happen most; Pomodoro breaks suggest something to do; after each session, *what did you finish?* and a 1–5 rating |
| Stats | Today, hours this week, perfect-day streak, level, a year heatmap (click a day to edit it), the last 14 days by activity, streak / best / habit strength / 30-day totals / trend per activity, how mood lines up with output, your **best week** next to this one (Replay pours it in day by day); **Weekly card** turns the last 7 days into a 1080×1350 image to download, share, copy or send to the clipboard |
| Clipboard | A clipboard shared with your phone (Hub → Clipboard), other browsers and room mates: type, paste (Ctrl V anywhere on the tab), drop text, files or images, copy with one click, pin; changes from other open tabs arrive instantly over Supabase Realtime |
| Room | The app's study rooms, as a liquid pool: everyone is a blob that grows while they focus and melts into the room, with who's focusing on what below; your web timer shows up too; an optional room passphrase encrypts room clips and chat; **room chat** (gone after a day) with **Nudge** for room mates who aren't focusing; **challenges** (start one from a template or your own, join by code, a shared leaderboard with the app with target and pace markers) |
| Settings | Activities (add, edit, reorder, archive, restore), goal types, schedules (every day, some days, N× a week, every N days: rest days and in-between days never break a streak; weekly schedules count met weeks), long-term targets ("200 h by 31 Dec": ahead/behind, needed per day, projected finish on the card and in Stats → Targets), colours, day start, quote pack, themes (Mono, Paper, Red night), liquid background / cursor / sounds / notifications, Supabase, backup |

Everything has a keyboard shortcut (`?` lists them) and the command palette (`Ctrl K` or `/`)
runs commands, starts focus sessions, switches themes and takes quick-log lines.

## Public page

Settings → **Public page** builds one self-contained `index.html` of your year for the activities
you tick: a heatmap (green when every goal was hit), current and best streaks, totals (or habit
strength, with hours off) and a 30-day sparkline each. It's opt-in and static: nothing is
uploaded, the page runs no JavaScript, and it never includes notes, mood, Top 3, clips or keys.
To publish it, put `index.html` in a GitHub repo named `<you>.github.io` (or any static host)
and download a fresh copy whenever you want to update it.

## Your data

- Kept in the browser's `localStorage` (`diligent.web.v1`), live across tabs.
- **Export JSON / CSV** in Settings. **Import** takes this site's export or the Android app's
  JSON backup (Settings → Backup in the app): activities are matched by name.
- **Sync:** paste the Supabase URL, anon key and owner id from the app's Hub → Cloud. Days go
  both ways by activity name, newer edit wins, like the app's sync. Activities the web app
  hasn't seen are created from the synced rows. Auto sync runs on open, 20 s after a change and
  every 5 minutes while the tab is open.
- No account, no analytics, no server of ours: the only network calls are to your Supabase
  project, Google Fonts, and api.anthropic.com when you write a coach letter.
- **Coach letter** (Stats, Week or Month): a short read of your last two weeks (or last 30 days vs the 30 before) with three experiments, written
  by Claude (Opus 5.5) with your own Anthropic API key, called straight from the browser. Only
  numbers and activity names are sent (Stats → Coach letter → What's sent shows the exact JSON);
  never notes, Top 3 or clips. The key lives in its own browser-storage entry, outside synced
  and exported data.

## Files

```
web/
├── index.html            shell, dialogs, goo filter
├── css/style.css         themes, liquid tanks, cursor, layout
├── js/store.js           data, goal rules, streaks, strength, quick-log parser, import/export
├── js/cloud.js           Supabase: day sync, shared clipboard, rooms
├── js/fluid.js           WebGL fluid simulation (falls back to a CSS gradient)
├── js/cursor.js          liquid cursor, magnetic buttons, ripples, card spotlight
├── js/app.js             views, focus timer, palette, shortcuts
├── js/quotes.js          generated from the app's quote packs
├── sw.js                 offline cache
└── manifest.webmanifest  install as an app
```

## Tests

`tools/web-test` serves `web/`, fakes Supabase and drives two browsers with Playwright
(logging, quick log, palette, focus, day sync, the shared clipboard, rooms, phone width). CI
runs it on every push that touches the web app (`Web check`). Locally:

```bash
cd tools/web-test && npm install && npx playwright install chromium && npm test
```

`js/quotes.js` is generated from `app/src/main/java/dev/diligent/app/widget/quotes`; regenerate
it if the packs change. Reduced-motion settings turn the fluid and the cursor off.
