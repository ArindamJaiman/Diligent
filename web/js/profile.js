/*
 * Public page: a single, self-contained HTML file of your year (heatmap, streaks, totals) for
 * the activities you pick. Opt-in and static: nothing is uploaded; you host the file where you
 * like (GitHub Pages, Netlify, any web host). Never includes notes, mood, Top 3, clips or keys,
 * and the page itself runs no JavaScript.
 */
(function () {
  "use strict";
  const D = (window.Diligent = window.Diligent || {});
  const S = D.store, state = S.state;
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  function options() {
    const o = state.settings.publicPage || {};
    const ids = Array.isArray(o.activities) ? o.activities : S.active().map((a) => a.id);
    return { activities: ids, hours: o.hours !== false, title: o.title || state.settings.name || "My discipline" };
  }
  function setOptions(p) { state.settings.publicPage = Object.assign(options(), p); S.save(); }

  /** Daily share over the chosen activities that existed and were due that day. */
  function share(acts, d) {
    const list = acts.filter((a) => S.isDue(a, d) || S.met(a, d));
    if (!list.length) return null;
    const anyData = state.progress[d] && list.some((a) => state.progress[d][a.id]);
    if (!anyData) return null;
    return list.reduce((n, a) => n + S.credit(a, d), 0) / list.length;
  }

  function build() {
    const o = options();
    const acts = S.active().filter((a) => o.activities.includes(a.id));
    const t = S.today();
    const end = S.parse(t), start = new Date(end);
    start.setDate(start.getDate() - 364 - ((end.getDay() + 6) % 7));
    const days = [];
    for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) days.push(S.iso(d));
    const shares = days.map((d) => share(acts, d));
    const active = shares.filter((x) => x != null && x > 0).length;
    const perfect = shares.filter((x) => x != null && x >= 0.999).length;
    const hours = days.reduce((n, d) => n + acts.reduce((m, a) => m + S.hoursOf(a, S.progressOf(d, a.id)), 0), 0);
    let streak = 0;
    for (let i = shares.length - 1; i >= 0; i--) {
      const s = shares[i];
      if (s != null && s >= 0.999) streak++;
      else if (i === shares.length - 1) continue; // today is still open
      else break;
    }

    // Heatmap as static SVG: Monday-first columns of dots.
    const cell = 13, gap = 3, cols = Math.ceil(days.length / 7);
    const dots = days.map((d, i) => {
      const s = shares[i], x = Math.floor(i / 7) * (cell + gap) + cell / 2, y = (i % 7) * (cell + gap) + cell / 2;
      const fill = s == null ? "#1a1a1a" : s >= 0.999 ? "#4ade80" : `rgba(255,255,255,${(0.14 + 0.6 * s).toFixed(2)})`;
      return `<circle cx="${x}" cy="${y}" r="${cell / 2}" fill="${fill}"><title>${d}${s == null ? "" : ": " + Math.round(s * 100) + "%"}</title></circle>`;
    }).join("");
    const months = [];
    for (let i = 0; i < days.length; i += 7) {
      const d = S.parse(days[i]);
      if (d.getDate() <= 7) months.push(`<text x="${Math.floor(i / 7) * (cell + gap)}" y="-6">${d.toLocaleDateString("en", { month: "short" })}</text>`);
    }
    const heat = `<svg viewBox="0 -18 ${cols * (cell + gap)} ${7 * (cell + gap) + 18}" role="img" aria-label="A year of days, greener when every goal was hit">${months.join("")}${dots}</svg>`;

    const last30 = S.range(S.addDays(t, -29), t);
    const rows = acts.map((a) => {
      const total = days.reduce((n, d) => n + S.progressOf(d, a.id), 0);
      const pts = last30.map((d, i) => `${(i / 29) * 120},${26 - S.credit(a, d) * 24}`).join(" ");
      const unit = S.streakUnit(a) === "weeks" ? "w" : "d";
      return `<tr><td><span class="dot" style="background:${esc(a.color)}"></span>${esc(a.name)}<div class="sub">${esc(S.describe(a))}</div></td>
        <td class="n">${S.streak(a)}${unit}</td><td class="n">${S.bestStreak(a)}${unit}</td>
        <td class="n">${o.hours ? esc(S.fmt(a, total)) : Math.round(S.strength(a) * 100) + "%"}</td>
        <td><svg class="spark" viewBox="0 0 120 28"><polyline points="${pts}" fill="none" stroke="${esc(a.color)}" stroke-width="1.6" stroke-linejoin="round"/></svg></td></tr>`;
    }).join("");

    const tiles = [
      [streak, "perfect days in a row"],
      [perfect, "perfect days this year"],
      [active, "active days this year"],
      ...(o.hours ? [[Math.round(hours), "hours this year"]] : []),
    ].map(([v, l]) => `<div class="tile"><b>${v}</b><span>${esc(l)}</span></div>`).join("");

    return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(o.title)} · Diligent</title>
<meta name="description" content="${esc(o.title)}: a year of discipline, tracked with Diligent.">
<meta name="generator" content="Diligent web">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Doto:wght@700;900&family=Space+Mono&family=UnifrakturMaguntia&display=swap">
<style>
  :root { color-scheme: dark; }
  body { margin: 0; background: #000; color: #f2f2f2; font: 14px/1.5 "Space Mono", ui-monospace, monospace; }
  main { max-width: 980px; margin: 0 auto; padding: 48px 20px 64px; }
  h1 { font: 400 clamp(40px, 8vw, 72px)/1 "UnifrakturMaguntia", serif; margin: 0; }
  .kicker { font: 700 11px/1 "Doto", monospace; letter-spacing: .3em; color: #8a8a8a; text-transform: uppercase; margin: 12px 0 32px; }
  .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin-bottom: 28px; }
  .tile, .card { border: 1px solid #262626; border-radius: 18px; background: #0d0d0d; padding: 18px; }
  .tile b { display: block; font: 900 40px/1 "Doto", monospace; }
  .tile span { display: block; margin-top: 8px; font: 700 10px/1.3 "Doto", monospace; letter-spacing: .16em; color: #8a8a8a; text-transform: uppercase; }
  .card { margin-bottom: 16px; overflow-x: auto; }
  .label { font: 700 11px/1 "Doto", monospace; letter-spacing: .22em; color: #8a8a8a; text-transform: uppercase; margin: 0 0 14px; }
  svg text { fill: #8a8a8a; font: 10px "Space Mono", monospace; }
  .heat svg { min-width: 760px; width: 100%; height: auto; display: block; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th { text-align: left; font: 700 9.5px/1 "Doto", monospace; letter-spacing: .16em; color: #8a8a8a; padding: 0 8px 10px; text-transform: uppercase; }
  td { padding: 10px 8px; border-top: 1px solid #1f1f1f; vertical-align: middle; }
  td.n, th.n { text-align: right; font-family: "Doto", monospace; font-weight: 700; white-space: nowrap; }
  .dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 8px; }
  .sub { font-size: 10.5px; color: #6f6f6f; margin-left: 17px; }
  .spark { width: 120px; height: 28px; display: block; }
  footer { margin-top: 28px; font-size: 11px; color: #6f6f6f; }
  footer a { color: #a0a0a0; }
</style></head>
<body><main>
  <h1>${esc(o.title)}</h1>
  <div class="kicker">Diligent · updated ${esc(new Date().toLocaleDateString("en", { day: "numeric", month: "short", year: "numeric" }))}</div>
  <section class="tiles">${tiles}</section>
  <section class="card heat"><p class="label">The last 12 months · green: every goal hit</p>${heat}</section>
  ${acts.length ? `<section class="card"><p class="label">Activities</p><table><thead><tr><th>Activity</th><th class="n">Streak</th><th class="n">Best</th><th class="n">${o.hours ? "This year" : "Strength"}</th><th>30 days</th></tr></thead><tbody>${rows}</tbody></table></section>` : ""}
  <footer>Tracked with <a href="https://github.com/ArindamJaiman/Diligent">Diligent</a>. A static snapshot: no notes, no tracking.</footer>
</main></body></html>`;
  }

  D.profile = { options, setOptions, build };
})();
