/*
 * Diligent web: data, kept in this browser (localStorage), with the same rules as the app:
 * goal types (at least / at most / quit), streaks with skips, habit strength, units hrs, mins, qs…
 * Rows match the app's Supabase rows by activity name (see cloud.js).
 */
(function () {
  "use strict";
  const D = (window.Diligent = window.Diligent || {});
  const KEY = "diligent.web.v1";

  const DEFAULT_ACTIVITIES = [
    ["Quantum Computing", "hrs", 5], ["LeetCode", "qs", 3], ["HFT Preparation", "hrs", 2],
    ["Research Papers", "hrs", 2], ["Gym", "hrs", 1], ["Reading", "hrs", 1], ["Coding", "hrs", 3],
  ];
  const COLORS = ["#ffffff", "#4ade80", "#60a5fa", "#f472b6", "#fbbf24", "#a78bfa", "#2dd4bf", "#fb7185"];

  const uid = () => (crypto.randomUUID ? crypto.randomUUID() : "id-" + Math.random().toString(36).slice(2) + Date.now().toString(36));

  function blank() {
    return {
      version: 1,
      activities: DEFAULT_ACTIVITIES.map(([name, unit, goal], i) => ({
        id: uid(), name, unit, goal, goalType: "at_least", color: COLORS[i % COLORS.length], order: i, archived: false, createdAt: Date.now(),
      })),
      progress: {}, // date → activityId → { p, done, skip, note, t }
      dayLogs: {}, // date → { mood, energy, note, t }
      top3: {}, // date → [{ text, done }]
      sessions: [], // { activityId, start, end, minutes, intention, mode }
      focus: null, // { activityId, startedAt, mode: "timer"|"pomo", target, intention, phase, phaseStart }
      settings: { theme: "mono", dayStartHour: 0, sound: true, fluid: true, cursor: true, quotePack: "mix", name: "", notify: false, ambient: "off" },
      cloud: { url: "", key: "", owner: "", device: "", room: "", lastPull: 0, lastPush: 0, auto: true },
      clips: [], // this-browser clipboard: { id, text, kind, device, createdAt }
      xp: 0,
    };
  }

  function load() {
    try {
      const raw = localStorage.getItem(KEY);
      if (!raw) return blank();
      const data = JSON.parse(raw);
      const base = blank();
      return Object.assign(base, data, {
        settings: Object.assign(base.settings, data.settings || {}),
        cloud: Object.assign(base.cloud, data.cloud || {}),
      });
    } catch (e) {
      console.warn("Diligent: couldn't read saved data, starting fresh", e);
      return blank();
    }
  }

  const state = load();
  const listeners = new Set();
  let saveTimer = 0;

  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (e) { console.warn("Diligent: save failed", e); }
    }, 120);
  }
  function emit(reason) {
    save();
    listeners.forEach((fn) => fn(reason));
  }
  // Another tab changed the data: take it.
  window.addEventListener("storage", (e) => {
    if (e.key !== KEY || !e.newValue) return;
    try {
      const next = JSON.parse(e.newValue);
      Object.keys(state).forEach((k) => delete state[k]);
      Object.assign(state, blank(), next);
      listeners.forEach((fn) => fn("external"));
    } catch {}
  });

  // ─── Dates ────────────────────────────────────────────
  const pad = (n) => String(n).padStart(2, "0");
  const iso = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const parse = (s) => { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); };
  const addDays = (s, n) => { const d = parse(s); d.setDate(d.getDate() + n); return iso(d); };
  /** Today, honouring "day starts at" (1 AM work counts for the evening before). */
  function today() {
    const d = new Date(Date.now() - (state.settings.dayStartHour || 0) * 3600e3);
    return iso(d);
  }
  function range(from, to) { const out = []; for (let d = from; d <= to; d = addDays(d, 1)) out.push(d); return out; }

  // ─── Goals ────────────────────────────────────────────
  function rowOf(date, id) { return (state.progress[date] || {})[id]; }
  function progressOf(date, id) { const r = rowOf(date, id); return r ? r.p || 0 : 0; }
  function isLimit(a) { return a.goalType === "at_most" || a.goalType === "quit"; }
  function met(a, date) {
    const r = rowOf(date, a.id);
    const p = r ? r.p || 0 : 0;
    if (a.goalType === "at_most") return p <= a.goal;
    if (a.goalType === "quit") return p <= 0;
    return !!(r && r.done) || (a.goal > 0 && p >= a.goal);
  }
  function credit(a, date) {
    if (isLimit(a)) return met(a, date) ? 1 : 0;
    if (met(a, date)) return 1;
    return a.goal > 0 ? Math.min(1, progressOf(date, a.id) / a.goal) : 0;
  }
  const skipped = (a, date) => !!(rowOf(date, a.id) || {}).skip;
  const active = () => state.activities.filter((a) => !a.archived).sort((x, y) => x.order - y.order);
  const byId = (id) => state.activities.find((a) => a.id === id);

  /** Share of today's activities that are done (skips don't count against you). */
  function dayShare(date) {
    const list = active().filter((a) => !skipped(a, date) && since(a) <= date);
    if (!list.length) return null;
    const hasData = state.progress[date] && Object.keys(state.progress[date]).length;
    if (!hasData && date !== today()) return null;
    return list.reduce((n, a) => n + credit(a, date), 0) / list.length;
  }

  /** First day the activity existed (limits only count days since then). */
  function since(a) {
    const first = Object.keys(state.progress).sort().find((d) => state.progress[d][a.id]);
    const created = iso(new Date(a.createdAt || Date.now()));
    return first && first < created ? first : created;
  }
  function streak(a) {
    let n = 0, d = today();
    if (!met(a, d)) d = addDays(d, -1); // today is still open
    const start = since(a);
    for (let i = 0; i < 3000 && d >= start; i++, d = addDays(d, -1)) {
      if (skipped(a, d)) continue;
      if (met(a, d)) n++; else break;
    }
    return n;
  }
  function bestStreak(a) {
    let best = 0, cur = 0;
    for (const d of range(since(a), today())) {
      if (skipped(a, d)) continue;
      if (met(a, d)) { cur++; best = Math.max(best, cur); } else cur = 0;
    }
    return best;
  }
  /** Loop-style habit strength 0..1: exponential average of daily credit, 13-day half life. */
  function strength(a) {
    const alpha = 1 - Math.pow(0.5, 1 / 13);
    let s = 0;
    const start = addDays(today(), -120);
    for (const d of range(start, today())) {
      if (skipped(a, d)) continue;
      s = s + alpha * (credit(a, d) - s);
    }
    return s;
  }
  function hoursOf(a, p) { return a.unit === "hrs" ? p : a.unit === "mins" ? p / 60 : 0; }
  function hoursOn(date) { return active().reduce((n, a) => n + hoursOf(a, progressOf(date, a.id)), 0); }
  function overallStreak() {
    let n = 0, d = today();
    if (!(dayShare(d) >= 0.999)) d = addDays(d, -1);
    for (let i = 0; i < 2000; i++, d = addDays(d, -1)) { const s = dayShare(d); if (s != null && s >= 0.999) n++; else break; }
    return n;
  }
  /** "1 h 20 m", "3 qs" */
  function fmt(a, v) {
    if (a.unit === "hrs") { const m = Math.round(v * 60); return m < 60 ? `${m} m` : `${Math.floor(m / 60)} h${m % 60 ? " " + (m % 60) + " m" : ""}`; }
    if (a.unit === "mins") return `${Math.round(v)} m`;
    return `${+v.toFixed(2)} ${a.unit}`;
  }
  function step(a) { return a.unit === "hrs" ? 0.25 : a.unit === "mins" ? 15 : 1; }

  // ─── Mutations ────────────────────────────────────────
  function touch(date, id) {
    const day = (state.progress[date] ||= {});
    return (day[id] ||= { p: 0, done: false, skip: false, note: "", t: Date.now() });
  }
  function add(id, amount, date) {
    date = date || today();
    const a = byId(id); if (!a) return;
    const r = touch(date, id);
    const wasMet = met(a, date);
    r.p = Math.max(0, +(r.p + amount).toFixed(4));
    r.t = Date.now();
    if (!isLimit(a) && !wasMet && met(a, date)) state.xp += 10;
    emit({ type: "progress", id, date, completed: !wasMet && met(a, date) && !isLimit(a) });
  }
  function set(id, value, date) {
    date = date || today();
    const r = touch(date, id);
    r.p = Math.max(0, +value); r.t = Date.now();
    emit({ type: "progress", id, date });
  }
  function toggleDone(id, date) {
    date = date || today();
    const a = byId(id); const r = touch(date, id);
    const wasMet = met(a, date);
    r.done = !r.done;
    if (r.done && a.goal > 0 && r.p < a.goal && !isLimit(a)) r.p = a.goal;
    if (!r.done && r.p >= a.goal && !isLimit(a)) r.p = 0;
    r.t = Date.now();
    if (!wasMet && met(a, date)) state.xp += 10;
    emit({ type: "progress", id, date, completed: !wasMet && met(a, date) });
  }
  function toggleSkip(id, date) {
    date = date || today();
    const r = touch(date, id); r.skip = !r.skip; r.t = Date.now();
    emit({ type: "progress", id, date });
  }
  function note(id, text, date) {
    date = date || today();
    const r = touch(date, id); r.note = text; r.t = Date.now();
    emit({ type: "note", id, date });
  }
  function upsertActivity(fields) {
    let a = fields.id && byId(fields.id);
    if (a) Object.assign(a, fields);
    else {
      a = Object.assign({ id: uid(), unit: "hrs", goal: 1, goalType: "at_least", color: COLORS[state.activities.length % COLORS.length], order: state.activities.length, archived: false, createdAt: Date.now() }, fields);
      state.activities.push(a);
    }
    emit({ type: "activities" });
    return a;
  }
  function removeActivity(id) {
    const a = byId(id); if (!a) return;
    a.archived = true;
    emit({ type: "activities" });
  }
  function move(id, delta) {
    const list = active();
    const i = list.findIndex((a) => a.id === id), j = i + delta;
    if (i < 0 || j < 0 || j >= list.length) return;
    [list[i].order, list[j].order] = [list[j].order, list[i].order];
    list.forEach((a, k) => { if (k !== i && k !== j) a.order = k; });
    emit({ type: "activities" });
  }
  function dayLog(date) { return state.dayLogs[date || today()] || { mood: 0, energy: 0, note: "" }; }
  function setDayLog(date, fields) { state.dayLogs[date] = Object.assign(dayLog(date), fields, { t: Date.now() }); emit({ type: "daylog" }); }

  // ─── Quick log: "2h quantum", "+3 lc", "gym done yesterday", "skip reading", "focus coding 50" ──
  function matchActivity(words) {
    const q = words.trim().toLowerCase();
    if (!q) return null;
    let best = null, bestScore = 0;
    for (const a of active()) {
      const name = a.name.toLowerCase();
      const initials = name.split(/[\s\-_]+/).map((w) => w[0]).join("") + name.replace(/[^A-Z]/g, "");
      const camel = a.name.replace(/[^A-Z]/g, "").toLowerCase(); // LeetCode → lc
      let score = 0;
      if (name === q) score = 100;
      else if (name.startsWith(q)) score = 80;
      else if (name.split(/\s+/).some((w) => w.startsWith(q))) score = 60;
      else if (q === camel || initials.startsWith(q)) score = 55;
      else if (name.includes(q)) score = 40;
      if (score > bestScore) { bestScore = score; best = a; }
    }
    return best;
  }
  function parseQuick(input) {
    let text = " " + input.toLowerCase().trim() + " ";
    let date = today();
    if (/\byesterday\b|\byday\b/.test(text)) { date = addDays(date, -1); text = text.replace(/\byesterday\b|\byday\b/, " "); }
    const ago = text.match(/\b(\d+)\s*d(?:ays?)?\s*ago\b/);
    if (ago) { date = addDays(today(), -+ago[1]); text = text.replace(ago[0], " "); }
    text = text.trim();

    let m;
    if ((m = text.match(/^(?:focus|start|timer)\s+(.+?)(?:\s+(\d+)\s*(?:m|min|mins)?)?$/))) {
      const a = matchActivity(m[1]); if (!a) return null;
      return { kind: "focus", a, minutes: m[2] ? +m[2] : 0, date, label: `Start focus on ${a.name}${m[2] ? ` for ${m[2]} min` : ""}` };
    }
    if ((m = text.match(/^(?:skip)\s+(.+)$/)) || (m = text.match(/^(.+?)\s+skip$/))) {
      const a = matchActivity(m[1]); if (!a) return null;
      return { kind: "skip", a, date, label: `Skip ${a.name}` + (date !== today() ? ` on ${date}` : "") };
    }
    if ((m = text.match(/^(?:done|complete|finish(?:ed)?)\s+(.+)$/)) || (m = text.match(/^(.+?)\s+(?:done|complete|finished|✓)$/))) {
      const a = matchActivity(m[1]); if (!a) return null;
      return { kind: "done", a, date, label: `Mark ${a.name} done` + (date !== today() ? ` on ${date}` : "") };
    }
    // amounts: 2h, 1.5h, 2h30m, 90m, +3, 3, =4 (set)
    const amountRe = /([+=-]?)\s*(?:(\d+(?:\.\d+)?)\s*h(?:rs?|ours?)?\s*(?:(\d+)\s*m(?:in(?:s|utes?)?)?)?|(\d+(?:\.\d+)?)\s*m(?:in(?:s|utes?)?)?|(\d+(?:\.\d+)?))(?![a-z])/;
    m = text.match(new RegExp("^" + amountRe.source + "\\s+(.+)$")) || text.match(new RegExp("^(.+?)\\s+" + amountRe.source + "$"));
    if (!m) return null;
    let sign, h, hm, mins, plain, name;
    if (/^[+=-]?\s*\d/.test(text)) [, sign, h, hm, mins, plain, name] = m; else [, name, sign, h, hm, mins, plain] = m;
    const a = matchActivity(name); if (!a) return null;
    let minutes = null, count = null;
    if (h != null) minutes = +h * 60 + (hm ? +hm : 0);
    else if (mins != null) minutes = +mins;
    else count = +plain;
    let amount;
    if (minutes != null) amount = a.unit === "hrs" ? minutes / 60 : a.unit === "mins" ? minutes : minutes / 60;
    else amount = count;
    if (sign === "-") amount = -amount;
    const kind = sign === "=" ? "set" : "add";
    const verb = kind === "set" ? `Set ${a.name} to ${fmt(a, amount)}` : `${amount >= 0 ? "+" : "−"}${fmt(a, Math.abs(amount))} ${a.name}`;
    return { kind, a, amount, date, label: verb + (date !== today() ? ` on ${date}` : "") };
  }
  function runQuick(cmd) {
    if (!cmd) return false;
    if (cmd.kind === "add") add(cmd.a.id, cmd.amount, cmd.date);
    else if (cmd.kind === "set") set(cmd.a.id, cmd.amount, cmd.date);
    else if (cmd.kind === "done") { if (!met(cmd.a, cmd.date)) toggleDone(cmd.a.id, cmd.date); }
    else if (cmd.kind === "skip") toggleSkip(cmd.a.id, cmd.date);
    else if (cmd.kind === "focus" && D.focus) D.focus.start(cmd.a.id, { target: cmd.minutes });
    return true;
  }

  // ─── Backup ───────────────────────────────────────────
  function exportJson() { return JSON.stringify(Object.assign({}, state, { cloud: Object.assign({}, state.cloud, { key: "", clipPass: "" }), exportedAt: new Date().toISOString() }), null, 2); }

  /** Accepts this site's export or the Android app's backup (Settings → Backup). */
  function importJson(text) {
    const data = JSON.parse(text);
    if (Array.isArray(data.activities) && Array.isArray(data.dailyProgress)) {
      // Android BackupData
      const ids = {};
      let added = 0, rows = 0;
      data.activities.forEach((x, i) => {
        let a = state.activities.find((y) => y.name.toLowerCase() === String(x.name).toLowerCase());
        if (!a) {
          a = { id: uid(), name: x.name, unit: x.unit || "hrs", goal: +x.dailyGoal || 1, goalType: x.goalType || "at_least", color: x.color || COLORS[i % COLORS.length], order: x.sortOrder ?? i, archived: !!x.isArchived, createdAt: x.createdAt || Date.now() };
          state.activities.push(a);
          added++;
        }
        ids[x.id] = a.id;
      });
      data.dailyProgress.forEach((r) => {
        const id = ids[r.activityId]; if (!id || !r.date) return;
        const day = (state.progress[r.date] ||= {});
        const prev = day[id];
        if (prev && prev.t > (r.lastUpdated || 0)) return;
        day[id] = { p: +r.progress || 0, done: !!r.isCompleted, skip: !!r.isSkipped, note: r.notes || "", t: r.lastUpdated || Date.now(), rating: r.rating || 0 };
        rows++;
      });
      (data.dayLogs || []).forEach((l) => { if (l.date) state.dayLogs[l.date] = { mood: l.mood || 0, energy: l.energy || 0, note: l.note || "", t: l.updatedAt || Date.now() }; });
      emit({ type: "import" });
      return `Imported the app backup: ${added} new activities, ${rows} days`;
    }
    if (data.version === 1 && Array.isArray(data.activities)) {
      const keepCloud = state.cloud;
      Object.keys(state).forEach((k) => delete state[k]);
      Object.assign(state, blank(), data, { cloud: Object.assign({}, data.cloud || {}, { key: keepCloud.key || "" }) });
      emit({ type: "import" });
      return "Restored the web backup";
    }
    throw new Error("Not a Diligent backup");
  }
  function exportCsv() {
    const lines = ["date,activity,unit,progress,goal,completed,skipped,note"];
    Object.keys(state.progress).sort().forEach((d) => {
      Object.entries(state.progress[d]).forEach(([id, r]) => {
        const a = byId(id); if (!a) return;
        lines.push([d, a.name, a.unit, r.p, a.goal, met(a, d), !!r.skip, (r.note || "").replace(/[\n,]/g, " ")].map((v) => `"${String(v).replace(/"/g, '""')}"`).join(","));
      });
    });
    return lines.join("\n");
  }

  D.store = {
    state, uid, COLORS, today, iso, parse, addDays, range,
    on: (fn) => { listeners.add(fn); return () => listeners.delete(fn); }, emit, save,
    active, byId, rowOf, progressOf, met, credit, skipped, isLimit, dayShare, streak, bestStreak, strength, hoursOf, hoursOn, overallStreak, fmt, step,
    add, set, toggleDone, toggleSkip, note, upsertActivity, removeActivity, move, dayLog, setDayLog,
    parseQuick, runQuick, matchActivity, exportJson, importJson, exportCsv,
  };
})();
