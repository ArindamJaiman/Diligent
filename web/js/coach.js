/*
 * The weekly coach letter, as in the app (Insights → coach): a short, specific read of the last
 * two weeks, written by Claude with *your* Anthropic API key, called straight from this browser.
 *
 * Only a numeric summary is sent: daily share of goals, hours, mood/energy, focus by hour,
 * per-activity this-week-vs-last and the mood pattern. Activity names go too; notes, Top 3
 * and clips never do. The key is kept in this browser only (its own localStorage entry, outside
 * the synced and exported data) and sent only to api.anthropic.com.
 */
(function () {
  "use strict";
  const D = (window.Diligent = window.Diligent || {});
  const S = D.store, state = S.state;
  const KEY = "diligent.anthropic.key";
  const MODEL = "claude-opus-5-5";

  // The app's prompt (insights/AiCoach.kt), minus sleep and steps, which the web doesn't track.
  const SYSTEM_PROMPT = `You are a warm, direct productivity coach writing a short weekly letter to someone who
tracks their deep work, habits, mood and focus in an app called Diligent.
From the JSON summary, write about 150-220 words in plain text (no markdown headings):
open with one specific win, name the one or two patterns that matter most (cite the
numbers), and end with exactly three small, concrete experiments for next week, each on
its own line starting with "→". If the numbers show a heavy load with falling mood or energy,
put rest first. Don't invent data that isn't in the summary, and don't lecture.`;

  const getKey = () => { try { return localStorage.getItem(KEY) || ""; } catch { return ""; } };
  const setKey = (k) => { try { k ? localStorage.setItem(KEY, k.trim()) : localStorage.removeItem(KEY); } catch {} };
  const round1 = (x) => Math.round(x * 10) / 10;

  /** What gets sent, also shown before sending. */
  function summary() {
    const t = S.today(), days = S.range(S.addDays(t, -13), t);
    const sessions = state.sessions.filter((x) => S.iso(new Date(x.start)) >= days[0]);
    const byHour = new Array(24).fill(0);
    sessions.forEach((x) => { byHour[new Date(x.start).getHours()] += x.minutes; });
    const week = S.range(S.addDays(t, -6), t), last = S.range(S.addDays(t, -13), S.addDays(t, -7));
    const total = (a, ds) => ds.reduce((n, d) => n + S.progressOf(d, a.id), 0);
    const moods = days.map((d) => state.dayLogs[d]).filter((l) => l && l.mood);
    const good = days.filter((d) => (state.dayLogs[d] || {}).mood >= 4 && S.dayShare(d) != null);
    const rest = days.filter((d) => !good.includes(d) && S.dayShare(d) != null);
    const avg = (ds) => ds.length ? Math.round(ds.reduce((n, d) => n + S.dayShare(d), 0) / ds.length * 100) : null;
    const patterns = [];
    if (good.length >= 2 && rest.length >= 2) patterns.push(`On days rated mood 4-5 you hit ${avg(good)}% of goals vs ${avg(rest)}% on other days.`);
    const peak = byHour.indexOf(Math.max(...byHour));
    if (sessions.length >= 3) patterns.push(`Most focus minutes start around ${peak}:00.`);
    return {
      today: t,
      days: days.map((d) => {
        const log = state.dayLogs[d] || {}, share = S.dayShare(d);
        const o = { date: d, hours: round1(S.hoursOn(d)), focus_sessions: sessions.filter((x) => S.iso(new Date(x.start)) === d).length };
        if (share != null) o.goals_hit_pct = Math.round(share * 100);
        if (log.mood) o.mood_1_5 = log.mood;
        if (log.energy) o.energy_1_5 = log.energy;
        return o;
      }),
      this_week_vs_last: S.active().map((a) => ({ activity: a.name, unit: a.unit, schedule: S.describe(a), this_week: round1(total(a, week)), last_week: round1(total(a, last)), streak: S.streak(a) })),
      focus_14d: {
        sessions: sessions.length,
        minutes: sessions.reduce((n, x) => n + x.minutes, 0),
        avg_minutes: sessions.length ? Math.round(sessions.reduce((n, x) => n + x.minutes, 0) / sessions.length) : 0,
        minutes_by_hour: byHour,
      },
      mood_days_rated: moods.length,
      patterns,
    };
  }

  /** Writes the letter; throws with a readable message. */
  async function write(data) {
    const key = getKey();
    if (!key) throw new Error("Add your Anthropic API key first");
    let res;
    try {
      res = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-api-key": key,
          "anthropic-version": "2026-10-21",
          // A declined request is retried server-side on the model Anthropic recommends for it.
          "anthropic-beta": "server-side-fallback-2026-07-01",
          // Your own key, from your own browser: no server of ours in between.
          "anthropic-dangerous-direct-browser-access": "true",
        },
        body: JSON.stringify({
          model: MODEL,
          max_tokens: 16000,
          fallbacks: "default",
          system: SYSTEM_PROMPT,
          messages: [{ role: "user", content: "Here is my last two weeks as JSON:\n" + JSON.stringify(data) }],
        }),
      });
    } catch {
      throw new Error("Couldn't reach api.anthropic.com (offline, or blocked by an extension?)");
    }
    let body = null;
    try { body = await res.json(); } catch {}
    if (!res.ok) {
      const msg = body && body.error && body.error.message;
      if (res.status === 401) throw new Error("The API key was rejected; check it in Stats → Coach letter");
      if (res.status === 429) throw new Error("Rate limited by the API; try again in a minute");
      if (res.status === 529 || res.status >= 500) throw new Error("The API is busy right now; try again shortly");
      throw new Error(`API error ${res.status}${msg ? ": " + msg : ""}`);
    }
    if (body.stop_reason === "refusal") throw new Error("Claude declined to write this one; try again later");
    const text = (body.content || []).filter((b) => b.type === "text").map((b) => b.text).join("\n").trim();
    if (!text) throw new Error("Empty reply");
    state.coach = { letter: text, at: Date.now(), model: body.model || MODEL };
    S.save();
    return state.coach;
  }

  D.coach = { summary, write, getKey, setKey, MODEL };
})();
