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
| Focus | Timer with an optional target (25 / 50 / 90 min) or Pomodoro 25/5; the liquid rises as time passes; logs to the activity when you stop (under 30 s logs nothing); intention, session list and best hours; focus sounds (rain, brown noise, waves, generated in the browser); **Float** keeps the timer in a small always-on-top window (Chrome / Edge) |
| Stats | Today, hours this week, perfect-day streak, level, a year heatmap (click a day to edit it), the last 14 days by activity, streak / best / habit strength / 30-day totals / trend per activity, and how mood lines up with output; **Weekly card** turns the last 7 days into a 1080×1350 image to download, share, copy or send to the clipboard |
| Clipboard | A clipboard shared with your phone (Hub → Clipboard), other browsers and room mates: type, paste (Ctrl V anywhere on the tab), drop text, files or images, copy with one click |
| Room | The app's study rooms: who's focusing on what, live; your web timer shows up too |
| Settings | Activities (add, edit, reorder, archive, restore), goal types, colours, day start, quote pack, themes (Mono, Paper, Red night), liquid background / cursor / sounds / notifications, Supabase, backup |

Everything has a keyboard shortcut (`?` lists them) and the command palette (`Ctrl K` or `/`)
runs commands, starts focus sessions, switches themes and takes quick-log lines.

## Your data

- Kept in the browser's `localStorage` (`diligent.web.v1`), live across tabs.
- **Export JSON / CSV** in Settings. **Import** takes this site's export or the Android app's
  JSON backup (Settings → Backup in the app): activities are matched by name.
- **Sync:** paste the Supabase URL, anon key and owner id from the app's Hub → Cloud. Days go
  both ways by activity name, newer edit wins, like the app's sync. Activities the web app
  hasn't seen are created from the synced rows. Auto sync runs on open, 20 s after a change and
  every 5 minutes while the tab is open.
- No account, no analytics, no server of ours: the only network calls are to your Supabase
  project and Google Fonts.

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
