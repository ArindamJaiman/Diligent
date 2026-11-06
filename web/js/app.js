/* Diligent web: views, focus timer, command palette, celebrations. */
(function () {
  "use strict";
  const D = window.Diligent;
  const S = D.store, C = D.cloud, state = S.state;
  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const main = $("#main");
  const VIEWS = ["today", "focus", "stats", "clipboard", "room", "settings"];
  let view = "today";
  let viewDate = S.today();

  // ─── Feedback: toasts, sound, notifications, ink ─────
  function toast(text, ms) {
    const el = document.createElement("div");
    el.className = "toast";
    el.textContent = text;
    $("#toasts").appendChild(el);
    setTimeout(() => { el.classList.add("out"); setTimeout(() => el.remove(), 400); }, ms || 2600);
  }
  let audio = null;
  function bloop(kind) {
    if (!state.settings.sound) return;
    try {
      audio = audio || new (window.AudioContext || window.webkitAudioContext)();
      const t = audio.currentTime;
      const notes = kind === "win" ? [520, 780, 1040] : kind === "soft" ? [380] : [620];
      notes.forEach((f, i) => {
        const o = audio.createOscillator(), g = audio.createGain();
        o.type = "sine";
        o.frequency.setValueAtTime(f, t + i * 0.09);
        o.frequency.exponentialRampToValueAtTime(f * 0.45, t + i * 0.09 + 0.16);
        g.gain.setValueAtTime(0.0001, t + i * 0.09);
        g.gain.exponentialRampToValueAtTime(0.16, t + i * 0.09 + 0.012);
        g.gain.exponentialRampToValueAtTime(0.0001, t + i * 0.09 + 0.24);
        o.connect(g).connect(audio.destination);
        o.start(t + i * 0.09);
        o.stop(t + i * 0.09 + 0.26);
      });
    } catch {}
  }
  function notify(body) {
    bloop("win");
    toast(body, 4000);
    if (state.settings.notify && "Notification" in window && Notification.permission === "granted" && document.hidden) {
      try { new Notification("Diligent", { body, icon: "icon.svg" }); } catch {}
    }
  }
  const hexRgb = (hex) => { const n = parseInt(String(hex || "#ffffff").slice(1), 16); return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255]; };
  function palette() {
    const theme = state.settings.theme;
    if (theme === "night") return [[1, 0.23, 0.19], [0.7, 0.1, 0.08], [1, 0.4, 0.3]];
    const cols = S.active().map((a) => hexRgb(a.color)).map((c) => c.map((v) => 0.45 + v * 0.55));
    return [[1, 1, 1], [0.85, 0.88, 1], ...cols];
  }
  let fluid = null;
  function inkAt(el, color, big) {
    if (!fluid || !el) return;
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    if (big) fluid.burst(x, y, color ? hexRgb(color) : null);
    else fluid.splatAt(x, y, color ? hexRgb(color).map((v) => v * 0.3) : null);
  }

  // ─── Quotes ──────────────────────────────────────────
  function quotes() {
    const packs = D.QUOTE_PACKS || [];
    const pick = state.settings.quotePack;
    const list = pick === "mix" ? packs : packs.filter((p) => p.id === pick);
    return list.flatMap((p) => p.quotes.map((q) => ({ q, by: p.name })));
  }
  let quote = null;
  function nextQuote() { const list = quotes(); quote = list.length ? list[Math.floor(Math.random() * list.length)] : { q: "Show up. Every day.", by: "Diligent" }; return quote; }

  // ─── Focus timer ─────────────────────────────────────
  const POMO_WORK = 25, POMO_BREAK = 5;
  const F = (D.focus = {
    start(activityId, opts) {
      opts = opts || {};
      if (state.focus) F.stop();
      const a = S.byId(activityId);
      if (!a) return;
      const now = Date.now();
      state.focus = { activityId, startedAt: now, mode: opts.mode || "timer", target: +opts.target || 0, intention: opts.intention || "", phase: "work", phaseStart: now, logged: 0, notified: false };
      S.emit({ type: "focus" });
      syncAmbient();
      bloop("soft");
      toast(`Focusing on ${a.name}`);
      C.pushPresence().catch(() => {});
    },
    stop(silent) {
      const f = state.focus;
      if (!f) return;
      const now = Date.now();
      const minutes = f.mode === "pomo" ? (f.phase === "work" ? (now - f.phaseStart) / 60000 : 0) : (now - f.startedAt) / 60000;
      F.log(f, minutes);
      const total = f.logged;
      if (total >= 0.5) state.sessions.unshift({ activityId: f.activityId, start: f.startedAt, end: now, minutes: Math.round(total), intention: f.intention, mode: f.mode });
      state.sessions = state.sessions.slice(0, 500);
      state.focus = null;
      S.emit({ type: "focus" });
      syncAmbient();
      updatePip();
      const a = S.byId(f.activityId);
      if (!silent) toast(total >= 0.5 ? `Logged ${Math.round(total)} min to ${a ? a.name : "?"}` : "Under 30 s: nothing logged");
      C.pushPresence().catch(() => {});
    },
    log(f, minutes) {
      if (minutes < 0.5) return;
      const a = S.byId(f.activityId);
      f.logged += minutes;
      if (!a) return;
      const amount = a.unit === "hrs" ? minutes / 60 : a.unit === "mins" ? minutes : 0;
      if (amount > 0) S.add(a.id, Math.round(amount * 10000) / 10000);
    },
    tick() {
      const f = state.focus;
      if (!f) return;
      const now = Date.now();
      if (f.mode === "pomo") {
        const len = (f.phase === "work" ? POMO_WORK : POMO_BREAK) * 60000;
        while (now - f.phaseStart >= len && state.focus) {
          if (f.phase === "work") { F.log(f, POMO_WORK); notify("25 minutes logged. Take 5."); }
          else notify("Break over. Back to it.");
          f.phaseStart += len;
          f.phase = f.phase === "work" ? "break" : "work";
          S.save();
          break;
        }
      } else if (f.target && !f.notified && now - f.startedAt >= f.target * 60000) {
        f.notified = true;
        S.save();
        notify(`${f.target} minutes. Target reached.`);
      }
    },
    /** { elapsed ms, label, level 0..1 } for the readout. */
    readout() {
      const f = state.focus;
      if (!f) return null;
      const now = Date.now();
      if (f.mode === "pomo") {
        const len = (f.phase === "work" ? POMO_WORK : POMO_BREAK) * 60000;
        const left = Math.max(0, len - (now - f.phaseStart));
        return { ms: left, label: f.phase === "work" ? "Pomodoro · work" : "Pomodoro · break", level: 1 - left / len, down: true };
      }
      const el = now - f.startedAt;
      if (f.target) return { ms: el, label: `of ${f.target} min`, level: Math.min(1, el / (f.target * 60000)) };
      return { ms: el, label: "Timer", level: (el % 3600000) / 3600000 };
    },
  });
  // ─── Focus sounds: generated noise, so nothing to download ─
  const Ambient = {
    kind: "off", src: null, gain: null, lfo: null, previewTimer: 0,
    play(kind) {
      if (kind === this.kind) return;
      this.stop();
      this.kind = kind;
      if (kind === "off") return;
      try {
        audio = audio || new (window.AudioContext || window.webkitAudioContext)();
        const ctx = audio, len = ctx.sampleRate * 8, buf = ctx.createBuffer(1, len, ctx.sampleRate), data = buf.getChannelData(0);
        let last = 0;
        for (let i = 0; i < len; i++) {
          const white = Math.random() * 2 - 1;
          if (kind === "rain") data[i] = white;
          else { last = (last + 0.02 * white) / 1.02; data[i] = last * 3.5; }
        }
        // cross-fade the loop seam
        const fade = Math.floor(ctx.sampleRate * 0.25);
        for (let i = 0; i < fade; i++) { const k = i / fade; data[i] = data[i] * k + data[len - fade + i] * (1 - k); }
        const src = ctx.createBufferSource(); src.buffer = buf; src.loop = true; src.loopEnd = (len - fade) / ctx.sampleRate;
        const filter = ctx.createBiquadFilter();
        filter.type = kind === "rain" ? "bandpass" : "lowpass";
        filter.frequency.value = kind === "rain" ? 2400 : 900;
        filter.Q.value = kind === "rain" ? 0.4 : 0.7;
        const gain = ctx.createGain();
        const level = kind === "rain" ? 0.05 : 0.22;
        gain.gain.setValueAtTime(0.0001, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(level, ctx.currentTime + 1.5);
        src.connect(filter).connect(gain).connect(ctx.destination);
        if (kind === "waves") { // swell every ~9 s
          const lfo = ctx.createOscillator(), depth = ctx.createGain();
          lfo.frequency.value = 0.11; depth.gain.value = level * 0.8;
          lfo.connect(depth).connect(gain.gain); lfo.start(); this.lfo = lfo;
        }
        src.start();
        this.src = src; this.gain = gain;
      } catch { this.kind = "off"; }
    },
    stop() {
      if (this.src && audio) {
        const src = this.src, lfo = this.lfo, g = this.gain;
        g.gain.cancelScheduledValues(audio.currentTime);
        g.gain.setValueAtTime(Math.max(0.0001, g.gain.value), audio.currentTime);
        g.gain.exponentialRampToValueAtTime(0.0001, audio.currentTime + 0.8);
        setTimeout(() => { try { src.stop(); if (lfo) lfo.stop(); } catch {} }, 900);
      }
      this.src = this.gain = this.lfo = null;
      this.kind = "off";
    },
    /** Plays the sound you picked for a few seconds, unless a session is already playing one. */
    preview(kind) {
      clearTimeout(this.previewTimer);
      if (state.focus) return syncAmbient();
      this.play(kind);
      this.previewTimer = setTimeout(() => { if (!state.focus) this.stop(); }, 4000);
    },
  };
  function syncAmbient() {
    const f = state.focus;
    const want = f && !(f.mode === "pomo" && f.phase === "break") ? state.settings.ambient || "off" : "off";
    if (want !== Ambient.kind) Ambient.play(want);
  }

  // ─── Floating timer (Document Picture-in-Picture) ─────
  let pip = null;
  async function openPip() {
    if (pip || !("documentPictureInPicture" in window)) return;
    try { pip = await documentPictureInPicture.requestWindow({ width: 280, height: 200 }); } catch { return toast("The browser didn't open the floating timer"); }
    const theme = getComputedStyle(document.documentElement);
    const v = (n) => theme.getPropertyValue(n).trim();
    pip.document.head.innerHTML = `<title>Diligent</title><style>
      html,body{margin:0;height:100%;background:${v("--bg")};color:${v("--text")};font-family:"Doto","Space Mono",monospace;overflow:hidden}
      .tank{position:fixed;left:0;right:0;bottom:0;background:var(--c);opacity:.22;transition:height 1s cubic-bezier(.34,1.56,.64,1)}
      .wrap{position:relative;height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px}
      b{font-size:44px;font-weight:900}span{font-size:10px;letter-spacing:.24em;text-transform:uppercase;opacity:.7}
      button{margin-top:8px;border:0;border-radius:99px;padding:8px 14px;background:${v("--ink")};color:${v("--on-ink")};font:700 11px/1 "Space Mono",monospace;letter-spacing:.14em;cursor:pointer}
    </style>`;
    for (const link of document.querySelectorAll('link[rel="stylesheet"][href*="fonts.googleapis"]')) pip.document.head.appendChild(link.cloneNode());
    pip.document.body.innerHTML = `<div class="tank" id="t"></div><div class="wrap"><b id="c">--:--</b><span id="l"></span><button id="s">STOP &amp; LOG</button></div>`;
    pip.document.getElementById("s").onclick = () => F.stop();
    pip.addEventListener("pagehide", () => { pip = null; });
    updatePip();
  }
  function updatePip() {
    if (!pip) return;
    const r = F.readout();
    if (!r) { pip.close(); pip = null; return; }
    const a = S.byId(state.focus.activityId), d = pip.document;
    d.getElementById("c").textContent = clock(r.ms);
    d.getElementById("l").textContent = `${a ? a.name : ""} · ${r.label}`;
    const t = d.getElementById("t");
    t.style.height = Math.round(r.level * 100) + "%";
    t.style.setProperty("--c", a ? a.color : "#fff");
  }

  const clock = (ms) => { const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60; return (h ? h + ":" + String(m).padStart(2, "0") : String(m).padStart(2, "0")) + ":" + String(s % 60).padStart(2, "0"); };

  // ─── Router ──────────────────────────────────────────
  function go(next, push) {
    if (!VIEWS.includes(next)) next = "today";
    const changed = next !== view;
    view = next;
    if (push !== false && location.hash !== "#" + next) history.replaceState(null, "", "#" + next);
    $$("#tabs button").forEach((b) => b.classList.toggle("on", b.dataset.view === view));
    moveTabBlob();
    render(changed);
    if (view === "clipboard") { clipSeen = Date.now(); updateBadge(0); }
  }
  function moveTabBlob() {
    const btn = $(`#tabs button[data-view="${view}"]`);
    if (!btn) return;
    const tabs = $("#tabs");
    const x = btn.offsetLeft - 5, w = btn.offsetWidth;
    $$("#tabs .blob").forEach((b) => { b.style.transform = `translateX(${x}px)`; b.style.width = w + "px"; });
    if (tabs.scrollWidth > tabs.clientWidth) btn.scrollIntoView({ block: "nearest", inline: "center", behavior: "smooth" });
  }
  window.addEventListener("hashchange", () => go(location.hash.slice(1), false));
  window.addEventListener("resize", moveTabBlob);
  $("#tabs").addEventListener("click", (e) => { const b = e.target.closest("button[data-view]"); if (b) go(b.dataset.view); });

  let viewCleanup = null;
  function render(animate) {
    if (viewCleanup) { viewCleanup(); viewCleanup = null; }
    const html = { today: todayView, focus: focusView, stats: statsView, clipboard: clipView, room: roomView, settings: settingsView }[view]();
    main.innerHTML = `<section class="view${animate ? "" : " still"}">${html}</section>`;
    if (!animate) main.firstElementChild.style.animation = "none";
    const after = { today: todayAfter, focus: focusAfter, stats: statsAfter, clipboard: clipAfter, room: roomAfter, settings: settingsAfter }[view];
    viewCleanup = after && after() || null;
  }

  // ─── Today ───────────────────────────────────────────
  function dayTitle(date) {
    const d = S.parse(date), t = S.today();
    if (date === t) return "Today";
    if (date === S.addDays(t, -1)) return "Yesterday";
    return d.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
  }
  function tank(level, color, extra) {
    return `<div class="tank${extra ? " " + extra : ""}" style="--level:${level};--c:${color}"><div class="wave"></div><div class="wave w2"></div></div>`;
  }
  function cardLevel(a, date) {
    if (S.isLimit(a)) return a.goal > 0 ? Math.min(1, S.progressOf(date, a.id) / a.goal) : (S.progressOf(date, a.id) > 0 ? 1 : 0);
    return S.credit(a, date);
  }
  function cardMeta(a, date) {
    const p = S.progressOf(date, a.id);
    if (S.skipped(a, date)) return "Skipped · the streak is safe";
    if (a.goalType === "quit") return p > 0 ? "Slipped today" : `${S.streak(a)} days clean`;
    if (a.goalType === "at_most") return p <= a.goal ? `${S.fmt(a, Math.max(0, a.goal - p))} left under the limit` : `Over by ${S.fmt(a, p - a.goal)}`;
    if (S.met(a, date)) return p > a.goal ? `Goal met · +${S.fmt(a, p - a.goal)} extra` : "Goal met";
    return `${S.fmt(a, Math.max(0, a.goal - p))} to go`;
  }
  function actCard(a, date) {
    const p = S.progressOf(date, a.id), row = S.rowOf(date, a.id) || {};
    const met = S.met(a, date);
    const st = S.streak(a);
    const timed = a.unit === "hrs" || a.unit === "mins";
    const goalLabel = a.goalType === "at_most" ? `max ${S.fmt(a, a.goal)}` : a.goalType === "quit" ? "quit" : `/ ${S.fmt(a, a.goal)}`;
    return `<article class="card act spot${met && !S.isLimit(a) ? " met" : ""}${row.skip ? " skip" : ""}" data-id="${a.id}" style="--c:${esc(a.color)}">
      ${tank(0, esc(a.color))}
      <header><span class="dot"></span><h3 class="clickable" data-act="edit" title="Edit">${esc(a.name)}</h3>${st > 0 ? `<span class="chip${st >= 7 ? " hot" : ""}" title="Streak">${st >= 7 ? "🔥" : "◆"} ${st}</span>` : ""}</header>
      <div class="amount clickable" data-act="set" title="Set an exact value">${esc(S.fmt(a, p))}<small>${esc(goalLabel)}</small></div>
      <div class="meta">${esc(cardMeta(a, date))}</div>
      ${row.note ? `<div class="note">“${esc(row.note)}”</div>` : ""}
      <div class="ctl">
        <button class="btn round" data-act="dec" aria-label="Less" data-magnetic="0.3">−</button>
        <button class="btn round" data-act="inc" aria-label="More" data-magnetic="0.3">+</button>
        ${timed && date === S.today() ? `<button class="btn round" data-act="focus" aria-label="Focus timer" title="Focus timer" data-magnetic="0.3" style="font-size:15px">${state.focus && state.focus.activityId === a.id ? "■" : "⏱"}</button>` : ""}
        <button class="btn sm ghost" data-act="skip" title="Skip this day (keeps the streak)">${row.skip ? "unskip" : "skip"}</button>
        ${S.isLimit(a) ? "" : `<button class="btn round done" data-act="done" aria-label="Done" data-cursor="done" data-magnetic="0.3">✓</button>`}
      </div>
    </article>`;
  }
  function doneCount(date) {
    const goals = S.active().filter((a) => !S.isLimit(a));
    return `${goals.filter((a) => S.met(a, date)).length}/${goals.length} done`;
  }
  function todayView() {
    const date = viewDate, share = S.dayShare(date) || 0, t = S.today();
    const q = quote || nextQuote();
    const d = S.parse(date);
    const top = state.top3[date] || [];
    const log = S.dayLog(date);
    const acts = S.active();
    return `
      <div class="card hero spot">
        <div class="orb" id="orb">${tank(0, "var(--text)")}
          <div class="pct"><div><b id="orb-pct">${Math.round(share * 100)}</b><span>PERCENT</span></div></div></div>
        <div>
          <h1>${esc(d.toLocaleDateString(undefined, { weekday: "long" }))}</h1>
          <div class="quote" id="quote" title="Tap for the next quote">${esc(q.q)} <span class="muted small">· ${esc(q.by)}</span></div>
          <div class="quick"><input id="quick" placeholder="Quick log: 2h quantum · +3 lc · gym done · focus coding 50" autocomplete="off" spellcheck="false" aria-label="Quick log"><span class="hint" id="quick-hint"></span></div>
        </div>
      </div>
      <div class="daynav">
        <button class="btn round ghost" id="prev-day" aria-label="Previous day" data-magnetic="0.3">‹</button>
        <h3>${esc(dayTitle(date))}</h3>
        <button class="btn round ghost" id="next-day" aria-label="Next day" data-magnetic="0.3" ${date >= t ? "disabled" : ""}>›</button>
        ${date !== t ? `<button class="btn sm ghost" id="to-today">Back to today</button>` : ""}
        <span class="spacer"></span>
        <span class="tiny" id="done-count">${doneCount(date)}</span>
      </div>
      <div class="grid two">
        <div class="grid acts" id="acts">
          ${acts.map((a) => actCard(a, date)).join("")}
          <div class="card add-card" id="add-act" data-cursor="new">+ NEW ACTIVITY</div>
        </div>
        <div class="stack">
          ${state.focus ? focusMini() : ""}
          <div class="card top3 spot">
            <p class="label">Top 3 <span class="grow"></span><span class="tiny">${top.filter((x) => x.done).length}/3</span></p>
            ${[0, 1, 2].map((i) => { const it = top[i] || { text: "", done: false }; return `<div class="item${it.done ? " done" : ""}"><button class="check${it.done ? " on" : ""}" data-top="${i}" aria-label="Done"></button><input type="text" data-top-text="${i}" value="${esc(it.text)}" placeholder="Must-do #${i + 1}" maxlength="80"></div>`; }).join("")}
          </div>
          <div class="card spot">
            <p class="label">Day log</p>
            <div class="row"><span class="tiny" style="width:60px">MOOD</span><div class="scale" data-scale="mood">${[1, 2, 3, 4, 5].map((n) => `<button class="${log.mood === n ? "on" : ""}" data-n="${n}">${n}</button>`).join("")}</div></div>
            <div class="row" style="margin-top:8px"><span class="tiny" style="width:60px">ENERGY</span><div class="scale" data-scale="energy">${[1, 2, 3, 4, 5].map((n) => `<button class="${log.energy === n ? "on" : ""}" data-n="${n}">${n}</button>`).join("")}</div></div>
            <textarea id="day-note" placeholder="What went well? What got in the way?" style="margin-top:12px">${esc(log.note)}</textarea>
          </div>
        </div>
      </div>`;
  }
  function focusMini() {
    const f = state.focus, a = S.byId(f.activityId), r = F.readout();
    return `<div class="card spot" style="--c:${esc(a ? a.color : "#fff")}">${tank(r.level, esc(a ? a.color : "#fff"))}
      <p class="label">Focusing <span class="grow"></span><span class="live">LIVE</span></p>
      <div class="row"><b style="font:900 30px/1 var(--font-dot)" id="mini-clock">${clock(r.ms)}</b><span class="muted small">${esc(a ? a.name : "")}</span><span class="spacer"></span><button class="btn sm" id="mini-stop">Stop</button></div></div>`;
  }
  function todayAfter() {
    const date = viewDate;
    requestAnimationFrame(() => requestAnimationFrame(() => { // fill from empty, so the liquid pours in
      $$("#acts .act").forEach((card) => { const a = S.byId(card.dataset.id); if (a) $(".tank", card).style.setProperty("--level", cardLevel(a, date)); });
      const orb = $("#orb .tank"); if (orb) orb.style.setProperty("--level", S.dayShare(date) || 0);
    }));
    $("#prev-day").onclick = () => { viewDate = S.addDays(viewDate, -1); render(false); };
    $("#next-day").onclick = () => { if (viewDate < S.today()) { viewDate = S.addDays(viewDate, 1); render(false); } };
    const tt = $("#to-today"); if (tt) tt.onclick = () => { viewDate = S.today(); render(false); };
    $("#quote").onclick = () => { const el = $("#quote"); el.classList.add("swap"); setTimeout(() => { const q = nextQuote(); el.innerHTML = `${esc(q.q)} <span class="muted small">· ${esc(q.by)}</span>`; el.classList.remove("swap"); }, 350); };
    $("#add-act").onclick = () => openEditor(null);
    const quick = $("#quick"), hint = $("#quick-hint");
    quick.oninput = () => { const c = S.parseQuick(quick.value); hint.textContent = c ? "↵ " + c.label : quick.value ? "…" : ""; };
    quick.onkeydown = (e) => {
      if (e.key !== "Enter") return;
      const c = S.parseQuick(quick.value);
      if (!c) { toast("Couldn't read that. Try “2h quantum” or “+3 lc”."); return; }
      S.runQuick(c); toast(c.label); quick.value = ""; hint.textContent = "";
    };
    $("#acts").addEventListener("click", (e) => {
      const btn = e.target.closest("[data-act]"); if (!btn) return;
      const card = btn.closest(".act"), a = S.byId(card.dataset.id); if (!a) return;
      const act = btn.dataset.act;
      if (act === "inc") { S.add(a.id, S.step(a), date); inkAt(btn, a.color); }
      else if (act === "dec") S.add(a.id, -S.step(a), date);
      else if (act === "done") S.toggleDone(a.id, date);
      else if (act === "skip") S.toggleSkip(a.id, date);
      else if (act === "edit") openEditor(a);
      else if (act === "focus") { if (state.focus && state.focus.activityId === a.id) F.stop(); else F.start(a.id); }
      else if (act === "set") {
        const v = prompt(`${a.name} on ${date} (${a.unit})`, S.progressOf(date, a.id));
        if (v != null && v.trim() !== "" && !isNaN(+v)) S.set(a.id, +v, date);
      }
    });
    $$("[data-top]").forEach((b) => b.onclick = () => {
      const i = +b.dataset.top, list = (state.top3[date] ||= []);
      list[i] = list[i] || { text: "", done: false };
      list[i].done = !list[i].done;
      if (list[i].done) { bloop(); inkAt(b, "#4ade80"); }
      S.emit({ type: "top3" });
    });
    $$("[data-top-text]").forEach((inp) => inp.oninput = () => {
      const i = +inp.dataset.topText, list = (state.top3[date] ||= []);
      list[i] = Object.assign(list[i] || { done: false }, { text: inp.value });
      S.save();
    });
    $$("[data-scale]").forEach((g) => g.onclick = (e) => {
      const b = e.target.closest("button"); if (!b) return;
      const field = g.dataset.scale, n = +b.dataset.n;
      state.dayLogs[date] = Object.assign(S.dayLog(date), { [field]: S.dayLog(date)[field] === n ? 0 : n, t: Date.now() });
      S.save();
      $$("button", g).forEach((x) => x.classList.toggle("on", +x.dataset.n === state.dayLogs[date][field]));
    });
    $("#day-note").oninput = (e) => { state.dayLogs[date] = Object.assign(S.dayLog(date), { note: e.target.value, t: Date.now() }); S.save(); };
    const ms = $("#mini-stop"); if (ms) ms.onclick = () => F.stop();
  }
  /** Update one card in place, so the liquid level animates instead of re-rendering. */
  function patchToday(ev) {
    if (view !== "today" || ev.date !== viewDate) return false;
    const card = $(`#acts .act[data-id="${ev.id}"]`), a = S.byId(ev.id);
    if (!card || !a) return false;
    const tmp = document.createElement("div");
    tmp.innerHTML = actCard(a, viewDate);
    const fresh = tmp.firstElementChild;
    const tankEl = $(".tank", card);
    // swap everything but the tank, then move the level
    Array.from(card.children).forEach((ch) => { if (ch !== tankEl) ch.remove(); });
    Array.from(fresh.children).forEach((ch) => { if (!ch.classList.contains("tank")) card.appendChild(ch); });
    card.className = fresh.className;
    tankEl.style.setProperty("--level", cardLevel(a, viewDate));
    tankEl.classList.remove("slosh"); void tankEl.offsetWidth; tankEl.classList.add("slosh");
    const share = S.dayShare(viewDate) || 0;
    const orb = $("#orb .tank"); if (orb) orb.style.setProperty("--level", share);
    const pct = $("#orb-pct"); if (pct) pct.textContent = Math.round(share * 100);
    const dc = $("#done-count"); if (dc) dc.textContent = doneCount(viewDate);
    if (ev.completed) {
      card.classList.add("splash");
      inkAt(card, a.color, true);
      bloop("win");
      if (share >= 0.999) setTimeout(() => { toast("Perfect day ✦ every goal hit"); inkAt($("#orb"), "#4ade80", true); }, 500);
    }
    return true;
  }

  // ─── Focus view ──────────────────────────────────────
  let focusPick = { activityId: null, mode: "timer", target: 50 };
  function focusView() {
    const f = state.focus, r = F.readout();
    const timed = S.active().filter((a) => a.unit === "hrs" || a.unit === "mins");
    if (!focusPick.activityId || !S.byId(focusPick.activityId)) focusPick.activityId = (state.sessions[0] && S.byId(state.sessions[0].activityId) ? state.sessions[0].activityId : (timed[0] || {}).id);
    const a = f ? S.byId(f.activityId) : S.byId(focusPick.activityId);
    const color = a ? a.color : "#ffffff";
    const today = S.today();
    const todays = state.sessions.filter((s) => S.iso(new Date(s.start)) === today);
    const byHour = new Array(24).fill(0);
    state.sessions.forEach((s) => { byHour[new Date(s.start).getHours()] += s.minutes; });
    const maxH = Math.max(1, ...byHour);
    const bestHour = byHour.indexOf(Math.max(...byHour));
    const avg = state.sessions.length ? Math.round(state.sessions.reduce((n, s) => n + s.minutes, 0) / state.sessions.length) : 0;
    return `
      <div class="grid two">
        <div class="card spot timer-wrap" style="--c:${esc(color)}">
          <div class="timer${f ? " running" : ""}" id="timer">${tank(r ? r.level : 0, esc(color))}
            <div class="readout"><b id="timer-clock">${r ? clock(r.ms) : focusPick.mode === "pomo" ? "25:00" : "00:00"}</b><span id="timer-label">${r ? esc(r.label) + " · " + esc(a ? a.name : "") : "Ready"}</span></div>
          </div>
          ${f ? `<p class="muted small" style="margin:18px 0 0">${f.intention ? "“" + esc(f.intention) + "”" : "Logged to " + esc(a ? a.name : "") + " when you stop."}</p>
            <div class="row" style="margin-top:16px"><button class="btn" id="stop" data-cursor="stop">Stop &amp; log</button><button class="btn ghost" id="cancel">Discard</button>${"documentPictureInPicture" in window ? `<button class="btn ghost" id="pip" data-cursor="float" title="Keep the timer on top of other windows">Float</button>` : ""}</div>`
          : `<div style="width:100%;max-width:440px;margin-top:22px">
            <label class="field" for="f-act">Activity</label>
            <select id="f-act">${timed.map((x) => `<option value="${x.id}" ${x.id === focusPick.activityId ? "selected" : ""}>${esc(x.name)}</option>`).join("")}</select>
            <div class="row" style="margin-top:12px">
              <div class="seg" id="f-mode"><button data-v="timer" class="${focusPick.mode === "timer" ? "on" : ""}">Timer</button><button data-v="pomo" class="${focusPick.mode === "pomo" ? "on" : ""}">Pomodoro 25/5</button></div>
              ${focusPick.mode === "timer" ? `<div class="seg" id="f-target">${[0, 25, 50, 90].map((m) => `<button data-v="${m}" class="${focusPick.target === m ? "on" : ""}">${m ? m + "m" : "∞"}</button>`).join("")}</div>` : ""}
            </div>
            <label class="field" for="f-int">Intention (optional)</label>
            <input id="f-int" placeholder="Finish the Grover's search chapter" maxlength="120">
            <label class="field">Focus sound</label>
            <div class="seg" id="f-sound">${[["off", "Off"], ["rain", "Rain"], ["brown", "Brown noise"], ["waves", "Waves"]].map(([v, l]) => `<button data-v="${v}" class="${(state.settings.ambient || "off") === v ? "on" : ""}">${l}</button>`).join("")}</div>
            <button class="btn" id="start" style="margin-top:16px;width:100%" ${timed.length ? "" : "disabled"} data-cursor="go">Start focus</button>
            ${timed.length ? "" : `<p class="tiny">Add an activity measured in hrs or mins first.</p>`}
          </div>`}
        </div>
        <div class="stack">
          <div class="card spot"><p class="label">Today</p>
            <div class="row"><b style="font:900 34px/1 var(--font-dot)">${todays.reduce((n, s) => n + s.minutes, 0)}</b><span class="muted small">min in ${todays.length} session${todays.length === 1 ? "" : "s"}</span></div></div>
          <div class="card spot"><p class="label">Best hours <span class="grow"></span><span class="tiny">${state.sessions.length ? "peak " + bestHour + ":00 · avg " + avg + " min" : ""}</span></p>
            <div class="hours">${byHour.map((m, h) => `<i style="height:${Math.max(2, m / maxH * 100)}%" title="${h}:00 · ${m} min"></i>`).join("")}</div>
            <div class="hours-axis"><span>0</span><span>6</span><span>12</span><span>18</span><span>23</span></div></div>
          <div class="card sessions spot"><p class="label">Sessions</p>
            ${state.sessions.slice(0, 8).map((s) => { const x = S.byId(s.activityId); return `<div class="s"><span class="dot" style="--c:${esc(x ? x.color : "#fff")};margin-top:5px"></span><span style="flex:1">${esc(x ? x.name : "?")}${s.intention ? `<br><span class="tiny">${esc(s.intention)}</span>` : ""}</span><span class="muted">${s.minutes} min · ${new Date(s.start).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span></div>`; }).join("") || `<p class="tiny">No sessions yet. They're kept here and in your stats.</p>`}
          </div>
        </div>
      </div>`;
  }
  function focusAfter() {
    const st = $("#start");
    if (st) st.onclick = () => { focusPick.activityId = $("#f-act").value; F.start(focusPick.activityId, { mode: focusPick.mode, target: focusPick.mode === "timer" ? focusPick.target : 0, intention: $("#f-int").value.trim() }); inkAt($("#timer"), (S.byId(focusPick.activityId) || {}).color, true); };
    const fa = $("#f-act"); if (fa) fa.onchange = () => { focusPick.activityId = fa.value; render(false); };
    const fm = $("#f-mode"); if (fm) fm.onclick = (e) => { const b = e.target.closest("button"); if (b) { focusPick.mode = b.dataset.v; render(false); } };
    const ft = $("#f-target"); if (ft) ft.onclick = (e) => { const b = e.target.closest("button"); if (b) { focusPick.target = +b.dataset.v; render(false); } };
    const fs = $("#f-sound"); if (fs) fs.onclick = (e) => { const b = e.target.closest("button"); if (b) { state.settings.ambient = b.dataset.v; S.save(); $$("button", fs).forEach((x) => x.classList.toggle("on", x === b)); Ambient.preview(b.dataset.v); } };
    const pp = $("#pip"); if (pp) pp.onclick = openPip;
    const sp = $("#stop"); if (sp) sp.onclick = () => { inkAt($("#timer"), (S.byId(state.focus.activityId) || {}).color, true); F.stop(); };
    const cn = $("#cancel"); if (cn) cn.onclick = () => { if (confirm("Discard this session without logging it?")) { const f = state.focus; f.mode = "timer"; f.startedAt = Date.now(); F.stop(true); toast("Discarded"); } };
  }

  // ─── Stats ───────────────────────────────────────────
  function statsView() {
    const t = S.today(), acts = S.active();
    const week = S.range(S.addDays(t, -6), t);
    const weekHours = week.reduce((n, d) => n + S.hoursOn(d), 0);
    const last30 = S.range(S.addDays(t, -29), t);
    const perfect30 = last30.filter((d) => (S.dayShare(d) || 0) >= 0.999).length;
    const allDates = Object.keys(state.progress);
    const totalHours = allDates.reduce((n, d) => n + S.hoursOn(d), 0);
    const level = Math.floor(Math.sqrt(totalHours / 2)) + 1, nextAt = 2 * level * level;
    const tiles = [
      [Math.round((S.dayShare(t) || 0) * 100) + "%", "Today"],
      [weekHours.toFixed(1), "Hours · 7 days"],
      [S.overallStreak(), "Perfect days in a row"],
      [perfect30, "Perfect days · 30"],
      [Math.round(totalHours), "Hours all time"],
      ["L" + level, `${Math.max(0, nextAt - totalHours).toFixed(0)} h to L${level + 1}`],
    ];
    // Heatmap, Monday-first columns over 53 weeks
    const end = S.parse(t), start = new Date(end); start.setDate(start.getDate() - 364 - ((end.getDay() + 6) % 7));
    const cells = [];
    for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
      const k = S.iso(d), s = S.dayShare(k);
      const bg = s == null ? "" : s >= 0.999 ? "background:var(--good);box-shadow:0 0 8px color-mix(in srgb,var(--good) 60%,transparent)" : `background:color-mix(in srgb, var(--text) ${Math.round(12 + 60 * s)}%, transparent)`;
      cells.push(`<i style="${bg}" data-date="${k}" data-cursor="edit" title="${k}: ${s == null ? "–" : Math.round(s * 100) + "%"} · click to edit"></i>`);
    }
    const days14 = S.range(S.addDays(t, -13), t);
    const n = Math.max(1, acts.length);
    const bars = days14.map((d) => `<div class="bar" title="${d}: ${Math.round((S.dayShare(d) || 0) * 100)}%">${acts.map((a) => { const c = S.skipped(a, d) ? 0 : S.credit(a, d); return c ? `<i style="height:${(c / n) * 100}%;background:${esc(a.color)};opacity:.85"></i>` : ""; }).join("")}</div>`).join("");
    const axis = days14.map((d) => `<span>${S.parse(d).toLocaleDateString(undefined, { weekday: "narrow" })}</span>`).join("");
    const rows = acts.map((a) => {
      const total = last30.reduce((s, d) => s + S.progressOf(d, a.id), 0);
      const pts = last30.map((d, i) => `${(i / 29) * 110},${24 - S.credit(a, d) * 22}`).join(" ");
      const str = S.strength(a);
      return `<tr style="--c:${esc(a.color)}"><td><span class="row"><span class="dot"></span>${esc(a.name)}</span></td>
        <td class="num">${S.streak(a)}</td><td class="num">${S.bestStreak(a)}</td>
        <td><div class="row"><div class="meter" style="flex:1"><i style="width:${Math.round(str * 100)}%"></i></div><span class="tiny">${Math.round(str * 100)}%</span></div></td>
        <td class="num">${esc(S.fmt(a, total))}</td>
        <td><svg class="spark" viewBox="0 0 110 26" preserveAspectRatio="none"><polyline points="${pts}" fill="none" stroke="${esc(a.color)}" stroke-width="1.5" stroke-linejoin="round"/></svg></td></tr>`;
    }).join("");
    const moods = last30.map((d) => state.dayLogs[d]).filter((l) => l && l.mood);
    const good = last30.filter((d) => (state.dayLogs[d] || {}).mood >= 4);
    const goodShare = good.length ? good.reduce((s, d) => s + (S.dayShare(d) || 0), 0) / good.length : null;
    const restShare = last30.filter((d) => !good.includes(d) && S.dayShare(d) != null);
    const rs = restShare.length ? restShare.reduce((s, d) => s + S.dayShare(d), 0) / restShare.length : null;
    return `
      <div class="row" style="margin-bottom:12px"><span class="label" style="margin:0">Your numbers</span><span class="spacer"></span><button class="btn ghost sm" id="weekly-card" data-cursor="share">Weekly card</button></div>
      <div class="grid tiles">${tiles.map(([v, l]) => `<div class="card tile spot"><b>${v}</b><span>${esc(l)}</span></div>`).join("")}</div>
      <div class="card spot" style="margin-top:16px"><p class="label">Last 12 months · share of goals hit <span class="grow"></span><span class="tiny">click a day to edit it</span></p><div class="heat" id="heat">${cells.join("")}</div></div>
      <div class="grid two" style="margin-top:16px">
        <div class="card spot"><p class="label">Last 14 days · goals hit by activity</p><div class="bars">${bars}</div><div class="bars-axis">${axis}</div></div>
        <div class="card spot"><p class="label">Mood & output · 30 days</p>
          ${moods.length ? `<p class="small">On days you rated <b>4–5</b> you hit <b>${Math.round((goodShare || 0) * 100)}%</b> of goals, vs <b>${Math.round((rs || 0) * 100)}%</b> on other days.</p>
          <p class="tiny">Average mood ${(moods.reduce((s, l) => s + l.mood, 0) / moods.length).toFixed(1)} · energy ${(moods.filter((l) => l.energy).reduce((s, l) => s + l.energy, 0) / Math.max(1, moods.filter((l) => l.energy).length)).toFixed(1)}</p>`
          : `<p class="tiny">Rate your mood in Today → Day log and this shows what goes with your good days.</p>`}
        </div>
      </div>
      <div class="card spot scroll-x" style="margin-top:16px"><p class="label">Activities</p>
        <table class="list"><thead><tr><th>Activity</th><th style="text-align:right">Streak</th><th style="text-align:right">Best</th><th>Strength</th><th style="text-align:right">30 days</th><th>Trend</th></tr></thead><tbody>${rows}</tbody></table></div>`;
  }
  // ─── Weekly card: a shareable PNG of the last 7 days ─
  async function weeklyCanvas() {
    const W = 1080, H = 1350;
    const cs = getComputedStyle(document.documentElement), v = (n) => cs.getPropertyValue(n).trim();
    const bg = v("--bg") || "#000", fg = v("--text") || "#fff", dim = v("--dim") || "#888", good = v("--good") || "#4ade80";
    await Promise.all(["900 80px Doto", "400 80px UnifrakturMaguntia", "400 20px 'Space Mono'", "700 20px 'Space Mono'"].map((f) => document.fonts.load(f).catch(() => {})));
    const cv = document.createElement("canvas"); cv.width = W; cv.height = H;
    const g = cv.getContext("2d");
    const t = S.today(), days = S.range(S.addDays(t, -6), t), acts = S.active();
    const shares = days.map((d) => S.dayShare(d) || 0);
    const weekShare = shares.reduce((a, b) => a + b, 0) / 7;
    const hours = days.reduce((n, d) => n + S.hoursOn(d), 0);
    const perfect = shares.filter((x) => x >= 0.999).length;
    const best = acts.map((a) => ({ a, s: S.streak(a) })).sort((x, y) => y.s - x.s)[0];
    const spaced = (txt, x, y, px) => { g.letterSpacing = px + "px"; g.fillText(txt, x, y); g.letterSpacing = "0px"; };

    g.fillStyle = bg; g.fillRect(0, 0, W, H);
    // liquid at the bottom, as high as the week went
    const level = H - 120 - weekShare * 380;
    for (const [alpha, amp, phase] of [[0.1, 22, 0], [0.06, 30, 1.7]]) {
      g.beginPath(); g.moveTo(0, H);
      for (let x = 0; x <= W; x += 10) g.lineTo(x, level + Math.sin(x / 120 + phase) * amp);
      g.lineTo(W, H); g.closePath();
      g.globalAlpha = alpha; g.fillStyle = fg; g.fill(); g.globalAlpha = 1;
    }
    // dot grain
    g.fillStyle = fg; g.globalAlpha = 0.05;
    for (let y = 0; y < H; y += 12) for (let x = 0; x < W; x += 12) g.fillRect(x, y, 1.5, 1.5);
    g.globalAlpha = 1;

    g.fillStyle = fg; g.font = "400 104px UnifrakturMaguntia"; g.fillText("Diligent", 80, 170);
    const fmtDay = (d) => S.parse(d).toLocaleDateString(undefined, { day: "numeric", month: "short" }).toUpperCase();
    g.fillStyle = dim; g.font = "700 26px Doto"; spaced(`WEEK · ${fmtDay(days[0])} – ${fmtDay(t)}`, 84, 225, 6);

    g.fillStyle = fg; g.font = "900 230px Doto"; g.fillText(hours >= 100 ? Math.round(hours) : hours.toFixed(1), 70, 470);
    g.fillStyle = dim; g.font = "700 26px Doto"; spaced("HOURS LOGGED", 84, 520, 6);

    const stats = [[Math.round(weekShare * 100) + "%", "GOALS HIT"], [perfect + "/7", "PERFECT DAYS"], [best && best.s ? best.s + "d" : "–", best && best.s ? best.a.name.toUpperCase().slice(0, 14) : "STREAK"]];
    stats.forEach(([val, label], i) => {
      const x = 84 + i * 320;
      g.fillStyle = fg; g.font = "900 84px Doto"; g.fillText(val, x, 650);
      g.fillStyle = dim; g.font = "700 20px Doto"; spaced(label, x + 4, 690, 4);
    });

    // seven liquid dots
    days.forEach((d, i) => {
      const cx = 84 + 66 + i * 132, cy = 800, r = 52;
      g.save(); g.beginPath(); g.arc(cx, cy, r, 0, Math.PI * 2); g.clip();
      g.fillStyle = shares[i] >= 0.999 ? good : fg; g.globalAlpha = shares[i] >= 0.999 ? 0.9 : 0.75;
      g.fillRect(cx - r, cy + r - shares[i] * 2 * r, 2 * r, shares[i] * 2 * r);
      g.restore();
      g.strokeStyle = fg; g.globalAlpha = 0.35; g.lineWidth = 2; g.beginPath(); g.arc(cx, cy, r, 0, Math.PI * 2); g.stroke(); g.globalAlpha = 1;
      g.fillStyle = dim; g.font = "700 22px Doto"; g.textAlign = "center";
      g.fillText(S.parse(d).toLocaleDateString(undefined, { weekday: "short" }).toUpperCase(), cx, cy + r + 40);
      g.textAlign = "left";
    });

    // activities
    const rows = acts.map((a) => ({ a, c: days.reduce((n, d) => n + (S.skipped(a, d) ? 0 : S.credit(a, d)), 0) / 7, total: days.reduce((n, d) => n + S.progressOf(d, a.id), 0) }))
      .sort((x, y) => y.c - x.c).slice(0, 5);
    rows.forEach(({ a, c, total }, i) => {
      const y = 960 + i * 62;
      g.fillStyle = a.color; g.beginPath(); g.arc(92, y - 8, 8, 0, Math.PI * 2); g.fill();
      g.fillStyle = fg; g.font = "700 26px 'Space Mono'"; g.fillText(a.name.slice(0, 22), 116, y);
      g.fillStyle = fg; g.globalAlpha = 0.12; g.fillRect(560, y - 18, 300, 12); g.globalAlpha = 1;
      g.fillStyle = a.color; g.fillRect(560, y - 18, 300 * c, 12);
      g.fillStyle = dim; g.font = "400 22px 'Space Mono'"; g.textAlign = "right"; g.fillText(S.fmt(a, total), W - 84, y); g.textAlign = "left";
    });

    g.fillStyle = dim; g.font = "700 20px Doto"; spaced("TRACK YOUR DISCIPLINE · BUILD CONSISTENCY", 84, H - 60, 5);
    return cv;
  }
  async function openWeeklyCard() {
    let cv;
    try { cv = await weeklyCanvas(); } catch (e) { return toast("Couldn't draw the card: " + e.message); }
    const blob = await new Promise((r) => cv.toBlob(r, "image/png"));
    const url = URL.createObjectURL(blob);
    let dlg = $("#card-dialog");
    if (!dlg) { dlg = document.createElement("dialog"); dlg.id = "card-dialog"; document.body.appendChild(dlg); }
    dlg.innerHTML = `<p class="label">Weekly card</p><img src="${url}" alt="Your week" style="width:100%;border-radius:14px;border:1px solid var(--border)">
      <div class="row" style="margin-top:14px"><button class="btn" data-c="dl">Download</button>
      ${navigator.canShare && navigator.canShare({ files: [new File([blob], "x.png", { type: "image/png" })] }) ? `<button class="btn ghost" data-c="share">Share</button>` : ""}
      <button class="btn ghost" data-c="copy">Copy</button><button class="btn ghost" data-c="clip" title="Send it to the shared clipboard">To clipboard</button>
      <span class="spacer"></span><button class="btn ghost sm" data-c="close">Close</button></div>`;
    const name = `diligent-week-${S.today()}.png`;
    dlg.onclick = async (e) => {
      const b = e.target.closest("[data-c]"); if (!b) { if (e.target === dlg) dlg.close(); return; }
      const c = b.dataset.c;
      if (c === "dl") { const a = document.createElement("a"); a.href = url; a.download = name; a.click(); }
      else if (c === "share") navigator.share({ files: [new File([blob], name, { type: "image/png" })], title: "My week on Diligent" }).catch(() => {});
      else if (c === "copy") { try { await navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]); toast("Copied"); } catch { toast("Couldn't copy here"); } }
      else if (c === "clip") {
        const scope = state.settings.clipScope || (C.ready() ? "mine" : "local");
        try { await C.pushClip(scope, await imageToDataUrl(new File([blob], name, { type: "image/png" })), "image"); toast("Sent to the clipboard"); } catch (err) { toast(err.message, 4000); }
      }
      else if (c === "close") dlg.close();
    };
    dlg.addEventListener("close", () => setTimeout(() => URL.revokeObjectURL(url), 1000), { once: true });
    dlg.showModal();
  }

  function statsAfter() {
    const wc = $("#weekly-card"); if (wc) wc.onclick = openWeeklyCard;
    const h = $("#heat"); if (!h) return;
    h.scrollLeft = h.scrollWidth;
    h.onclick = (e) => { const c = e.target.closest("[data-date]"); if (c) { viewDate = c.dataset.date; go("today"); } };
  }

  // ─── Clipboard ───────────────────────────────────────
  let clipScope = state.settings.clipScope || (C.ready() ? "mine" : "local");
  let clipList = [], clipError = "", clipSeen = Date.now(), clipNewest = 0;
  const ago = (t) => { const s = (Date.now() - t) / 1000; if (s < 45) return "just now"; if (s < 3600) return Math.round(s / 60) + " m ago"; if (s < 86400) return Math.round(s / 3600) + " h ago"; return new Date(t).toLocaleDateString(); };
  const linkify = (text) => esc(text).replace(/https?:\/\/[^\s<]+/g, (u) => `<a href="${u}" target="_blank" rel="noopener noreferrer">${u}</a>`);
  function clipView() {
    const scopeLabel = { local: "This browser", mine: "My devices", room: state.cloud.room ? "Room " + state.cloud.room : "Room" };
    return `
      <div class="card spot">
        <p class="label">Shared clipboard <span class="grow"></span>${clipScope !== "local" && C.ready() ? `<span class="live">LIVE</span>` : ""}</p>
        <div class="row" style="margin-bottom:14px">
          <div class="seg" id="clip-scope">${["local", "mine", "room"].map((s) => `<button data-v="${s}" class="${clipScope === s ? "on" : ""}">${esc(scopeLabel[s])}</button>`).join("")}</div>
          <span class="tiny">${clipScope === "local" ? "Stays in this browser; live across its tabs." : clipScope === "mine" ? "Your phone (Hub → Clipboard), laptop and any browser signed in with your owner id." : state.cloud.room ? "Everyone with the room code sees these." : "Join a room in the Room tab first."}</span>
        </div>
        <div class="drop" id="drop">
          <textarea id="clip-text" placeholder="Type, paste or drop text or an image… (Ctrl ↵ to send)" maxlength="${C.MAX_CHARS}"></textarea>
          <div class="row"><button class="btn" id="clip-send" data-cursor="send">Send</button><button class="btn ghost" id="clip-paste">Paste from clipboard</button><span class="spacer"></span>
            <label class="btn ghost sm" style="cursor:pointer">Image<input type="file" id="clip-file" accept="image/*" hidden></label>
            ${clipList.length ? `<button class="btn danger sm" id="clip-clear">Clear all</button>` : ""}</div>
        </div>
        ${clipError ? `<p class="small" style="color:var(--bad)">${esc(clipError)}</p>` : ""}
      </div>
      <div class="clips" id="clips" style="margin-top:16px">${clipList.map(clipCard).join("") || `<p class="tiny">No clips yet. Send one here, from Diligent on your phone, or share text to “Diligent clipboard” from any Android app.</p>`}</div>`;
  }
  function clipCard(c) {
    const fresh = c.createdAt > clipSeen - 1 && c.device !== C.device();
    return `<div class="card clip spot${fresh ? " fresh" : ""}" data-id="${esc(c.id)}">
      ${c.kind === "image" ? `<img src="${esc(c.text)}" alt="Shared image" loading="lazy">` : `<pre>${linkify(c.text)}</pre>`}
      <footer><span class="who">${esc(c.device || "?")} · ${ago(c.createdAt)}</span>
        <button class="btn sm" data-clip="copy" data-cursor="copy">Copy</button><button class="btn sm ghost" data-clip="del" aria-label="Delete">✕</button></footer></div>`;
  }
  async function loadClips(quietly) {
    try {
      const list = await C.clips(clipScope);
      clipError = "";
      const before = clipList.map((c) => c.id).join();
      clipList = list;
      if (view === "clipboard" && (before !== list.map((c) => c.id).join() || !quietly)) renderClipList();
      const newest = list.reduce((m, c) => Math.max(m, c.createdAt), 0);
      if (quietly && newest > clipNewest && clipNewest && list[0] && list[0].device !== C.device()) bloop("soft");
      clipNewest = Math.max(clipNewest, newest);
    } catch (e) {
      const changed = clipError !== e.message;
      clipError = e.message;
      if (view === "clipboard" && changed) render(false); // once: the next failure has the same message
    }
  }
  function renderClipList() {
    const el = $("#clips"); if (!el) return;
    el.innerHTML = clipList.map(clipCard).join("") || `<p class="tiny">No clips yet.</p>`;
    const clr = $("#clip-clear");
    if (!clr && clipList.length) render(false);
  }
  async function copyText(text) {
    try { await navigator.clipboard.writeText(text); return true; } catch {
      const ta = document.createElement("textarea"); ta.value = text; document.body.appendChild(ta); ta.select();
      const ok = document.execCommand("copy"); ta.remove(); return ok;
    }
  }
  async function copyImage(dataUrl) {
    const img = new Image(); img.src = dataUrl; await img.decode();
    const cv = document.createElement("canvas"); cv.width = img.naturalWidth; cv.height = img.naturalHeight;
    cv.getContext("2d").drawImage(img, 0, 0);
    const blob = await new Promise((r) => cv.toBlob(r, "image/png"));
    await navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]);
  }
  async function imageToDataUrl(file) {
    const url = URL.createObjectURL(file);
    try {
      const img = new Image(); img.src = url; await img.decode();
      let scale = Math.min(1, 1280 / Math.max(img.naturalWidth, img.naturalHeight)), out = "";
      for (let tries = 0; tries < 6; tries++) {
        const cv = document.createElement("canvas");
        cv.width = Math.max(1, Math.round(img.naturalWidth * scale)); cv.height = Math.max(1, Math.round(img.naturalHeight * scale));
        cv.getContext("2d").drawImage(img, 0, 0, cv.width, cv.height);
        out = cv.toDataURL("image/webp", 0.8);
        if (!out.startsWith("data:image/webp")) out = cv.toDataURL("image/jpeg", 0.8);
        if (out.length < 340000) break;
        scale *= 0.7;
      }
      return out;
    } finally { URL.revokeObjectURL(url); }
  }
  async function sendClip(text, kind) {
    if (!text) return;
    try {
      await C.pushClip(clipScope, text, kind);
      bloop();
      inkAt($("#clip-send"), "#4ade80", true);
      await loadClips();
      const ta = $("#clip-text"); if (ta) ta.value = "";
    } catch (e) { toast(e.message, 4000); }
  }
  function clipAfter() {
    loadClips();
    $("#clip-scope").onclick = (e) => {
      const b = e.target.closest("button"); if (!b) return;
      clipScope = b.dataset.v; state.settings.clipScope = clipScope; S.save(); clipList = []; clipError = "";
      render(false); loadClips();
    };
    const ta = $("#clip-text");
    $("#clip-send").onclick = () => sendClip(ta.value.trim() ? ta.value : "", "text");
    ta.onkeydown = (e) => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); sendClip(ta.value.trim() ? ta.value : "", "text"); } };
    $("#clip-paste").onclick = async () => {
      try {
        if (navigator.clipboard.read) {
          const items = await navigator.clipboard.read();
          for (const it of items) {
            const type = it.types.find((t) => t.startsWith("image/"));
            if (type) { await sendClip(await imageToDataUrl(await it.getType(type)), "image"); return; }
          }
        }
        const text = await navigator.clipboard.readText();
        if (text) sendClip(text, "text"); else toast("Your clipboard is empty");
      } catch { toast("The browser blocked clipboard access. Paste with Ctrl V instead."); }
    };
    $("#clip-file").onchange = async (e) => { const f = e.target.files[0]; if (f) sendClip(await imageToDataUrl(f), "image"); };
    const clr = $("#clip-clear"); if (clr) clr.onclick = async () => { if (confirm("Delete every clip here?")) { try { await C.clearClips(clipScope); } catch (err) { toast(err.message); } loadClips(); } };
    const drop = $("#drop");
    drop.ondragover = (e) => { e.preventDefault(); drop.classList.add("over"); };
    drop.ondragleave = () => drop.classList.remove("over");
    drop.ondrop = async (e) => {
      e.preventDefault(); drop.classList.remove("over");
      const file = Array.from(e.dataTransfer.files).find((f) => f.type.startsWith("image/"));
      if (file) return sendClip(await imageToDataUrl(file), "image");
      const textFile = Array.from(e.dataTransfer.files).find((f) => f.type.startsWith("text/") || /\.(md|txt|json|csv|js|ts|py|kt)$/i.test(f.name));
      if (textFile) return sendClip(await textFile.text(), "text");
      const text = e.dataTransfer.getData("text/plain"); if (text) sendClip(text, "text");
    };
    $("#clips").onclick = async (e) => {
      const b = e.target.closest("[data-clip]"); if (!b) return;
      const card = b.closest(".clip"), c = clipList.find((x) => x.id === card.dataset.id); if (!c) return;
      if (b.dataset.clip === "copy") {
        try { if (c.kind === "image") await copyImage(c.text); else await copyText(c.text); b.textContent = "Copied"; inkAt(b, "#4ade80"); bloop(); setTimeout(() => (b.textContent = "Copy"), 1400); }
        catch { toast("Couldn't copy here"); }
      } else {
        card.style.transition = "transform .4s var(--spring), opacity .3s"; card.style.transform = "scale(.8)"; card.style.opacity = "0";
        try { await C.deleteClip(clipScope, c.id); } catch (err) { toast(err.message); }
        clipList = clipList.filter((x) => x.id !== c.id);
        setTimeout(renderClipList, 300);
      }
    };
    // Paste anywhere on this view (outside text fields) makes a clip at once.
    const onPaste = async (e) => {
      if (e.target.closest("input, textarea")) return;
      const items = Array.from(e.clipboardData.items || []);
      const img = items.find((i) => i.type.startsWith("image/"));
      if (img) { e.preventDefault(); return sendClip(await imageToDataUrl(img.getAsFile()), "image"); }
      const text = e.clipboardData.getData("text/plain");
      if (text) { e.preventDefault(); sendClip(text, "text"); }
    };
    document.addEventListener("paste", onPaste);
    const offLocal = C.onClips(() => { if (clipScope === "local") loadClips(true); });
    const timer = setInterval(() => { if (!document.hidden && clipScope !== "local") loadClips(true); }, 4000);
    return () => { document.removeEventListener("paste", onPaste); offLocal(); clearInterval(timer); };
  }
  function updateBadge(n) { const b = $("#clip-badge"); b.hidden = !n; b.textContent = n; }
  // Badge for clips from other devices while you're elsewhere.
  setInterval(async () => {
    if (document.hidden || view === "clipboard" || !C.ready() || state.settings.clipScope === "local") return;
    try {
      const list = await C.clips(state.settings.clipScope || "mine");
      const fresh = list.filter((c) => c.createdAt > clipSeen && c.device !== C.device()).length;
      if (fresh > (+$("#clip-badge").textContent || 0)) bloop("soft");
      updateBadge(fresh);
    } catch {}
  }, 30000);

  // ─── Room ────────────────────────────────────────────
  let roomMembers = [], roomError = "", roomFetched = "";
  function roomView() {
    if (!C.ready()) return `<div class="card spot"><p class="label">Study room</p><p>Rooms live in your own Supabase project, the same one the app uses.</p><button class="btn" onclick="location.hash='#settings'">Set up cloud</button></div>`;
    const room = state.cloud.room;
    if (!room) return `<div class="card spot" style="max-width:560px"><p class="label">Study room</p><h2 class="title">Focus together</h2>
      <p class="muted">Join friends with the same code: see who's focusing on what, live. Your web and phone timers both show up. Rooms also get their own shared clipboard.</p>
      <div class="row"><input id="room-code" placeholder="ROOM CODE" maxlength="12" style="flex:1;text-transform:uppercase;letter-spacing:.2em"><button class="btn" id="room-join">Join</button></div>
      <button class="btn ghost" id="room-new" style="margin-top:10px">New room</button></div>`;
    const now = Date.now();
    const on = roomMembers.filter((m) => m.activity && now - m.updatedAt < 10 * 60000), off = roomMembers.filter((m) => !on.includes(m));
    const mcard = (m, live) => `<div class="card member spot${live ? " on" : ""}"><div class="avatar">${esc((m.name || "?").slice(0, 1).toUpperCase())}</div>
      <div style="min-width:0"><b>${esc(m.name)}</b><div class="tiny">${live ? `${esc(m.activity)} · ${Math.max(0, Math.round((now - m.since) / 60000))} min${m.until > now ? " · until " + new Date(m.until).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : ""}` : "seen " + ago(m.updatedAt)}</div></div></div>`;
    return `<div class="card spot"><p class="label">Room <span class="grow"></span><span class="live">LIVE</span></p>
      <div class="row"><span class="code">${esc(room)}</span><span class="spacer"></span><button class="btn ghost sm" id="room-copy">Copy code</button><button class="btn ghost sm" id="room-clip">Room clipboard</button><button class="btn danger sm" id="room-leave">Leave</button></div>
      <p class="tiny">Your status updates when you start or stop the focus timer, here or on the phone.</p>
      ${roomError ? `<p class="small" style="color:var(--bad)">${esc(roomError)}</p>` : ""}</div>
      <p class="label" style="margin-top:22px">Focusing · ${on.length}</p>
      <div class="grid members">${on.map((m) => mcard(m, true)).join("") || `<p class="tiny">Nobody is focusing right now. Start a timer and you'll be first.</p>`}</div>
      ${off.length ? `<p class="label" style="margin-top:22px">Around</p><div class="grid members">${off.map((m) => mcard(m, false)).join("")}</div>` : ""}`;
  }
  async function refreshRoom() {
    roomFetched = state.cloud.room;
    try { await C.pushPresence(); roomMembers = await C.members(); roomError = ""; } catch (e) { roomError = e.message; }
    if (view === "room") render(false);
  }
  function roomAfter() {
    const join = (code) => { code = code.trim().toUpperCase(); if (code.length < 4) return toast("Codes are at least 4 characters"); state.cloud.room = code; S.save(); roomMembers = []; render(false); refreshRoom(); };
    const j = $("#room-join"); if (j) { j.onclick = () => join($("#room-code").value); $("#room-code").onkeydown = (e) => { if (e.key === "Enter") join(e.target.value); }; }
    const n = $("#room-new"); if (n) n.onclick = () => join(C.newCode());
    const c = $("#room-copy"); if (c) c.onclick = async () => { await copyText(state.cloud.room); toast("Code copied"); };
    const rc = $("#room-clip"); if (rc) rc.onclick = () => { clipScope = "room"; state.settings.clipScope = "room"; S.save(); go("clipboard"); };
    const l = $("#room-leave"); if (l) l.onclick = async () => { await C.leaveRoom(); roomMembers = []; render(false); };
    if (!C.ready() || !state.cloud.room) return null;
    if (roomFetched !== state.cloud.room) refreshRoom();
    const t = setInterval(() => { if (!document.hidden) refreshRoom(); }, 15000);
    return () => clearInterval(t);
  }

  // ─── Settings ────────────────────────────────────────
  const SQL = `create table if not exists diligent_clips (
  scope text not null, id text not null, text text, kind text default 'text',
  device text, created_at bigint, primary key (scope, id));
alter table diligent_clips enable row level security;
create policy clips on diligent_clips for all
  using (length(scope) > 5 and (scope = diligent_header('x-diligent-owner')
         or scope = 'room:' || diligent_header('x-diligent-room')))
  with check (length(scope) > 5 and (scope = diligent_header('x-diligent-owner')
         or scope = 'room:' || diligent_header('x-diligent-room')));`;
  function settingsView() {
    const s = state.settings, c = state.cloud;
    const archived = state.activities.filter((a) => a.archived);
    const sw = (key, title, sub) => `<label class="switch"><span class="txt"><b>${title}</b><br><span class="tiny">${sub}</span></span><input type="checkbox" data-set="${key}" ${s[key] ? "checked" : ""}></label>`;
    return `<div class="grid two">
      <div class="stack">
        <div class="card spot"><p class="label">Activities</p>
          ${S.active().map((a) => `<div class="row" style="padding:8px 0;border-bottom:1px solid var(--border);--c:${esc(a.color)}"><span class="dot"></span><span style="flex:1">${esc(a.name)} <span class="tiny">· ${a.goalType === "at_most" ? "max " : a.goalType === "quit" ? "quit · " : ""}${esc(S.fmt(a, a.goal))}</span></span>
            <button class="btn sm ghost" data-mv="-1" data-id="${a.id}" aria-label="Move up">↑</button><button class="btn sm ghost" data-mv="1" data-id="${a.id}" aria-label="Move down">↓</button><button class="btn sm" data-edit="${a.id}">Edit</button></div>`).join("")}
          <button class="btn ghost" id="set-add" style="margin-top:12px">+ New activity</button>
          ${archived.length ? `<p class="label" style="margin-top:18px">Archived</p>${archived.map((a) => `<div class="row" style="padding:4px 0"><span style="flex:1" class="muted">${esc(a.name)}</span><button class="btn sm ghost" data-restore="${a.id}">Restore</button></div>`).join("")}` : ""}
        </div>
        <div class="card spot"><p class="label">Cloud · your Supabase project</p>
          <p class="tiny">The same project as Hub → Cloud in the app: synced days, the shared clipboard and rooms. Kept in this browser only.</p>
          <label class="field" for="c-url">Project URL</label><input id="c-url" type="url" value="${esc(c.url)}" placeholder="https://xxxx.supabase.co">
          <label class="field" for="c-key">Anon key</label><input id="c-key" type="password" value="${esc(c.key)}" placeholder="eyJ…" autocomplete="off">
          <label class="field" for="c-owner">Owner id (Hub → Cloud in the app)</label>
          <div class="row"><input id="c-owner" value="${esc(c.owner)}" placeholder="uuid" style="flex:1" autocomplete="off"><button class="btn sm ghost" id="c-gen" title="Only if you don't use the app">New</button></div>
          <label class="field" for="c-device">This device's name</label><input id="c-device" value="${esc(c.device)}" placeholder="${esc(C.device())}">
          <div class="row" style="margin-top:14px"><button class="btn" id="c-save">Save &amp; sync</button><span class="tiny" id="c-status">${c.lastPull ? "last sync " + ago(c.lastPull) : ""}</span></div>
          <label class="switch" style="margin-top:6px"><span class="txt"><b>Auto sync</b><br><span class="tiny">On open, 20 s after a change and every 5 min while this tab is open</span></span><input type="checkbox" id="c-auto" ${c.auto ? "checked" : ""}></label>
          <details style="margin-top:10px"><summary class="tiny" style="cursor:pointer">SQL for the shared clipboard (run once; the rest is in docs/CLOUD.md)</summary><pre class="sql">${esc(SQL)}</pre></details>
        </div>
      </div>
      <div class="stack">
        <div class="card spot"><p class="label">You</p>
          <label class="field" for="s-name">Name in rooms</label><input id="s-name" value="${esc(s.name)}" placeholder="Me" maxlength="30">
          <label class="field" for="s-day">Day starts at</label>
          <select id="s-day">${[0, 1, 2, 3, 4, 5, 6].map((h) => `<option value="${h}" ${s.dayStartHour === h ? "selected" : ""}>${h === 0 ? "Midnight" : h + ":00 AM"}</option>`).join("")}</select>
          <label class="field" for="s-quotes">Quotes</label>
          <select id="s-quotes"><option value="mix">Mix of every pack</option>${(D.QUOTE_PACKS || []).map((p) => `<option value="${p.id}" ${s.quotePack === p.id ? "selected" : ""}>${esc(p.name)} · ${esc(p.description)}</option>`).join("")}</select>
        </div>
        <div class="card spot"><p class="label">Look &amp; feel</p>
          <div class="seg" id="s-theme">${[["mono", "Mono"], ["paper", "Paper"], ["night", "Red night"]].map(([v, l]) => `<button data-v="${v}" class="${s.theme === v ? "on" : ""}">${l}</button>`).join("")}</div>
          ${sw("fluid", "Liquid background", "Ink that follows your cursor (WebGL)")}
          ${sw("cursor", "Liquid cursor", "A blob that chases the pointer; off on touch screens")}
          ${sw("sound", "Sounds", "Soft drops when you log, finish and focus")}
          ${sw("notify", "Notifications", "Pomodoro and target alerts while the tab is in the background")}
        </div>
        <div class="card spot"><p class="label">Data</p>
          <p class="tiny">Import accepts this site's backup or the Android app's JSON backup (Settings → Backup).</p>
          <div class="row"><button class="btn ghost sm" id="d-export">Export JSON</button><button class="btn ghost sm" id="d-csv">Export CSV</button>
            <label class="btn ghost sm" style="cursor:pointer">Import<input type="file" id="d-import" accept="application/json,.json" hidden></label>
            <span class="spacer"></span><button class="btn danger sm" id="d-reset">Reset</button></div>
        </div>
        <div class="card spot"><p class="label">About</p>
          <p class="small">Diligent for the web. Everything works offline and installs as an app. <a href="https://github.com/ArindamJaiman/Diligent" target="_blank" rel="noopener">Source</a> · <a href="#" id="s-keys">Keyboard shortcuts</a></p></div>
      </div></div>`;
  }
  function download(name, text, type) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type }));
    a.download = name; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }
  function settingsAfter() {
    const s = state.settings, c = state.cloud;
    $$("[data-edit]").forEach((b) => b.onclick = () => openEditor(S.byId(b.dataset.edit)));
    $$("[data-mv]").forEach((b) => b.onclick = () => S.move(b.dataset.id, +b.dataset.mv));
    $$("[data-restore]").forEach((b) => b.onclick = () => { S.byId(b.dataset.restore).archived = false; S.emit({ type: "activities" }); });
    $("#set-add").onclick = () => openEditor(null);
    $("#s-name").oninput = (e) => { s.name = e.target.value.trim(); S.save(); };
    $("#s-day").onchange = (e) => { s.dayStartHour = +e.target.value; viewDate = S.today(); S.emit({ type: "settings" }); };
    $("#s-quotes").onchange = (e) => { s.quotePack = e.target.value; nextQuote(); S.save(); };
    $("#s-theme").onclick = (e) => { const b = e.target.closest("button"); if (b) setTheme(b.dataset.v); };
    $$("[data-set]").forEach((inp) => inp.onchange = async () => {
      const k = inp.dataset.set;
      s[k] = inp.checked;
      if (k === "notify" && inp.checked && "Notification" in window && Notification.permission !== "granted") {
        const p = await Notification.requestPermission();
        if (p !== "granted") { s.notify = false; inp.checked = false; toast("Notifications are blocked for this site"); }
      }
      S.save();
      applyLook();
    });
    $("#c-gen").onclick = () => { if (!$("#c-owner").value || confirm("Make a new owner id? Only do this if you don't use the app, or your rows won't match.")) $("#c-owner").value = S.uid(); };
    $("#c-auto").onchange = (e) => { c.auto = e.target.checked; S.save(); };
    $("#c-save").onclick = async () => {
      c.url = $("#c-url").value.trim().replace(/\/$/, ""); c.key = $("#c-key").value.trim(); c.owner = $("#c-owner").value.trim(); c.device = $("#c-device").value.trim();
      S.save();
      const st = $("#c-status"); st.textContent = "syncing…";
      try { const msg = await C.sync(); st.textContent = msg; toast("Synced · " + msg); } catch (e) { st.textContent = e.message; }
    };
    $("#d-export").onclick = () => download(`diligent-web-${S.today()}.json`, S.exportJson(), "application/json");
    $("#d-csv").onclick = () => download(`diligent-${S.today()}.csv`, S.exportCsv(), "text/csv");
    $("#d-import").onchange = async (e) => { const f = e.target.files[0]; if (!f) return; try { toast(S.importJson(await f.text()), 4000); } catch (err) { toast("Import failed: " + err.message, 4000); } };
    $("#d-reset").onclick = () => { if (confirm("Delete everything Diligent keeps in this browser?") && confirm("Really? This can't be undone.")) { localStorage.removeItem("diligent.web.v1"); location.reload(); } };
    $("#s-keys").onclick = (e) => { e.preventDefault(); $("#keys").showModal(); };
  }

  // ─── Activity editor ─────────────────────────────────
  let editing = null, edType = "at_least", edColor = S.COLORS[0];
  function openEditor(a) {
    editing = a;
    $("#ed-name").value = a ? a.name : "";
    $("#ed-unit").value = a ? a.unit : "hrs";
    if (a && !Array.from($("#ed-unit").options).some((o) => o.value === a.unit)) $("#ed-unit").add(new Option(a.unit, a.unit, true, true));
    $("#ed-goal").value = a ? a.goal : 1;
    edType = a ? a.goalType : "at_least";
    edColor = a ? a.color : S.COLORS[state.activities.length % S.COLORS.length];
    $("#ed-delete").hidden = !a;
    paintEditor();
    $("#editor").showModal();
    setTimeout(() => $("#ed-name").focus(), 50);
  }
  function paintEditor() {
    $$("#ed-type button").forEach((b) => b.classList.toggle("on", b.dataset.v === edType));
    $("#ed-colors").innerHTML = S.COLORS.map((c) => `<button type="button" style="--c:${c}" data-c="${c}" class="${c === edColor ? "on" : ""}" aria-label="${c}"></button>`).join("");
  }
  $("#ed-type").onclick = (e) => { const b = e.target.closest("button"); if (b) { edType = b.dataset.v; paintEditor(); } };
  $("#ed-colors").onclick = (e) => { const b = e.target.closest("button"); if (b) { edColor = b.dataset.c; paintEditor(); } };
  $("#ed-cancel").onclick = () => $("#editor").close();
  $("#ed-delete").onclick = () => { if (editing && confirm(`Archive ${editing.name}? Its history is kept.`)) { S.removeActivity(editing.id); $("#editor").close(); } };
  $("#editor-form").onsubmit = (e) => {
    e.preventDefault();
    const name = $("#ed-name").value.trim();
    if (!name) return;
    const fields = { name, unit: $("#ed-unit").value, goal: Math.max(0, +$("#ed-goal").value || 0), goalType: edType, color: edColor };
    if (editing) fields.id = editing.id;
    S.upsertActivity(fields);
    $("#editor").close();
    toast(editing ? "Saved" : `Added ${name}`);
  };

  // ─── Command palette ─────────────────────────────────
  let palItems = [], palSel = 0;
  function commands() {
    const list = [
      ...VIEWS.map((v, i) => ({ icon: "→", label: "Go to " + v[0].toUpperCase() + v.slice(1), key: String(i + 1), run: () => go(v) })),
      state.focus ? { icon: "■", label: "Stop focus and log it", key: "F", run: () => F.stop() } : null,
      ...S.active().filter((a) => a.unit === "hrs" || a.unit === "mins").map((a) => ({ icon: "⏱", label: `Focus on ${a.name}`, run: () => F.start(a.id) })),
      ...S.active().filter((a) => !S.isLimit(a)).map((a) => ({ icon: "✓", label: `Mark ${a.name} done`, run: () => { if (!S.met(a, S.today())) S.toggleDone(a.id); } })),
      { icon: "+", label: "New activity", key: "N", run: () => openEditor(null) },
      { icon: "◐", label: "Theme: Mono", run: () => setTheme("mono") },
      { icon: "◐", label: "Theme: Paper", run: () => setTheme("paper") },
      { icon: "◐", label: "Theme: Red night", run: () => setTheme("night") },
      { icon: "≈", label: (state.settings.fluid ? "Turn off" : "Turn on") + " the liquid background", run: () => { state.settings.fluid = !state.settings.fluid; S.save(); applyLook(); } },
      { icon: "⟳", label: "Sync now", run: () => C.sync().then((m) => toast("Synced · " + m)).catch((e) => toast(e.message)) },
      { icon: "⎘", label: "Send my clipboard to my devices", run: async () => { try { const t = await navigator.clipboard.readText(); await C.pushClip(C.ready() ? "mine" : "local", t); toast("Sent"); } catch (e) { toast(e.message || "Clipboard blocked"); } } },
      { icon: "❝", label: "Next quote", run: () => { nextQuote(); if (view === "today") render(false); } },
      { icon: "▣", label: "Weekly card (share your week as an image)", run: openWeeklyCard },
      { icon: "⤓", label: "Export backup", run: () => download(`diligent-web-${S.today()}.json`, S.exportJson(), "application/json") },
      { icon: "?", label: "Keyboard shortcuts", key: "?", run: () => $("#keys").showModal() },
    ];
    return list.filter(Boolean);
  }
  function openPalette() {
    $("#palette-input").value = "";
    paintPalette();
    $("#palette").showModal();
    $("#palette-input").focus();
  }
  function paintPalette() {
    const q = $("#palette-input").value.trim().toLowerCase();
    const words = q.split(/\s+/).filter(Boolean);
    const quick = q ? S.parseQuick(q) : null;
    palItems = [
      ...(quick ? [{ icon: "›", label: quick.label, key: "↵", run: () => { S.runQuick(quick); toast(quick.label); } }] : []),
      ...rank(commands(), words),
    ].slice(0, 12);
    palSel = Math.min(palSel, Math.max(0, palItems.length - 1));
    $("#palette-list").innerHTML = palItems.map((c, i) => `<li class="${i === palSel ? "sel" : ""}" data-i="${i}"><span class="ic">${esc(c.icon)}</span>${esc(c.label)}${c.key ? `<span class="kbd k">${esc(c.key)}</span>` : ""}</li>`).join("") || `<li class="muted">Nothing matches. Logs look like “2h quantum” or “+3 lc”.</li>`;
  }
  /** Whole-word hits beat word starts beat substrings, so "paper" finds the theme before "Research Papers". */
  function rank(list, words) {
    if (!words.length) return list;
    const score = (label) => {
      const parts = label.toLowerCase().split(/[^a-z0-9+]+/);
      let total = 0;
      for (const w of words) {
        const s = parts.includes(w) ? 3 : parts.some((p) => p.startsWith(w)) ? 2 : label.toLowerCase().includes(w) ? 1 : 0;
        if (!s) return 0;
        total += s;
      }
      return total;
    };
    return list.map((c, i) => ({ c, i, s: score(c.label) })).filter((x) => x.s).sort((a, b) => b.s - a.s || a.i - b.i).map((x) => x.c);
  }
  function runPalette(i) { const c = palItems[i]; $("#palette").close(); if (c) c.run(); }
  $("#palette-input").oninput = () => { palSel = 0; paintPalette(); };
  $("#palette-input").onkeydown = (e) => {
    if (e.key === "ArrowDown") { e.preventDefault(); palSel = Math.min(palItems.length - 1, palSel + 1); paintPalette(); }
    else if (e.key === "ArrowUp") { e.preventDefault(); palSel = Math.max(0, palSel - 1); paintPalette(); }
    else if (e.key === "Enter") { e.preventDefault(); runPalette(palSel); }
  };
  $("#palette-list").onclick = (e) => { const li = e.target.closest("li[data-i]"); if (li) runPalette(+li.dataset.i); };
  $("#palette").onclick = (e) => { if (e.target === $("#palette")) $("#palette").close(); };
  $("#open-palette").onclick = openPalette;

  // ─── Keyboard ────────────────────────────────────────
  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); openPalette(); return; }
    if (e.target.closest("input, textarea, select, dialog[open]") || e.ctrlKey || e.metaKey || e.altKey) return;
    const k = e.key;
    if (/^[1-6]$/.test(k)) go(VIEWS[+k - 1]);
    else if (k === "/") { e.preventDefault(); openPalette(); }
    else if (k === "?") $("#keys").showModal();
    else if (k === "n") openEditor(null);
    else if (k === "f") { if (state.focus) F.stop(); else go("focus"); }
    else if (k === "l") { go("today"); setTimeout(() => $("#quick") && $("#quick").focus(), 30); e.preventDefault(); }
    else if (view === "today" && k === "[") { viewDate = S.addDays(viewDate, -1); render(false); }
    else if (view === "today" && k === "]" && viewDate < S.today()) { viewDate = S.addDays(viewDate, 1); render(false); }
    else if (view === "today" && k === "t") { viewDate = S.today(); render(false); }
  });

  // ─── Look ────────────────────────────────────────────
  function setTheme(t) {
    state.settings.theme = t; S.save();
    document.documentElement.dataset.theme = t;
    $('meta[name="theme-color"]').content = t === "paper" ? "#f3efe6" : "#000000";
    if (view === "settings") render(false);
  }
  function applyLook() {
    const s = state.settings;
    const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
    document.documentElement.classList.toggle("no-fluid", !s.fluid || !fluid);
    $("#fallback").hidden = !!(s.fluid && fluid) || reduced;
    if (fluid) fluid.setPaused(!s.fluid);
    D.cursor.enable(!!s.cursor);
  }

  // ─── Sync scheduling ─────────────────────────────────
  let syncTimer = 0;
  function scheduleSync(delay) {
    if (!C.ready() || !state.cloud.auto) return;
    clearTimeout(syncTimer);
    syncTimer = setTimeout(() => C.sync().catch((e) => console.warn("Diligent sync:", e.message)), delay);
  }
  setInterval(() => { if (!document.hidden) scheduleSync(0); }, 5 * 60000);

  // ─── Store events ────────────────────────────────────
  // Background changes (sync, another tab) wait until you leave the field you're typing in.
  let deferred = false;
  const typing = () => { const el = document.activeElement; return !!el && main.contains(el) && el.matches("input, textarea, select"); };
  main.addEventListener("focusout", () => setTimeout(() => { if (deferred && !typing()) { deferred = false; render(false); } }, 0));
  S.on((ev) => {
    if (ev && (ev.type === "sync" || ev === "external") && typing()) { deferred = true; return; }
    if (ev && ev.type === "progress") {
      scheduleSync(20000);
      if (patchToday(ev)) return;
    }
    if (ev && (ev.type === "clips")) { if (view === "clipboard" && clipScope === "local") loadClips(true); return; }
    if (ev && ev.type === "top3" && view === "today") {
      const date = viewDate, top = state.top3[date] || [];
      $$("[data-top]").forEach((b) => { const it = top[+b.dataset.top] || {}; b.classList.toggle("on", !!it.done); b.parentElement.classList.toggle("done", !!it.done); });
      return;
    }
    if (ev && ev.type === "sync" && view !== "today" && view !== "stats") return;
    render(false);
  });

  // ─── Tick ────────────────────────────────────────────
  setInterval(() => {
    F.tick();
    syncAmbient();
    updatePip();
    const r = F.readout();
    const f = state.focus;
    if (r) {
      const a = S.byId(f.activityId);
      document.title = `${clock(r.ms)} · ${a ? a.name : ""} · Diligent`;
      const tc = $("#timer-clock"); if (tc) tc.textContent = clock(r.ms);
      const tl = $("#timer-label"); if (tl) tl.textContent = `${r.label} · ${a ? a.name : ""}`;
      const tk = $("#timer .tank"); if (tk) tk.style.setProperty("--level", r.level);
      const mc = $("#mini-clock"); if (mc) mc.textContent = clock(r.ms);
    } else if (document.title !== "Diligent") document.title = "Diligent";
    // The day rolled over while the tab was open.
    const t = S.today();
    if (t !== lastToday) { if (viewDate === lastToday) viewDate = t; lastToday = t; render(false); }
  }, 1000);
  let lastToday = S.today();

  // ─── Boot ────────────────────────────────────────────
  const d = new Date();
  $("#brand-date").textContent = d.toLocaleDateString(undefined, { weekday: "short", day: "2-digit", month: "short" });
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (!reduced) {
    try { fluid = D.fluid.start($("#fluid"), { palette }); } catch (e) { console.warn("Diligent fluid:", e); fluid = null; }
  }
  if (!fluid) {
    document.addEventListener("pointermove", (e) => {
      const fb = $("#fallback");
      fb.style.setProperty("--fx", (e.clientX / innerWidth) * 100 + "%");
      fb.style.setProperty("--fy", (e.clientY / innerHeight) * 100 + "%");
    }, { passive: true });
  }
  applyLook();
  go(location.hash.slice(1) || "today", false);
  requestAnimationFrame(moveTabBlob);
  document.fonts && document.fonts.ready.then(moveTabBlob);
  if (C.ready() && state.cloud.auto) scheduleSync(800);
  if (state.focus) C.pushPresence().catch(() => {});
  if ("serviceWorker" in navigator && /^https?:/.test(location.protocol)) navigator.serviceWorker.register("sw.js").catch(() => {});
})();
