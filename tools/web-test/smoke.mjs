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
  page.on("console", (m) => { if (m.type() === "error") errors.push(`[${name}] console: ${m.text()}`); });
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
  await a.click("#stop");

  await blur(a);
  await a.keyboard.press("4");
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

  await blur(a);
  await a.keyboard.press("6");
  await a.fill("#room-code", "SMOKE1");
  await a.click("#room-join");
  await a.waitForSelector(".member");
  check(db.tables.diligent_presence.some((r) => r.room === "SMOKE1"), "joining a room publishes presence");
  check(await a.locator(".pool .mblob:not(.core)").count() >= 1, "room members show as liquid blobs");

  // Room passphrase: room clips are encrypted with it
  await a.fill("#room-pass", "room secret");
  await a.click("#room-pass-save");
  await a.click("#room-clip");
  await a.waitForSelector("#clip-text");
  await a.fill("#clip-text", "notes for the room");
  await a.click("#clip-send");
  await a.waitForFunction(() => document.body.innerText.includes("notes for the room"), null, { timeout: 8000 }).catch(() => {});
  const roomRow = db.tables.diligent_clips.find((r) => r.scope === "room:SMOKE1");
  check(!!roomRow && roomRow.text.startsWith("enc1:") && (await a.locator(".clip pre").allInnerTexts()).includes("notes for the room"),
    "room clips are encrypted with the room passphrase and read back");

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
