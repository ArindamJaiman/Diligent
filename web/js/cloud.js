/*
 * Your own Supabase project, the same one the app uses (docs/CLOUD.md): days sync both ways
 * by activity name, the shared clipboard (diligent_clips) and study rooms (diligent_presence).
 *
 * The clipboard has three scopes:
 *   local   this browser only, live across its tabs (BroadcastChannel), nothing leaves it
 *   mine    your devices: rows scoped by the owner id, like synced days
 *   room    everyone with the room code: rows scoped by "room:<CODE>"
 */
(function () {
  "use strict";
  const D = (window.Diligent = window.Diligent || {});
  const S = D.store;
  const cfg = () => S.state.cloud;

  const ready = () => !!(cfg().url && cfg().key && cfg().owner);
  const device = () => cfg().device || S.state.settings.name || guessDevice();
  function guessDevice() {
    const ua = navigator.userAgent;
    const os = /Windows/.test(ua) ? "Windows" : /Mac OS X/.test(ua) ? "Mac" : /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Linux/.test(ua) ? "Linux" : "Web";
    const br = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
    return `${br} on ${os}`;
  }

  async function rest(path, { method = "GET", body, headers = {}, prefer } = {}) {
    const c = cfg();
    const h = Object.assign({
      apikey: c.key, Authorization: `Bearer ${c.key}`, "Content-Type": "application/json", "x-diligent-owner": c.owner,
    }, headers);
    if (prefer) h.Prefer = prefer;
    const res = await fetch(`${c.url.replace(/\/$/, "")}/rest/v1/${path}`, { method, headers: h, body: body ? JSON.stringify(body) : undefined });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`Supabase ${res.status}${text ? ": " + text.slice(0, 140) : ""}`);
    }
    return res.status === 204 || method === "DELETE" || (prefer || "").includes("return=minimal") ? null : res.json();
  }

  // ─── Days ─────────────────────────────────────────────
  /** Pushes rows changed since the last push, pulls rows others changed since the last pull. */
  async function sync() {
    if (!ready()) throw new Error("Add your Supabase project in Settings first");
    const c = cfg();
    const now = Date.now();
    const from = S.addDays(S.today(), -35);
    const up = [];
    for (const date of Object.keys(S.state.progress)) {
      if (date < from) continue;
      for (const [id, r] of Object.entries(S.state.progress[date])) {
        const a = S.byId(id);
        if (!a || a.archived || (r.t || 0) <= c.lastPush) continue;
        up.push({ owner: c.owner, date, activity: a.name, unit: a.unit, progress: r.p || 0, goal: a.goal, completed: S.met(a, date), updated_at: r.t || now });
      }
    }
    if (up.length) await rest("diligent_days?on_conflict=owner,date,activity", { method: "POST", body: up, prefer: "resolution=merge-duplicates,return=minimal" });

    const rows = await rest(`diligent_days?owner=eq.${encodeURIComponent(c.owner)}&updated_at=gt.${c.lastPull || 0}&select=date,activity,unit,progress,goal,completed,updated_at&order=date.asc`);
    let pulled = 0, created = 0;
    const byName = {};
    S.state.activities.forEach((a) => { if (!a.archived) byName[a.name.toLowerCase()] = a; });
    for (const row of rows) {
      let a = byName[String(row.activity).toLowerCase()];
      if (!a) {
        a = { id: S.uid(), name: row.activity, unit: row.unit || "hrs", goal: row.goal || 1, goalType: "at_least", color: S.COLORS[S.state.activities.length % S.COLORS.length], order: S.state.activities.length, archived: false, createdAt: S.parse(row.date).getTime() };
        S.state.activities.push(a);
        byName[a.name.toLowerCase()] = a;
        created++;
      }
      const day = (S.state.progress[row.date] ||= {});
      const local = day[a.id];
      if (!local || (local.t || 0) < row.updated_at) {
        day[a.id] = Object.assign(local || { note: "", skip: false }, { p: row.progress || 0, done: !!row.completed && !S.isLimit(a), t: row.updated_at });
        pulled++;
      }
    }
    c.lastPush = now;
    c.lastPull = now;
    S.emit({ type: "sync" });
    return `${up.length} up · ${pulled} down` + (created ? ` · ${created} new activities` : "");
  }

  // ─── Clipboard ────────────────────────────────────────
  const channel = "BroadcastChannel" in window ? new BroadcastChannel("diligent-clipboard") : null;
  const clipListeners = new Set();
  if (channel) channel.onmessage = () => clipListeners.forEach((fn) => fn("local"));

  function scopeId(scope) {
    if (scope === "mine") return cfg().owner;
    if (scope === "room") return "room:" + (cfg().room || "").toUpperCase();
    return null;
  }
  function scopeHeaders(scope) { return scope === "room" ? { "x-diligent-room": (cfg().room || "").toUpperCase() } : {}; }
  const MAX_CHARS = 20000, MAX_IMAGE = 350000;

  async function clips(scope) {
    if (scope === "local") return S.state.clips.slice().sort((a, b) => b.createdAt - a.createdAt);
    if (!ready()) throw new Error("Add your Supabase project in Settings to share between devices");
    if (scope === "room" && !cfg().room) return [];
    const rows = await rest(`diligent_clips?scope=eq.${encodeURIComponent(scopeId(scope))}&select=id,text,kind,device,created_at&order=created_at.desc&limit=60`, { headers: scopeHeaders(scope) });
    return rows.map((r) => ({ id: r.id, text: r.text, kind: r.kind || "text", device: r.device, createdAt: r.created_at }));
  }

  async function pushClip(scope, text, kind) {
    kind = kind || "text";
    if (!text) return;
    if (kind === "text") text = String(text).slice(0, MAX_CHARS);
    else if (text.length > MAX_IMAGE) throw new Error("That image is too big to share (max ~250 KB). Try a screenshot of a smaller area.");
    const clip = { id: S.uid(), text, kind, device: device(), createdAt: Date.now() };
    if (scope === "local") {
      S.state.clips.unshift(clip);
      S.state.clips = S.state.clips.slice(0, 100);
      S.emit({ type: "clips" });
      if (channel) channel.postMessage("changed");
      return clip;
    }
    if (!ready()) throw new Error("Add your Supabase project in Settings to share between devices");
    await rest("diligent_clips?on_conflict=scope,id", {
      method: "POST", headers: scopeHeaders(scope), prefer: "resolution=merge-duplicates,return=minimal",
      body: [{ scope: scopeId(scope), id: clip.id, text: clip.text, kind: clip.kind, device: clip.device, created_at: clip.createdAt }],
    });
    return clip;
  }

  async function deleteClip(scope, id) {
    if (scope === "local") {
      S.state.clips = S.state.clips.filter((c) => c.id !== id);
      S.emit({ type: "clips" });
      if (channel) channel.postMessage("changed");
      return;
    }
    await rest(`diligent_clips?scope=eq.${encodeURIComponent(scopeId(scope))}&id=eq.${encodeURIComponent(id)}`, { method: "DELETE", headers: scopeHeaders(scope) });
  }

  async function clearClips(scope) {
    if (scope === "local") { S.state.clips = []; S.emit({ type: "clips" }); if (channel) channel.postMessage("changed"); return; }
    await rest(`diligent_clips?scope=eq.${encodeURIComponent(scopeId(scope))}`, { method: "DELETE", headers: scopeHeaders(scope) });
  }

  // ─── Rooms ────────────────────────────────────────────
  /** Same one-way member id as the app, so you appear once whichever device you use. */
  async function memberId() {
    const data = new TextEncoder().encode("member:" + cfg().owner);
    if (!crypto.subtle) return "web" + cfg().owner.replace(/-/g, "").slice(0, 17);
    const hash = await crypto.subtle.digest("SHA-256", data);
    return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 20);
  }

  async function pushPresence() {
    const room = (cfg().room || "").toUpperCase();
    if (!ready() || !room) return;
    const f = S.state.focus;
    const a = f && S.byId(f.activityId);
    await rest("diligent_presence?on_conflict=room,member", {
      method: "POST", headers: { "x-diligent-room": room }, prefer: "resolution=merge-duplicates,return=minimal",
      body: [{
        room, member: await memberId(), name: S.state.settings.name || "Me", activity: a ? a.name : null,
        started_at: f ? f.startedAt : 0, until_at: f && f.target ? f.startedAt + f.target * 60000 : 0, broken: false, updated_at: Date.now(),
      }],
    });
  }

  async function members() {
    const room = (cfg().room || "").toUpperCase();
    if (!ready() || !room) return [];
    const rows = await rest(`diligent_presence?room=eq.${encodeURIComponent(room)}&select=name,activity,started_at,until_at,broken,updated_at&order=updated_at.desc`, { headers: { "x-diligent-room": room } });
    return rows.map((r) => ({ name: r.name || "?", activity: r.activity, since: r.started_at, until: r.until_at, broken: !!r.broken, updatedAt: r.updated_at }));
  }

  async function leaveRoom() {
    const room = (cfg().room || "").toUpperCase();
    if (ready() && room) {
      try { await rest(`diligent_presence?room=eq.${encodeURIComponent(room)}&member=eq.${await memberId()}`, { method: "DELETE", headers: { "x-diligent-room": room } }); } catch {}
    }
    cfg().room = "";
    S.emit({ type: "room" });
  }

  const newCode = () => Array.from({ length: 6 }, () => "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Math.floor(Math.random() * 32)]).join("");

  D.cloud = {
    ready, device, sync, clips, pushClip, deleteClip, clearClips, onClips: (fn) => { clipListeners.add(fn); return () => clipListeners.delete(fn); },
    pushPresence, members, leaveRoom, newCode, memberId, MAX_CHARS,
  };
})();
