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

chrome.runtime.onInstalled.addListener(() => {
  chrome.alarms.create("tick", { periodInMinutes: TICK_MINUTES });
  chrome.alarms.create("upload", { periodInMinutes: UPLOAD_MINUTES });
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
