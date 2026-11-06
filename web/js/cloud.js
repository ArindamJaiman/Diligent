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


  // ─── End-to-end encryption for your own clips ─────────
  // AES-256-GCM, key from the clipboard passphrase (PBKDF2-SHA256, 100,000 rounds), stored as
  // "enc1:" + base64(iv ‖ ciphertext ‖ tag): the same bytes the Android app's ClipCrypto writes.
  // Room clips stay plain (everyone in the room would need the passphrase).
  const ENC = "enc1:";
  const clipKeys = new Map();
  const passphrase = (scope) => (scope === "mine" && (S.state.cloud.clipPass || "").trim()) || null;
  function clipKey(pass) {
    if (!clipKeys.has(pass)) clipKeys.set(pass, (async () => {
      const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(pass), "PBKDF2", false, ["deriveKey"]);
      return crypto.subtle.deriveKey({ name: "PBKDF2", salt: new TextEncoder().encode("diligent-clips-v1"), iterations: 100000, hash: "SHA-256" },
        base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
    })());
    return clipKeys.get(pass);
  }
  function b64(bytes) { let s = ""; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)); return btoa(s); }
  const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
  async function seal(plain, pass) {
    if (!crypto.subtle) throw new Error("Encryption needs https (or localhost); remove the passphrase to send plain clips");
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await clipKey(pass), new TextEncoder().encode(plain)));
    const out = new Uint8Array(12 + ct.length); out.set(iv); out.set(ct, 12);
    return ENC + b64(out);
  }
  /** Plain text; plain clips pass through; null without the right passphrase. */
  async function unseal(stored, pass) {
    if (!stored || !stored.startsWith(ENC)) return stored;
    if (!pass || !crypto.subtle) return null;
    try {
      const raw = unb64(stored.slice(ENC.length));
      return new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: raw.subarray(0, 12) }, await clipKey(pass), raw.subarray(12)));
    } catch { return null; }
  }

  /** Unpinned clips older than this are deleted (0 = keep forever). Pinned ones always stay. */
  const ttlMs = () => { const h = S.state.settings.clipTtl; return (h == null ? 24 : +h) * 3600e3; };
  const order = (list) => list.sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || b.createdAt - a.createdAt);
  const fresh = (c) => c.pinned || !ttlMs() || Date.now() - c.createdAt < ttlMs();

  async function clips(scope) {
    if (scope === "local") {
      const keep = S.state.clips.filter(fresh);
      if (keep.length !== S.state.clips.length) { S.state.clips = keep; S.save(); }
      return order(keep.slice());
    }
    if (!ready()) throw new Error("Add your Supabase project in Settings to share between devices");
    if (scope === "room" && !cfg().room) return [];
    const where = `scope=eq.${encodeURIComponent(scopeId(scope))}`;
    // select=* so a table made before the pinned column still works
    const rows = await rest(`diligent_clips?${where}&select=*&order=created_at.desc&limit=80`, { headers: scopeHeaders(scope) });
    const pass = passphrase(scope);
    const list = await Promise.all(rows.map(async (r) => {
      const plain = await unseal(r.text, pass);
      return { id: r.id, text: plain == null ? "" : plain, raw: r.text, locked: plain == null, kind: r.kind || "text", device: r.device, createdAt: r.created_at, pinned: !!r.pinned };
    }));
    if (ttlMs() && list.some((c) => !fresh(c))) {
      const cut = Date.now() - ttlMs();
      const pinCol = rows.some((r) => "pinned" in r);
      rest(`diligent_clips?${where}&created_at=lt.${cut}${pinCol ? "&pinned=not.is.true" : ""}`, { method: "DELETE", headers: scopeHeaders(scope) }).catch(() => {});
    }
    return order(list.filter(fresh));
  }

  async function setPinned(scope, clip, pinned) {
    if (scope === "local") {
      const c = S.state.clips.find((x) => x.id === clip.id); if (c) c.pinned = pinned;
      S.emit({ type: "clips" });
      if (channel) channel.postMessage("changed");
      return;
    }
    try {
      await rest("diligent_clips?on_conflict=scope,id", {
        method: "POST", headers: scopeHeaders(scope), prefer: "resolution=merge-duplicates,return=minimal",
        body: [{ scope: scopeId(scope), id: clip.id, text: clip.raw != null ? clip.raw : clip.text, kind: clip.kind, device: clip.device, created_at: clip.createdAt, pinned }],
      });
      ping(scopeKey(scope));
    } catch (e) {
      if (/pinned/.test(e.message)) throw new Error("Pinning needs the pinned column: run the clipboard SQL in docs/CLOUD.md again");
      throw e;
    }
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
      body: [{ scope: scopeId(scope), id: clip.id, text: passphrase(scope) ? await seal(clip.text, passphrase(scope)) : clip.text, kind: clip.kind, device: clip.device, created_at: clip.createdAt }],
    });
    ping(scopeKey(scope));
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
    ping(scopeKey(scope));
  }

  async function clearClips(scope) {
    if (scope === "local") { S.state.clips = S.state.clips.filter((c) => c.pinned); S.emit({ type: "clips" }); if (channel) channel.postMessage("changed"); return; }
    // Clears the unpinned ones; falls back to everything on a table without the pinned column.
    const where = `diligent_clips?scope=eq.${encodeURIComponent(scopeId(scope))}`;
    try { await rest(where + "&pinned=not.is.true", { method: "DELETE", headers: scopeHeaders(scope) }); }
    catch { await rest(where, { method: "DELETE", headers: scopeHeaders(scope) }); }
    ping(scopeKey(scope));
  }


  // ─── Live: Supabase Realtime broadcast ────────────────
  // Realtime's database-change feed can't see these rows (the row policies read custom
  // headers), so clients send each other a tiny "changed" ping on a channel named from a
  // SHA-256 of the scope, and refetch through the REST API when one arrives. Pings carry
  // no data. If the socket can't open, polling carries on as before.
  const live = { ws: null, state: "off", ref: 0, topics: new Map(), retry: 0, beat: 0, url: "" };
  async function topicFor(key) {
    const bytes = new TextEncoder().encode("diligent:" + key);
    const hex = crypto.subtle ? Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))).map((b) => b.toString(16).padStart(2, "0")).join("") : key.replace(/[^a-z0-9]/gi, "");
    return "realtime:diligent-" + hex.slice(0, 32);
  }
  function liveSend(msg) { if (live.ws && live.ws.readyState === 1) live.ws.send(JSON.stringify(Object.assign({ ref: String(++live.ref) }, msg))); }
  function liveJoin(topic) { liveSend({ topic, event: "phx_join", payload: { config: { broadcast: { self: false }, presence: { key: "" } } }, join_ref: String(live.ref + 1) }); }
  function liveConnect() {
    if (!ready() || !("WebSocket" in window)) return;
    const url = cfg().url.replace(/^http/, "ws").replace(/\/$/, "") + "/realtime/v1/websocket?apikey=" + encodeURIComponent(cfg().key) + "&vsn=1.0.0";
    if (live.ws && live.url === url && live.ws.readyState <= 1) return;
    if (live.ws) try { live.ws.close(); } catch {}
    live.url = url;
    live.state = "connecting";
    let ws;
    try { ws = new WebSocket(url); } catch { live.state = "off"; return; }
    live.ws = ws;
    ws.onopen = () => {
      live.state = "open"; live.retry = 0;
      for (const topic of live.topics.keys()) liveJoin(topic);
      clearInterval(live.beat);
      live.beat = setInterval(() => liveSend({ topic: "phoenix", event: "heartbeat", payload: {} }), 25000);
    };
    ws.onmessage = (e) => {
      let m; try { m = JSON.parse(e.data); } catch { return; }
      if (m.event === "broadcast" && m.payload && m.payload.event === "changed") (live.topics.get(m.topic) || new Set()).forEach((fn) => fn(m.payload.payload || {}));
    };
    ws.onclose = () => {
      clearInterval(live.beat);
      if (live.ws !== ws) return;
      live.state = "off"; live.ws = null;
      if (live.topics.size && live.retry < 6) setTimeout(liveConnect, 1000 * Math.pow(2, live.retry++));
    };
    ws.onerror = () => {};
  }
  /** Calls fn when another device changes this scope ("mine" / "room"); returns an unsubscribe. */
  function watch(key, fn) {
    let topic = null, gone = false;
    topicFor(key).then((t) => {
      if (gone) return;
      topic = t;
      const set = live.topics.get(t) || new Set();
      const first = !set.size;
      set.add(fn); live.topics.set(t, set);
      if (live.state === "open") { if (first) liveJoin(t); } else liveConnect();
    });
    return () => {
      gone = true;
      if (!topic) return;
      const set = live.topics.get(topic); if (!set) return;
      set.delete(fn);
      if (!set.size) { live.topics.delete(topic); liveSend({ topic, event: "phx_leave", payload: {} }); }
    };
  }
  async function ping(key, what) {
    if (live.state !== "open") return;
    const topic = await topicFor(key);
    if (!live.topics.has(topic)) liveJoin(topic); // the server needs a join before a broadcast
    liveSend({ topic, event: "broadcast", payload: { type: "broadcast", event: "changed", payload: { what: what || "clips" } } });
  }
  const scopeKey = (scope) => scope === "room" ? "room:" + (cfg().room || "").toUpperCase() : "owner:" + cfg().owner;

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
    ping("room:" + room, "presence");
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
    ready, device, sync, clips, pushClip, deleteClip, clearClips, setPinned, onClips: (fn) => { clipListeners.add(fn); return () => clipListeners.delete(fn); },
    pushPresence, members, leaveRoom, newCode, memberId, MAX_CHARS,
    watch, scopeKey, liveState: () => live.state, encrypting: (scope) => !!passphrase(scope || "mine"),
  };
})();
