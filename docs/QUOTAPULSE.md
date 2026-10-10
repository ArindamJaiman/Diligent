# QuotaPulse

QuotaPulse is a home-screen widget inside Diligent that shows your AI tool usage for **Claude Code**, **Gemini**, **Antigravity** and **ChatGPT / Codex**. It was designed for the Nothing Phone (2a) Plus, but it is a standard Android widget and works on any launcher.

It refreshes **only when you tap SYNC**. A separate **live mode** keeps refreshing on a timer, but only after you start it, and its STOP control is always visible.

---

## What it can and can't show

Every number shows where it came from and when it was measured. Different kinds of numbers are never mixed. For example, API billing is never shown as your plan allowance. Unknown data shows as `—` with a reason, and never as `0%`.

| Provider | Shown | Source | Not available, and why |
|---|---|---|---|
| **Claude** | Session tokens today | Desktop connector (Claude Code's local logs) | Remaining % of a Pro/Max plan: no documented API. Add it with manual entry if you want it on the widget. |
| | `LIMIT HIT` plus reset time | Desktop connector (Claude Code records this only when a limit is actually hit) | |
| | API usage and billing, month to date | Anthropic Usage & Cost Admin API (organization accounts only) | |
| **Codex** | Plan rate-limit % left plus reset time | Desktop connector (Codex CLI records the limits it receives from the server) | ChatGPT app usage: no public API |
| | OpenAI API usage and billing, month to date | OpenAI Admin API (covers the whole org, not just Codex) | |
| **Gemini** | Manual entry only | — | Gemini CLI doesn't store session stats in a format the connector supports. Gemini API billing needs Google Cloud Billing access, which isn't supported on the phone. |
| **Antigravity** | Manual entry only | — | Quota is shown only in Antigravity's in-app usage panel, and there's no supported API. QuotaPulse doesn't scrape it. |

Widget labels mean:

- `78% LEFT`: a real quota measurement.
- `12K TOKENS`: a token count.
- `$3.20 API`: API billing.
- `STALE`: the data is more than 6 hours old.
- `CONNECTOR REQUIRED`: pair the desktop connector first.
- `UNAVAILABLE`: no supported source exists for this provider.
- `AUTH REQUIRED`: a key was rejected or the pairing broke.

---

## Install

1. Build and install the app:
   ```
   gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   You can also push a `v*` tag, and the release workflow will build a signed APK.
2. Long-press the home screen, open **Widgets**, find **Diligent**, and add **QuotaPulse**. It opens at 4×2. Resize it smaller for the compact view, or taller (about 4×3) for the large view with the SYNC NOW and START/STOP LIVE buttons.
3. Open the app and tap the gauge icon on the dashboard to open the **QuotaPulse** screen.

## Connect sources

### Desktop connector (Codex quota, Claude Code tokens and limits)

These numbers only exist on your laptop. The connector, `tools/quotapulse-connector/quotapulse-connector.mjs` (Node 18+, no dependencies), reads them and sends them to your phone through a **secret GitHub gist**. The data is encrypted with AES-256-GCM before upload. The gist holds only ciphertext, and only your phone has the key.

```bash
# 1. A GitHub token with the "gist" scope: GITHUB_TOKEN, or `gh auth login`
# 2. See exactly what would be sent (numbers only, no prompts or paths)
node tools/quotapulse-connector/quotapulse-connector.mjs print
# 3. Create the relay and print the pairing code
node tools/quotapulse-connector/quotapulse-connector.mjs pair
```

Paste the `QP1.…` code into the app under **QuotaPulse → Desktop connector → CONNECT**. Treat it like a password.

The phone only sees data the laptop has pushed, so keep it updated in one of these ways:

```bash
# On demand
node tools/quotapulse-connector/quotapulse-connector.mjs push

# Continuously while you work (Ctrl+C to stop)
node tools/quotapulse-connector/quotapulse-connector.mjs watch --interval 120

# Or every 5 minutes in the background with Windows Task Scheduler
schtasks /Create /SC MINUTE /MO 5 /TN QuotaPulseConnector /TR "node \"C:\path\to\Diligent\tools\quotapulse-connector\quotapulse-connector.mjs\" push"
```

`unpair` deletes the gist and the local key file (`~/.quotapulse/connector.json`). Remove the connector in the app as well.

### Admin API keys (optional; API billing only)

- **Anthropic**: create an Admin API key (`sk-ant-admin…`) under Console → Settings → Admin keys. Admin keys are unavailable for individual accounts; you need an organization.
- **OpenAI**: an organization owner can create an admin key under Platform → Settings → Admin keys.

Keys are encrypted with the Android Keystore and never appear in the widget, logs, intents or links. Admin keys are powerful, so use a dedicated key that you can revoke.

### Manual entry

On any provider's detail screen, type the remaining % from that tool's own usage screen. It shows on the widget as `MANUAL`, with the time you saved it.

---

## Manual sync and live mode

**Manual (the default after install, reboot or app restart)**

- Nothing polls in the background: no timers, no periodic jobs, no wake locks, no open connections. `updatePeriodMillis` is 0.
- **SYNC** queues one coalesced job. Tapping it repeatedly while a sync is pending doesn't add requests.
- Each connected source is fetched independently with a 30-second timeout. If one fails, its last good data stays visible next to the error, and the other sources still update.
- The widget only ever draws cached data. Adding or resizing it, rebooting, or going offline never triggers a network call.
- One non-waking alarm redraws the widget at the exact moment data turns stale or a quota window resets. Redraws don't make network requests.

**Live mode (explicit)**

- **START LIVE** (widget large view, or app) starts a foreground service of type `dataSync` with a notification that has a **STOP LIVE** button. **STOP LIVE** also works from the widget and the app.
- Interval: 60, 90, 120 (default) or 300 seconds, doubled in battery saver (shown as `LIVE · SAVER`). Per-source floors also apply: the connector gist is read at most every 2 minutes (GitHub allows 60 unauthenticated reads per hour), and the Admin APIs at most once a minute (Anthropic's documented polling rate).
- Rate limits respect `Retry-After` and GitHub's reset header. Network errors back off exponentially (1, 2, 4 … up to 30 minutes). A rejected key is not retried until you change it.
- It never restarts on its own. If Android stops it, or Android 15's 6-hour daily data-sync limit is reached, the widget shows `INTERRUPTED` with the reason. A saved "live" flag alone never makes the widget say `LIVE`.

**Request budget (estimated, not measured)**

| Mode | Requests |
|---|---|
| Manual, idle | 0 |
| One SYNC (connector + both Admin APIs) | about 5: 1 gist read (a ~0 KB 304 if nothing changed) plus 2 per Admin API |
| Live at 120 s, all sources | about 150 per hour, plus 30 widget redraws per hour |
| Live at 120 s, connector only | about 30 per hour, plus 30 widget redraws per hour |

There are no on-device battery measurements yet. They need the phone itself; see *Still to verify* below.

---

## Security

- Credentials live only in Keystore-encrypted storage (`SecretStore`). The widget state and preferences hold no secrets.
- Nothing is logged that contains a key. HTTP request headers are never logged.
- The connector sends only aggregate numbers, plus your computer's hostname (override it with `--label`). It never sends prompts, transcripts, file paths or project names.
- The connector payload is validated on the phone. Unknown providers are ignored, values are clamped, timestamps from the future are capped, and API billing is never accepted from the connector.
- No local HTTP servers are opened on the laptop or the phone, and no browser cookies or private app data are read.

---

## Still to verify on the Nothing Phone (2a) Plus

None of these have been tested on a real device yet:

- [ ] The widget appears in the picker and looks right on the Nothing launcher at compact, standard (4×2) and large (4×3) sizes, including the font and translucency.
- [ ] Taps: header opens QuotaPulse, a provider opens its detail screen, SYNC, START LIVE and STOP LIVE.
- [ ] Live-mode notification, STOP from the notification, and Nothing OS background restrictions.
- [ ] Android 15's 6-hour data-sync timeout shows `INTERRUPTED`.
- [ ] Battery drain in manual and live mode (Settings → Battery, or `adb shell dumpsys batterystats`).
- [ ] An end-to-end connector round trip (`pair`, then paste the code, then SYNC) with your real Codex and Claude Code data.
