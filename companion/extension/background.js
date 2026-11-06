// Diligent companion: time on the focused tab while you're active, per mapped activity.
// State lives in chrome.storage.local; uploads go only to the Supabase project in the options.

const TICK_MINUTES = 1;
const UPLOAD_MINUTES = 5;
const IDLE_SECONDS = 120;

const today = () => new Date().toLocaleDateString("en-CA"); // yyyy-mm-dd, local time

async function settings() {
  const { config = {} } = await chrome.storage.local.get("config");
  return config;
}

/** "github.com = Coding" lines → [{ host, activity }]. */
function parseMap(text = "") {
  return text.split("\n").map((line) => line.split("=")).filter((p) => p.length === 2)
    .map(([host, activity]) => ({ host: host.trim().toLowerCase().replace(/^www\./, ""), activity: activity.trim() }))
    .filter((m) => m.host && m.activity);
}

function activityFor(url, map) {
  try {
    const host = new URL(url).hostname.toLowerCase().replace(/^www\./, "");
    const hit = map.find((m) => host === m.host || host.endsWith("." + m.host));
    return hit ? hit.activity : null;
  } catch { return null; }
}

/** Adds the time since the last tick to whatever the focused tab maps to. */
async function tick() {
  const now = Date.now();
  const { last = now, minutes = {} } = await chrome.storage.local.get(["last", "minutes"]);
  await chrome.storage.local.set({ last: now });
  const elapsed = Math.min(now - last, (TICK_MINUTES + 1) * 60_000) / 60_000;
  if (elapsed <= 0) return;
  const state = await chrome.idle.queryState(IDLE_SECONDS);
  if (state !== "active") return;
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  if (!tab?.url) return;
  const window = await chrome.windows.get(tab.windowId);
  if (!window.focused) return;
  const config = await settings();
  const activity = activityFor(tab.url, parseMap(config.map));
  if (!activity) return;
  const d = today();
  minutes[d] ||= {};
  minutes[d][activity] = (minutes[d][activity] || 0) + elapsed;
  // Keep two weeks
  Object.keys(minutes).sort().slice(0, -14).forEach((k) => delete minutes[k]);
  await chrome.storage.local.set({ minutes });
}

async function upload() {
  const config = await settings();
  if (!config.url || !config.key || !config.owner) return;
  const { minutes = {} } = await chrome.storage.local.get("minutes");
  const d = today();
  const rows = Object.entries(minutes[d] || {}).map(([activity, m]) => ({
    owner: config.owner, date: d, activity, minutes: Math.round(m * 10) / 10, updated_at: Date.now()
  }));
  if (!rows.length) return;
  const res = await fetch(`${config.url.replace(/\/$/, "")}/rest/v1/diligent_desktop?on_conflict=owner,date,activity`, {
    method: "POST",
    headers: {
      apikey: config.key, Authorization: `Bearer ${config.key}`, "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates,return=minimal", "x-diligent-owner": config.owner
    },
    body: JSON.stringify(rows)
  });
  await chrome.storage.local.set({ lastUpload: { at: Date.now(), ok: res.ok, status: res.status } });
}

// ─── Right-click → Diligent clipboard ────────────────────
// Same rows as the app and the web app (table diligent_clips, scoped by the owner id). With a
// clipboard passphrase the text is encrypted here first: AES-256-GCM, key from PBKDF2-SHA256
// (100,000 rounds, salt "diligent-clips-v1"), stored as "enc1:" + base64(iv ‖ ciphertext ‖ tag).
async function seal(plain, pass) {
  const enc = new TextEncoder();
  const base = await crypto.subtle.importKey("raw", enc.encode(pass), "PBKDF2", false, ["deriveKey"]);
  const key = await crypto.subtle.deriveKey({ name: "PBKDF2", salt: enc.encode("diligent-clips-v1"), iterations: 100000, hash: "SHA-256" },
    base, { name: "AES-GCM", length: 256 }, false, ["encrypt"]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, enc.encode(plain)));
  const out = new Uint8Array(12 + ct.length); out.set(iv); out.set(ct, 12);
  let s = ""; for (let i = 0; i < out.length; i += 0x8000) s += String.fromCharCode.apply(null, out.subarray(i, i + 0x8000));
  return "enc1:" + btoa(s);
}

async function sendClip(text) {
  const config = await settings();
  if (!config.url || !config.key || !config.owner) return { ok: false, why: "Set up the extension's options first" };
  text = String(text || "").slice(0, 20000);
  if (!text.trim()) return { ok: false, why: "Nothing to send" };
  const pass = (config.pass || "").trim();
  const row = {
    scope: config.owner, id: crypto.randomUUID(), text: pass ? await seal(text, pass) : text, kind: "text",
    device: config.device || "Browser", created_at: Date.now()
  };
  const res = await fetch(`${config.url.replace(/\/$/, "")}/rest/v1/diligent_clips?on_conflict=scope,id`, {
    method: "POST",
    headers: {
      apikey: config.key, Authorization: `Bearer ${config.key}`, "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates,return=minimal", "x-diligent-owner": config.owner
    },
    body: JSON.stringify([row])
  }).catch(() => null);
  return res && res.ok ? { ok: true } : { ok: false, why: res ? `Supabase ${res.status}` : "Offline" };
}

/** A short badge on the toolbar icon: ✓ sent, ! failed (the reason is in the tooltip). */
async function flash(result) {
  await chrome.action.setBadgeBackgroundColor({ color: result.ok ? "#4ade80" : "#ff6b6b" });
  await chrome.action.setBadgeText({ text: result.ok ? "✓" : "!" });
  await chrome.action.setTitle({ title: result.ok ? "Diligent · sent to your clipboard" : "Diligent · " + result.why });
  setTimeout(() => { chrome.action.setBadgeText({ text: "" }); chrome.action.setTitle({ title: "Diligent" }); }, result.ok ? 2500 : 6000);
}

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const text = info.menuItemId === "clip-selection" ? info.selectionText
    : info.menuItemId === "clip-link" ? info.linkUrl
    : info.menuItemId === "clip-image" ? info.srcUrl
    : tab && tab.url ? (tab.title ? `${tab.title}\n${tab.url}` : tab.url) : "";
  flash(await sendClip(text));
});

chrome.runtime.onInstalled.addListener(() => {
  chrome.alarms.create("tick", { periodInMinutes: TICK_MINUTES });
  chrome.alarms.create("upload", { periodInMinutes: UPLOAD_MINUTES });
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: "clip-selection", title: "Send “%s” to Diligent clipboard", contexts: ["selection"] });
    chrome.contextMenus.create({ id: "clip-link", title: "Send link to Diligent clipboard", contexts: ["link"] });
    chrome.contextMenus.create({ id: "clip-image", title: "Send image address to Diligent clipboard", contexts: ["image"] });
    chrome.contextMenus.create({ id: "clip-page", title: "Send this page to Diligent clipboard", contexts: ["page"] });
  });
});
chrome.runtime.onStartup.addListener(() => chrome.storage.local.set({ last: Date.now() }));
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "tick") tick();
  if (alarm.name === "upload") tick().then(upload);
});
// Count up to each switch, so time lands on the tab you were actually on.
chrome.tabs.onActivated.addListener(() => tick());
chrome.windows.onFocusChanged.addListener(() => tick());
chrome.runtime.onMessage.addListener((message, _sender, reply) => {
  if (message === "upload") { tick().then(upload).then(() => reply(true)); return true; }
});
