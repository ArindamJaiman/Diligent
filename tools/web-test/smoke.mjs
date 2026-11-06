// Smoke test for the web app: two "devices" share one Supabase owner id. Checks logging, the
// quick log, the palette, focus, day sync, the shared clipboard, rooms and the heatmap editor,
// and fails on any page error.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { startMockSupabase } from "./mock-supabase.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../web");
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".webmanifest": "application/manifest+json" };
const WEB = 8091, DB = 8792;

const web = http.createServer((req, res) => {
  const p = path.join(ROOT, decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/\/$/, "/index.html"));
  if (!p.startsWith(ROOT) || !fs.existsSync(p)) { res.statusCode = 404; return res.end(); }
  res.setHeader("Content-Type", TYPES[path.extname(p)] || "application/octet-stream");
  fs.createReadStream(p).pipe(res);
}).listen(WEB);
const db = await startMockSupabase(DB);

let failures = 0;
const check = (ok, what) => { console.log(`${ok ? "✓" : "✗"} ${what}`); if (!ok) failures++; };
const errors = [];
const browser = await chromium.launch({ args: ["--enable-unsafe-swiftshader", "--use-angle=swiftshader", "--ignore-gpu-blocklist"] });
// Fonts come from Google; the test doesn't need them.
const block = (ctx) => ctx.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.fulfill({ status: 200, contentType: "text/css", body: "" }));

async function device(name) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
  await block(ctx);
  await ctx.grantPermissions(["clipboard-read", "clipboard-write"], { origin: `http://localhost:${WEB}` });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => errors.push(`[${name}] ${e.message}`));
  // The coach test fakes a 401 on purpose; the browser logs that response as an error.
  page.on("console", (m) => { if (m.type() === "error" && !/status of 401/.test(m.text())) errors.push(`[${name}] console: ${m.text()}`); });
  await page.goto(`http://localhost:${WEB}/#today`);
  await page.waitForSelector(".act");
  return page;
}
const blur = (p) => p.evaluate(() => document.activeElement && document.activeElement.blur());
const connect = (p, deviceName) => p.evaluate(([port, n]) => {
  const s = Diligent.store.state;
  Object.assign(s.cloud, { url: `http://localhost:${port}`, key: "anon", owner: "owner-smoke-test-0001", device: n, auto: false });
  s.settings.clipScope = "mine";
  localStorage.setItem("diligent.web.v1", JSON.stringify(s));
  history.replaceState(null, "", "#today");
}, [DB, deviceName]);

