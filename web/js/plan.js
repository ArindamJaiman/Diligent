/*
 * Plan: a day's timeline of activity blocks around your calendar.
 *
 * - Calendar: an imported .ics file (Google Calendar, Outlook, Apple Calendar export). Timed
 *   events become busy blocks; DAILY/WEEKLY recurrences (INTERVAL, BYDAY, UNTIL, COUNT, EXDATE)
 *   are expanded per day. TZID times are read as local time, UTC ("Z") times are converted.
 *   All-day events don't block time.
 * - Blocks: { id, activityId, start (minutes from midnight), minutes } per date.
 * - Auto-plan: what's still open today goes into the free gaps, biggest first (deep work
 *   early), in chunks of at most 90 minutes with a 15-minute breather after each.
 */
(function () {
  "use strict";
  const D = (window.Diligent = window.Diligent || {});
  const S = D.store, state = S.state;
  state.plans = state.plans || {};
  state.calendar = state.calendar || { events: [], name: "", importedAt: 0 };

  const STEP = 15, MAX_CHUNK = 90, BREATHER = 15, MIN_CHUNK = 30;
  const settings = () => ({ start: state.settings.planStart ?? 8, end: state.settings.planEnd ?? 23 });

  // ─── .ics ────────────────────────────────────────────
  function unfold(text) { return text.replace(/\r\n/g, "\n").replace(/\n[ \t]/g, ""); }
  function parseDate(value, params) {
    const m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?$/.exec(value.trim());
    if (!m) return null;
    const [, y, mo, d, h, mi, s, z] = m;
    if (h == null || /VALUE=DATE(?!-)/.test(params)) return { allDay: true, t: new Date(+y, +mo - 1, +d).getTime() };
    return { allDay: false, t: z ? Date.UTC(+y, +mo - 1, +d, +h, +mi, +(s || 0)) : new Date(+y, +mo - 1, +d, +h, +mi, +(s || 0)).getTime() };
  }
  function parseDuration(v) {
    const m = /^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(v.trim());
    if (!m) return 0;
    return ((+m[2] || 0) * 7 * 1440 + (+m[3] || 0) * 1440 + (+m[4] || 0) * 60 + (+m[5] || 0) + (+m[6] || 0) / 60) * 60000;
  }
  /** → [{ title, start, end, rrule, exdates }], timed events only. */
  function parseIcs(text) {
    const events = [];
    let cur = null;
    for (const line of unfold(text).split("\n")) {
      if (line === "BEGIN:VEVENT") { cur = { title: "Busy", exdates: [] }; continue; }
      if (line === "END:VEVENT") {
        if (cur && cur.start && !cur.allDay) {
          if (!cur.end) cur.end = cur.start + (cur.duration || 3600000);
          if (cur.status !== "CANCELLED" && cur.transp !== "TRANSPARENT") {
            events.push({ title: cur.title, start: cur.start, end: cur.end, rrule: cur.rrule || null, exdates: cur.exdates });
          }
        }
        cur = null;
        continue;
      }
      if (!cur) continue;
      const i = line.indexOf(":"); if (i < 0) continue;
      const head = line.slice(0, i), value = line.slice(i + 1);
      const [name, ...params] = head.split(";");
      const p = params.join(";");
      if (name === "SUMMARY") cur.title = value.replace(/\\,/g, ",").replace(/\\;/g, ";").replace(/\\n/gi, " ").trim() || "Busy";
      else if (name === "DTSTART") { const d = parseDate(value, p); if (d) { cur.start = d.t; cur.allDay = d.allDay; } }
      else if (name === "DTEND") { const d = parseDate(value, p); if (d) cur.end = d.t; }
      else if (name === "DURATION") cur.duration = parseDuration(value);
      else if (name === "RRULE") cur.rrule = Object.fromEntries(value.split(";").map((kv) => kv.split("=")));
      else if (name === "EXDATE") value.split(",").forEach((v) => { const d = parseDate(v, p); if (d) cur.exdates.push(S.iso(new Date(d.t))); });
      else if (name === "STATUS") cur.status = value.trim();
      else if (name === "TRANSP") cur.transp = value.trim();
    }
    return events;
  }

  const WEEKDAY = { MO: 1, TU: 2, WE: 3, TH: 4, FR: 5, SA: 6, SU: 0 };
  const daysBetween = (a, b) => Math.round((S.parse(b) - S.parse(a)) / 864e5);
  /** Does a recurring event (or a one-off) have an occurrence starting on this date? */
  function occursOn(ev, date) {
    const first = S.iso(new Date(ev.start));
    if (!ev.rrule) return first === date;
    if (date < first || ev.exdates.includes(date)) return false;
    const r = ev.rrule, interval = Math.max(1, +r.INTERVAL || 1);
    if (r.UNTIL) { const u = parseDate(r.UNTIL, ""); if (u && date > S.iso(new Date(u.t))) return false; }
    const gap = daysBetween(first, date);
    let index;
    if (r.FREQ === "DAILY") {
      if (gap % interval) return false;
      index = gap / interval;
    } else if (r.FREQ === "WEEKLY") {
      const days = r.BYDAY ? r.BYDAY.split(",").map((x) => WEEKDAY[x.slice(-2)]) : [new Date(ev.start).getDay()];
      if (!days.includes(S.parse(date).getDay())) return false;
      const monday = (d) => { const x = S.parse(d); x.setDate(x.getDate() - ((x.getDay() + 6) % 7)); return S.iso(x); };
      const weeks = Math.round(daysBetween(monday(first), monday(date)) / 7);
      if (weeks % interval) return false;
      index = (weeks / interval) * days.length;
    } else return false; // MONTHLY / YEARLY aren't expanded
    return !(r.COUNT && index >= +r.COUNT);
  }
  /** Busy intervals [startMin, endMin, title] on a date, from the imported calendar. */
  function busyOn(date) {
    const out = [];
    for (const ev of state.calendar.events) {
      // a one-off event may also run over midnight into this date
      for (const startDate of [date, S.addDays(date, -1)]) {
        if (!occursOn(ev, startDate)) continue;
        const s0 = new Date(ev.start), len = ev.end - ev.start;
        const start = S.parse(startDate); start.setHours(s0.getHours(), s0.getMinutes(), 0, 0);
        const dayStart = S.parse(date).getTime();
        const a = Math.max(0, (start.getTime() - dayStart) / 60000), b = Math.min(1440, (start.getTime() + len - dayStart) / 60000);
        if (b > a) out.push([a, b, ev.title]);
      }
    }
    return out.sort((x, y) => x[0] - y[0]);
  }
  function importIcs(text, name) {
    const events = parseIcs(text);
    if (!events.length && !/BEGIN:VCALENDAR/.test(text)) throw new Error("That isn't an .ics calendar file");
    // keep recurring events and anything from 30 days ago onwards
    const cutoff = Date.now() - 30 * 864e5;
    state.calendar = { events: events.filter((e) => e.rrule || e.end >= cutoff), name: name || "calendar.ics", importedAt: Date.now() };
    S.emit({ type: "plan" });
    return state.calendar.events.length;
  }
  function clearCalendar() { state.calendar = { events: [], name: "", importedAt: 0 }; S.emit({ type: "plan" }); }

  // ─── Blocks ──────────────────────────────────────────
  const blocks = (date) => (state.plans[date] ||= []);
  const snap = (m) => Math.round(m / STEP) * STEP;
  function add(date, activityId, start, minutes) {
    const b = { id: S.uid(), activityId, start: Math.max(0, Math.min(1440 - STEP, snap(start))), minutes: Math.max(STEP, snap(minutes)) };
    blocks(date).push(b);
    blocks(date).sort((x, y) => x.start - y.start);
    S.emit({ type: "plan" });
    return b;
  }
  function update(date, id, fields) {
    const b = blocks(date).find((x) => x.id === id); if (!b) return;
    if (fields.start != null) b.start = Math.max(0, Math.min(1440 - b.minutes, snap(fields.start)));
    if (fields.minutes != null) b.minutes = Math.max(STEP, Math.min(1440 - b.start, snap(fields.minutes)));
    blocks(date).sort((x, y) => x.start - y.start);
    S.emit({ type: "plan" });
  }
  function remove(date, id) { state.plans[date] = blocks(date).filter((b) => b.id !== id); S.emit({ type: "plan" }); }
  function clear(date) { state.plans[date] = []; S.emit({ type: "plan" }); }

  /** Minutes still needed today: time units from the goal, other units get a 30-minute slot. */
  function remaining(a, date) {
    if (S.isLimit(a) || S.met(a, date) || !S.isDue(a, date)) return 0;
    const p = S.progressOf(date, a.id);
    if (a.unit === "hrs") return Math.max(0, (a.goal - p) * 60);
    if (a.unit === "mins") return Math.max(0, a.goal - p);
    return 30;
  }
  function plannedMinutes(date, activityId) { return blocks(date).filter((b) => b.activityId === activityId).reduce((n, b) => n + b.minutes, 0); }
  /** What's left to place: activities still open minus what's already planned. */
  function open(date) {
    return S.active().map((a) => ({ a, left: Math.max(0, remaining(a, date) - plannedMinutes(date, a.id)) })).filter((x) => x.left >= STEP / 2);
  }

  /** Free gaps [start, end] in the day window, after now when it's today. */
  function gaps(date) {
    const { start, end } = settings();
    let from = start * 60;
    if (date === S.today()) {
      const now = new Date(); const nowMin = now.getHours() * 60 + now.getMinutes();
      from = Math.max(from, Math.ceil(nowMin / STEP) * STEP);
    }
    const taken = [...busyOn(date).map(([a, b]) => [a, b]), ...blocks(date).map((b) => [b.start, b.start + b.minutes + BREATHER])].sort((x, y) => x[0] - y[0]);
    const out = [];
    let cur = from;
    for (const [a, b] of taken) {
      if (a > cur) out.push([cur, Math.min(a, end * 60)]);
      cur = Math.max(cur, b);
    }
    if (cur < end * 60) out.push([cur, end * 60]);
    return out.filter(([a, b]) => b - a >= MIN_CHUNK);
  }

  /** Places what's left into free time; returns { placed, unplaced (minutes) }. */
  function autoPlan(date) {
    const items = open(date).sort((x, y) => y.left - x.left);
    let placed = 0, unplaced = 0;
    for (const it of items) {
      let left = Math.round(it.left / STEP) * STEP || STEP;
      while (left > 0) {
        const g = gaps(date)[0];
        if (!g) break;
        const len = Math.min(left, MAX_CHUNK, Math.floor((g[1] - g[0]) / STEP) * STEP);
        if (len < MIN_CHUNK && len < left) { // too small for this item: try the next gap that fits
          const fit = gaps(date).find(([a, b]) => b - a >= Math.min(left, MIN_CHUNK));
          if (!fit) break;
          const l2 = Math.min(left, MAX_CHUNK, Math.floor((fit[1] - fit[0]) / STEP) * STEP);
          blocks(date).push({ id: S.uid(), activityId: it.a.id, start: fit[0], minutes: l2 });
          left -= l2; placed++;
          continue;
        }
        blocks(date).push({ id: S.uid(), activityId: it.a.id, start: g[0], minutes: len });
        blocks(date).sort((x, y) => x.start - y.start);
        left -= len; placed++;
      }
      unplaced += Math.max(0, left);
    }
    blocks(date).sort((x, y) => x.start - y.start);
    S.emit({ type: "plan" });
    return { placed, unplaced };
  }

  /** The block running now (today only). */
  function current(date) {
    if (date !== S.today()) return null;
    const now = new Date(), m = now.getHours() * 60 + now.getMinutes();
    return blocks(date).find((b) => b.start <= m && m < b.start + b.minutes) || null;
  }

  D.plan = { STEP, settings, parseIcs, occursOn, busyOn, importIcs, clearCalendar, blocks, add, update, remove, clear, open, gaps, autoPlan, current, remaining };
})();
