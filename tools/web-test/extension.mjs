// Loads the browser extension (companion/extension) in Chromium and checks that its right-click
// clipboard path writes the same rows as the app: an encrypted clip the web app can read back.
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { startMockSupabase } from "./mock-supabase.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, "../../web"), EXT = path.resolve(HERE, "../../companion/extension");
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".webmanifest": "application/manifest+json" };
const WEB_PORT = 8093, DB_PORT = 8794;
const web = http.createServer((req, res) => {
  const p = path.join(WEB, decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/\/$/, "/index.html"));
  if (!p.startsWith(WEB) || !fs.existsSync(p)) { res.statusCode = 404; return res.end(); }
  res.setHeader("Content-Type", TYPES[path.extname(p)] || "application/octet-stream");
  fs.createReadStream(p).pipe(res);
}).listen(WEB_PORT);
const db = await startMockSupabase(DB_PORT);

let failures = 0;
const check = (ok, what) => { console.log(`${ok ? "✓" : "✗"} ${what}`); if (!ok) failures++; };
const profile = fs.mkdtempSync(path.join(os.tmpdir(), "diligent-ext-"));
// Extensions need full Chromium (the headless shell can't load them).
const ctx = await chromium.launchPersistentContext(profile, { channel: "chromium", headless: true, args: [`--disable-extensions-except=${EXT}`, `--load-extension=${EXT}`] });
try {
  const sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent("serviceworker", { timeout: 20000 });
  const result = await sw.evaluate(async (port) => {
    await chrome.storage.local.set({ config: { url: `http://localhost:${port}`, key: "anon", owner: "owner-ext-0001", device: "Laptop Chrome", pass: "correct horse" } });
    return await sendClip("from the extension ✓");
  }, DB_PORT);
  const row = db.tables.diligent_clips && db.tables.diligent_clips[0];
  check(result.ok && row && row.scope === "owner-ext-0001" && row.device === "Laptop Chrome" && row.text.startsWith("enc1:"), "the extension sends an encrypted clip to your own scope");
  const page = await ctx.newPage();
  await page.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.fulfill({ status: 200, contentType: "text/css", body: "" }));
  await page.goto(`http://localhost:${WEB_PORT}/#today`);
  const read = await page.evaluate(async (port) => {
    Object.assign(Diligent.store.state.cloud, { url: `http://localhost:${port}`, key: "anon", owner: "owner-ext-0001", clipPass: "correct horse" });
    return (await Diligent.cloud.clips("mine")).map((c) => c.text);
  }, DB_PORT);
  check(read.includes("from the extension ✓"), "the web app decrypts it");
  const unset = await sw.evaluate(async () => { await chrome.storage.local.set({ config: {} }); return await sendClip("x"); });
  check(!unset.ok && /options/.test(unset.why), "without set-up it says so instead of failing silently");
} catch (e) {
  console.error(e);
  failures++;
}
await ctx.close();
web.close();
db.server.close();
fs.rmSync(profile, { recursive: true, force: true });
console.log(failures ? `\n${failures} check(s) failed` : "\nall checks passed");
process.exit(failures ? 1 : 0);