try {
  const a = await device("A");
  check(await a.locator(".act").count() === 7, "seven default activities");

  const lc = a.locator('.act:has(h3:text-is("LeetCode"))');
  for (let i = 0; i < 3; i++) await lc.locator('[data-act="inc"]').click();
  await a.waitForTimeout(300);
  check((await lc.getAttribute("class")).includes("met"), "+ three times meets the LeetCode goal");
  check((await a.locator("#done-count").innerText()).startsWith("1/"), "done counter updates in place");

  await a.fill("#quick", "2h quantum");
  check((await a.locator("#quick-hint").innerText()).includes("Quantum Computing"), "quick log previews the parse");
  await a.press("#quick", "Enter");
  await a.waitForTimeout(200);
  check((await a.locator('.act:has(h3:text-is("Quantum Computing")) .amount').innerText()).startsWith("2 h"), "quick log adds 2 h");

  await blur(a);
  await a.keyboard.press("Control+k");
  await a.keyboard.type("paper");
  check((await a.locator("#palette-list li").first().innerText()).includes("Theme: Paper"), "palette ranks the Paper theme first");
  await a.keyboard.press("Enter");
  check(await a.evaluate(() => document.documentElement.dataset.theme) === "paper", "theme switches");

  await blur(a);
  await a.keyboard.press("3");
  await a.click("#start");
  // Wait for the clock to tick rather than sampling it at a fixed moment (CI renders slowly).
  const ticked = await a.waitForFunction(() => { const t = document.getElementById("timer-clock"); return t && /^00:(0[1-9]|[1-5]\d)$/.test(t.textContent); }, null, { timeout: 15000 }).then(() => true, () => false);
  check(ticked && /Diligent$/.test(await a.title()) && (await a.title()).includes("Quantum"), "focus timer runs and shows in the tab title");
  await a.click("#distracted");
  await a.keyboard.press("d");
  await a.evaluate(() => { Diligent.store.state.focus.startedAt -= 5 * 60000; }); // make it a 5-minute session
  await a.click("#stop");
  await a.waitForSelector("#session-dialog[open]");
  await a.fill("#sd-done", "Read the Grover chapter");
  await a.click('#sd-rate button[data-n="4"]');
  await a.click("#sd-save");
  await a.waitForFunction(() => Diligent.store.state.sessions[0].done === "Read the Grover chapter", null, { timeout: 5000 }).catch(() => {});
  await a.waitForSelector(".done-note", { timeout: 5000 }).catch(() => {});
  const sess = await a.evaluate(() => Diligent.store.state.sessions[0]);
  check(sess.done === "Read the Grover chapter" && sess.rating === 4 && sess.distractions === 2 && (await a.locator(".done-note").count()) >= 1,
    "after a session: what got done, a rating and the distractions are kept");

  await blur(a);
  await a.keyboard.press("4");
  // Journal fields: add one in Settings, log it in Today, see it charted in Stats
  await blur(a);
  await a.keyboard.press("7");
  await a.fill("#jf-name", "Sleep score");
  await a.fill("#jf-unit", "pts");
  await a.click("#jf-add");
  await a.evaluate(() => { location.hash = "#today"; });
  await a.fill('[data-jf]', "82");
  await a.evaluate(() => {
    const S = Diligent.store, st = S.state, id = st.settings.journalFields[0].id;
    for (let k = 1; k < 30; k++) { const d = S.addDays(S.today(), -k); st.dayLogs[d] = Object.assign(st.dayLogs[d] || {}, { fields: { [id]: 60 + (k % 5) * 6 } }); }
    S.save();
  });
  check(await a.evaluate(() => { const st = Diligent.store.state, id = st.settings.journalFields[0].id; return st.dayLogs[Diligent.store.today()].fields[id] === 82; }), "a journal field is logged from the day log");
  await a.evaluate(() => { location.hash = "#stats"; });
  await a.waitForSelector(".jchart");
  check((await a.locator(".jchart").first().innerText()).includes("82") && await a.locator(".jline circle").count() >= 25, "the journal field is charted in Stats");

  // Correlations: 30 days where mood tracks output should read as strong; noise shouldn't
  await a.evaluate(() => {
    const S = Diligent.store, st = S.state;
    for (let k = 20; k < 50; k++) {
      const d = S.addDays(S.today(), -k), good = k % 3 !== 0;
      S.active().forEach((x) => S.set(x.id, good ? x.goal : x.goal * 0.2, d));
      st.dayLogs[d] = { mood: good ? 4 + (k % 2) : 1 + (k % 2), energy: 1 + ((k * 7) % 5), note: "" };
    }
    S.save();
  });
  await a.evaluate(() => { location.hash = "#today"; }); await a.evaluate(() => { location.hash = "#stats"; });
  await a.waitForSelector(".corr");
  const corr = await a.locator(".corr").allInnerTexts();
  check(corr.some((t) => /better mood goes with more goals hit/.test(t) && /strong/.test(t)) && !corr.some((t) => /energy/.test(t) && /strong/.test(t)),
    "correlations: mood that tracks output reads as strong, noise doesn't");

  // Best week: seed two past weeks, the better one is picked and replays
  const bestMonday = await a.evaluate(() => {
    const S = Diligent.store, m1 = S.addDays(S.weekStart(S.today()), -14), m2 = S.addDays(m1, 7);
    S.active().forEach((x) => { for (let i = 0; i < 7; i++) { S.set(x.id, x.goal, S.addDays(m1, i)); S.set(x.id, x.goal / 2, S.addDays(m2, i)); } });
    return m1;
  });
  await a.evaluate(() => { location.hash = "#today"; }); await a.evaluate(() => { location.hash = "#stats"; });
  await a.waitForSelector(".bestweek");
  check((await a.locator(".bestweek .big").first().innerText()) === "100%" && await a.evaluate((m) => Diligent.store.bestWeek().monday === m, bestMonday), "best week finds the strongest finished week");
  await a.click("#replay-week");
  await a.waitForTimeout(3500);
  // Coach letter: the API call is intercepted, never sent
  let sent = null, reply = { status: 200, json: { model: "claude-opus-5-5", stop_reason: "end_turn", content: [{ type: "thinking", thinking: "" }, { type: "text", text: "Strong week.\n→ Start at 9\n→ Walk at 3\n→ Shut down at 10" }] } };
  await a.route("https://api.anthropic.com/v1/messages", async (route) => {
    sent = { headers: route.request().headers(), body: JSON.parse(route.request().postData()) };
    await route.fulfill({ status: reply.status, contentType: "application/json", body: JSON.stringify(reply.json) });
  });
  await a.fill("#coach-key", "sk-ant-test-key");
  await a.click("#coach-key-save");
  await a.click("#coach-write");
  await a.waitForSelector("#coach-letter");
  check(sent && sent.headers["x-api-key"] === "sk-ant-test-key" && sent.headers["anthropic-version"] === "2026-10-21"
    && sent.headers["anthropic-beta"] === "server-side-fallback-2026-07-01" && sent.headers["anthropic-dangerous-direct-browser-access"] === "true"
    && sent.body.model === "claude-opus-5-5" && sent.body.fallbacks === "default" && Array.isArray(sent.body.messages)
    && !JSON.stringify(sent.body).includes("What went well"), "coach letter: request shape, and no notes in what's sent");
  check((await a.locator("#coach-letter").innerText()).includes("Shut down at 10") && await a.locator("#coach-letter .arrow").count() === 3, "coach letter renders the text blocks and the three experiments");
  await a.click('#coach-period button[data-v="month"]');
  await a.click("#coach-write");
  await a.waitForTimeout(600);
  check(sent.body.system.includes("monthly letter") && sent.body.messages[0].content.includes('"this_month_vs_last"'), "the monthly letter sends 30 days vs the 30 before");
  reply = { status: 200, json: { model: "claude-opus-5-5", stop_reason: "refusal", content: [] } };
  await a.click("#coach-write");
  await a.waitForFunction(() => [...document.querySelectorAll(".toast")].some((t) => t.textContent.includes("declined")), null, { timeout: 5000 }).catch(() => {});
  check(await a.locator(".toast", { hasText: "declined" }).count() > 0 && (await a.locator("#coach-letter").innerText()).includes("Strong week"), "a refusal keeps the last letter and says so");
  reply = { status: 401, json: { type: "error", error: { type: "authentication_error", message: "invalid x-api-key" } } };
  await a.click("#coach-write");
  await a.waitForFunction(() => [...document.querySelectorAll(".toast")].some((t) => t.textContent.includes("rejected")), null, { timeout: 5000 }).catch(() => {});
  check(await a.locator(".toast", { hasText: "rejected" }).count() > 0, "a bad key gets a clear message");
  check(!(await a.evaluate(() => localStorage.getItem("diligent.web.v1"))).includes("sk-ant-test-key") && !(await a.evaluate(() => Diligent.store.exportJson())).includes("sk-ant-test-key"), "the API key stays out of the synced and exported data");
  await a.unroute("https://api.anthropic.com/v1/messages");
  await a.click("#coach-key-clear").catch(() => {});

  await a.click("#weekly-card");
  await a.waitForSelector("#card-dialog[open] img");
  const card = await a.evaluate(async () => { const img = document.querySelector("#card-dialog img"); await img.decode(); return [img.naturalWidth, img.naturalHeight]; });
  check(card[0] === 1080 && card[1] === 1350, "weekly card renders a 1080×1350 PNG");
  await a.screenshot({ path: process.env.CARD_SHOT || "/dev/null" }).catch(() => {});
  await a.click('#card-dialog [data-c="close"]');
  await a.locator("#heat i").nth(-2).click();
  check(await a.evaluate(() => location.hash) === "#today" && (await a.locator(".daynav h3").innerText()) !== "TODAY", "heatmap opens a past day");

  // Schedules and long-term targets, set in the activity editor
  await blur(a);
  await a.keyboard.press("t");
  await a.locator('.act:has(h3:text-is("Gym")) h3').click();
  await a.click('#ed-sched button[data-v="weekly"]');
  await a.fill("#ed-count", "3");
  await a.click("#ed-save");
  await a.waitForTimeout(200);
  check(/\d\/3 this week|week done/.test(await a.locator('.act:has(h3:text-is("Gym")) .meta').innerText()), "a 3× a week schedule shows its weekly count");
  await a.locator('.act:has(h3:text-is("Quantum Computing")) h3').click();
  await a.fill("#ed-target", "200");
  await a.fill("#ed-target-date", await a.evaluate(() => Diligent.store.addDays(Diligent.store.today(), 60)));
  await a.click("#ed-save");
  await a.waitForTimeout(200);
  check((await a.locator('.act:has(h3:text-is("Quantum Computing")) .target').innerText()).includes("by"), "a long-term target shows its pace on the card");

  // Plan: import a calendar, auto-plan tomorrow around it, no block overlaps a meeting
  await blur(a);
  await a.keyboard.press("2");
  await a.click("#plan-next");
  const tomorrow = await a.evaluate(() => Diligent.store.addDays(Diligent.store.today(), 1).replace(/-/g, ""));
  const ics = ["BEGIN:VCALENDAR", "BEGIN:VEVENT", "SUMMARY:Lecture", `DTSTART:${tomorrow}T100000`, `DTEND:${tomorrow}T120000`, "END:VEVENT",
    "BEGIN:VEVENT", "SUMMARY:Standup", `DTSTART:${tomorrow}T090000`, "DURATION:PT15M", "RRULE:FREQ=DAILY", "END:VEVENT", "END:VCALENDAR"].join("\r\n");
  await a.setInputFiles("#ics", { name: "uni.ics", mimeType: "text/calendar", buffer: Buffer.from(ics) });
  await a.waitForSelector(".busy");
  check(await a.locator(".busy").count() === 2, "an imported .ics shows its meetings (and a daily recurrence) as busy");
  await a.click("#autoplan");
  await a.waitForSelector(".pblock");
  const clash = await a.evaluate(() => {
    const P = Diligent.plan, d = Diligent.store.addDays(Diligent.store.today(), 1), busy = P.busyOn(d), bl = P.blocks(d);
    return bl.length && !bl.some((b) => busy.some(([x, y]) => b.start < y && b.start + b.minutes > x) || bl.some((c) => c !== b && b.start < c.start + c.minutes && b.start + b.minutes > c.start));
  });
  check(clash === true, "auto-plan fills free time without overlapping meetings or itself");
  await a.click("#plan-today");

  await connect(a, "Laptop");
  await a.reload();
  await a.waitForSelector(".act");
  const synced = await a.evaluate(() => Diligent.cloud.sync());
  check(/up/.test(synced) && db.tables.diligent_days.length >= 2, `day sync pushes rows (${synced})`);

  const b = await device("B");
  await connect(b, "Phone");
  await b.reload();
  await b.waitForSelector(".act");
  await b.evaluate(() => Diligent.cloud.sync());
  check(await b.evaluate(() => { const S = Diligent.store; const x = S.active().find((y) => y.name === "LeetCode"); return S.progressOf(S.today(), x.id); }) === 3, "the second device pulls the synced day");

  await blur(a);
  await a.keyboard.press("5");
  await a.fill("#clip-text", "hello from the laptop");
  await a.click("#clip-send");
  await blur(b);
  await b.keyboard.press("5");
  await b.waitForSelector(".clip pre");
  check((await b.locator(".clip pre").allInnerTexts()).includes("hello from the laptop"), "a clip reaches the other device");
  await a.waitForFunction(() => Diligent.cloud.liveState() === "open", null, { timeout: 5000 }).catch(() => {});
  check(await a.evaluate(() => Diligent.cloud.liveState()) === "open", "the clipboard opens a Realtime socket");
  await b.waitForFunction(() => Diligent.cloud.liveState() === "open", null, { timeout: 5000 }).catch(() => {});
  await a.waitForTimeout(500); // let both joins land
  const sentAt = Date.now(), deliveredBefore = db.realtime.delivered;
  await b.fill("#clip-text", "reply from the phone");
  await b.keyboard.press("Control+Enter");
  await a.waitForFunction(() => document.body.innerText.includes("reply from the phone"), null, { timeout: 20000 });
  const took = Date.now() - sentAt;
  // The ping reached the other tab, and the clip arrived well before a 16 s poll would bring it.
  check(db.realtime.delivered > deliveredBefore && took < 8000, `the reply arrives through a Realtime ping (${took} ms, polling is 16 s while live)`);
  await a.locator(".clip").first().locator('[data-clip="copy"]').click();
  check(await a.evaluate(() => navigator.clipboard.readText()) === "reply from the phone", "copy puts the clip on the system clipboard");

  // Pin the laptop clip, age both by two days: the pinned one stays, the other expires.
  await a.locator('.clip:has-text("hello from the laptop") [data-clip="pin"]').click();
  await a.waitForSelector(".clip.pinned");
  db.tables.diligent_clips.forEach((r) => { r.created_at -= 48 * 3600e3; });
  await a.evaluate(() => { location.hash = "#today"; });
  await a.evaluate(() => { location.hash = "#clipboard"; });
  await a.waitForFunction(() => document.querySelectorAll(".clip").length === 1, null, { timeout: 8000 }).catch(() => {});
  const left = await a.locator(".clip pre").allInnerTexts();
  check(left.length === 1 && left[0] === "hello from the laptop", "pinned clips stay, unpinned ones expire after a day");
  await a.waitForTimeout(400);
  check(db.tables.diligent_clips.length === 1 && db.tables.diligent_clips[0].pinned === true, "expired clips are deleted from Supabase");

  // End-to-end: with a shared passphrase Supabase stores only ciphertext; without it the clip is locked.
  for (const p of [a, b]) await p.evaluate(() => { Diligent.store.state.cloud.clipPass = "correct horse"; Diligent.store.save(); });
  await b.fill("#clip-text", "secret for my laptop");
  await b.keyboard.press("Control+Enter");
  await a.waitForFunction(() => document.body.innerText.includes("secret for my laptop"), null, { timeout: 20000 }).then(() => true, () => false);
  const row = db.tables.diligent_clips.find((r) => String(r.text).startsWith("enc1:"));
  check(!!row && !db.tables.diligent_clips.some((r) => String(r.text).includes("secret for my laptop")) && (await a.locator(".clip pre").allInnerTexts()).includes("secret for my laptop"),
    "with a passphrase only ciphertext reaches Supabase, and the other device reads it");
  await a.evaluate(() => { Diligent.store.state.cloud.clipPass = ""; Diligent.store.save(); location.hash = "#today"; });
  await a.evaluate(() => { location.hash = "#clipboard"; });
  await a.waitForFunction(() => document.body.innerText.includes("🔒 Encrypted"), null, { timeout: 8000 }).then(() => true, () => false);
  check((await a.locator(".clip pre").allInnerTexts()).some((t) => t.includes("🔒 Encrypted")), "without the passphrase the clip shows as locked");

  // Files: encrypted before upload, downloaded and decrypted intact, deleted with the clip
  await a.evaluate(() => { Diligent.store.state.cloud.clipPass = "correct horse"; Diligent.store.save(); location.hash = "#today"; });
  await a.evaluate(() => { location.hash = "#clipboard"; });
  await a.waitForSelector("#clip-attach:not([disabled])", { state: "attached" });
  const pdf = Buffer.from("%PDF-1.4 smoke test " + "x".repeat(5000));
  await a.setInputFiles("#clip-attach", { name: "notes.pdf", mimeType: "application/pdf", buffer: pdf });
  await a.waitForSelector(".filebox");
  const stored = [...db.objects.values()][0];
  const [dlf] = await Promise.all([a.waitForEvent("download"), a.locator('.clip:has(.filebox) [data-clip="save"]').click()]);
  const got = fs.readFileSync(await dlf.path());
  check(stored && !stored.includes(Buffer.from("%PDF")) && dlf.suggestedFilename() === "notes.pdf" && got.equals(pdf), "files are encrypted in Storage and download intact");
  await a.locator('.clip:has(.filebox) [data-clip="del"]').click();
  await a.waitForTimeout(500);
  check(db.objects.size === 0, "deleting a file clip deletes the file");
  await a.evaluate(() => { Diligent.store.state.cloud.clipPass = ""; Diligent.store.save(); });

  await blur(a);
  await a.keyboard.press("6");
  await a.fill("#room-code", "SMOKE1");
  await a.click("#room-join");
  await a.waitForSelector(".member");
  check(db.tables.diligent_presence.some((r) => r.room === "SMOKE1"), "joining a room publishes presence");
  check(await a.locator(".pool .mblob:not(.core)").count() >= 1, "room members show as liquid blobs");

  // Room chat: A says something, B (in the same room) sees it; the clipboard doesn't list it
  await a.fill("#chat-text", "anyone up for a 50 min block?");
  await a.press("#chat-text", "Enter");
  await a.waitForSelector(".msg.mine");
  await b.evaluate(() => { Diligent.store.state.cloud.room = "SMOKE1"; Diligent.store.save(); location.hash = "#today"; });
  await b.evaluate(() => { location.hash = "#room"; });
  await b.waitForFunction(() => document.body.innerText.includes("anyone up for a 50 min"), null, { timeout: 8000 }).then(() => true, () => false);
  check((await b.locator(".msg").allInnerTexts()).some((t) => t.includes("anyone up for a 50 min block?"))
    && !(await a.evaluate(async () => (await Diligent.cloud.clips("room")).some((c) => c.text.includes("50 min block")))), "room chat reaches the other device and stays out of the clipboard");

  // Challenges: start one from a template, a second device joins with the code, both on the board
  await a.click('[data-tpl="1"]');
  check((await a.inputValue("#ch-kw")) === "Quantum", "a challenge template fills the form");
  await a.click("#ch-create");
  await a.waitForFunction(() => (Diligent.store.state.challenges || []).length === 1, null, { timeout: 8000 });
  const chCode = await a.evaluate(() => Diligent.store.state.challenges[0].code);
  await b.evaluate(() => { Diligent.store.state.settings.name = "Friend"; Diligent.store.save(); });
  await blur(b);
  await b.keyboard.press("6");
  await b.fill("#ch-code", chCode);
  await b.click("#ch-join");
  await b.waitForFunction(() => document.querySelectorAll(".challenge .stand").length >= 1, null, { timeout: 8000 }).catch(() => {});
  // Both test devices share one owner id, so they are one member with one score row (like the app).
  const rows = db.tables.diligent_scores.filter((r) => r.code === chCode);
  const expected = await a.evaluate((c) => Diligent.cloud.challengeTotal(Diligent.store.state.challenges.find((x) => x.code === c)), chCode);
  check(rows.length === 1 && expected > 2 && Math.abs(rows[0].total - expected) < 0.01 && await b.evaluate((c) => Diligent.store.state.challenges.some((x) => x.code === c), chCode)
    && await b.locator(".challenge .stand").count() === 1, `a second device joins by code and the board shows the Quantum total (${rows.length} row, total ${rows[0] && rows[0].total})`);

  // Room passphrase: room clips are encrypted with it
  await a.fill("#room-pass", "room secret");
  await a.click("#room-pass-save");
  await a.click("#room-clip");
  await a.waitForSelector("#clip-text");
  await a.fill("#clip-text", "notes for the room");
  await a.click("#clip-send");
  await a.waitForFunction(() => document.body.innerText.includes("notes for the room"), null, { timeout: 8000 }).catch(() => {});
  const roomRow = db.tables.diligent_clips.find((r) => r.scope === "room:SMOKE1" && r.kind !== "chat");
  check(!!roomRow && roomRow.text.startsWith("enc1:") && (await a.locator(".clip pre").allInnerTexts()).includes("notes for the room"),
    "room clips are encrypted with the room passphrase and read back");

  // Public page: a static file with no notes and no scripts
  await a.evaluate(() => { const S = Diligent.store; S.note(S.active()[0].id, "private note do not publish", S.today()); });
  await blur(a);
  await a.keyboard.press("7");
  await a.fill("#pp-title", "Arindam's year");
  const [dl] = await Promise.all([a.waitForEvent("download"), a.click("#pp-download")]);
  const html = fs.readFileSync(await dl.path(), "utf8");
  check(dl.suggestedFilename() === "index.html" && html.includes("Arindam&#39;s year") && (html.match(/<circle /g) || []).length >= 365
    && !html.includes("private note") && !/<script/i.test(html), "public page: a static year with no notes and no scripts");

  const m = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  await block(m);
  const mp = await m.newPage();
  mp.on("pageerror", (e) => errors.push(`[mobile] ${e.message}`));
  await mp.goto(`http://localhost:${WEB}/#today`);
  await mp.waitForSelector(".act");
  check(await mp.evaluate(() => document.documentElement.scrollWidth - innerWidth) <= 0, "no sideways scroll at phone width");
} catch (e) {
  console.error(e);
  failures++;
}
check(errors.length === 0, "no page errors" + (errors.length ? ":\n  " + errors.join("\n  ") : ""));
await browser.close();
web.close();
db.server.close();
console.log(failures ? `\n${failures} check(s) failed` : "\nall checks passed");
process.exit(failures ? 1 : 0);
