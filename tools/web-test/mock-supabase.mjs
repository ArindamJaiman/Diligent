// A tiny PostgREST stand-in: eq/gt/gte filters, order, limit, upsert on_conflict, delete,
// with the same header scoping as the row policies in docs/CLOUD.md.
import http from "node:http";
import crypto from "node:crypto";

const KEYS = { diligent_days: ["owner", "date", "activity"], diligent_clips: ["scope", "id"], diligent_presence: ["room", "member"], diligent_challenges: ["code"], diligent_scores: ["code", "member"] };

function allowed(table, row, h) {
  const owner = h["x-diligent-owner"] || "", room = h["x-diligent-room"] || "";
  if (table === "diligent_days") return row.owner === owner;
  if (table === "diligent_clips") return row.scope.length > 5 && (row.scope === owner || row.scope === "room:" + room);
  if (table === "diligent_presence") return row.room === room;
  if (table === "diligent_challenges" || table === "diligent_scores") return row.code === room;
  return true;
}

/** eq, gt, gte, lt, is (true/false/null) and not.<op>. */
function filter(k, v) {
  const [op, ...rest] = v.split(".");
  if (op === "not") { const inner = filter(k, rest.join(".")); return (r) => !inner(r); }
  const val = rest.join(".");
  return (r) => {
    const x = r[k];
    if (op === "eq") return String(x) === val;
    if (op === "gt") return +x > +val;
    if (op === "gte") return String(x) >= val;
    if (op === "lt") return +x < +val;
    if (op === "is") return val === "null" ? x == null : String(!!x) === val && x != null;
    return true;
  };
}

export function startMockSupabase(port) {
  const tables = {};
  const server = http.createServer((req, res) => {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Headers", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET,POST,DELETE,PATCH,OPTIONS");
    if (req.method === "OPTIONS") return res.end();
    const url = new URL(req.url, "http://x");
    if (url.pathname.startsWith("/storage/v1/object/")) return storage(req, res, url);
    const table = url.pathname.replace("/rest/v1/", "");
    const rows = (tables[table] ||= []);
    const filters = [];
    for (const [k, v] of url.searchParams) {
      if (["select", "order", "limit", "on_conflict"].includes(k)) continue;
      filters.push(filter(k, v));
    }
    const match = (r) => allowed(table, r, req.headers) && filters.every((f) => f(r));
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      if (req.method === "GET") {
        let out = rows.filter(match);
        const order = url.searchParams.get("order");
        if (order) { const [col, dir] = order.split("."); out.sort((a, b) => (a[col] > b[col] ? 1 : -1) * (dir === "desc" ? -1 : 1)); }
        const limit = url.searchParams.get("limit");
        if (limit) out = out.slice(0, +limit);
        res.setHeader("Content-Type", "application/json");
        return res.end(JSON.stringify(out));
      }
      if (req.method === "POST") {
        for (const row of JSON.parse(body)) {
          if (!allowed(table, row, req.headers)) { res.statusCode = 403; return res.end('{"message":"row-level security"}'); }
          const key = KEYS[table] || [];
          const i = rows.findIndex((r) => key.every((c) => r[c] === row[c]));
          if (i >= 0) Object.assign(rows[i], row); else rows.push(row);
        }
        res.statusCode = 201;
        return res.end();
      }
      if (req.method === "DELETE") {
        tables[table] = rows.filter((r) => !match(r));
        res.statusCode = 204;
        return res.end();
      }
      res.statusCode = 405;
      res.end();
    });
  });
  const realtime = { broadcasts: 0, delivered: 0, sockets: new Set() };
  // Storage: one bucket, objects by path (raw bytes)
  const objects = new Map();
  function storage(req, res, url) {
    const rest = url.pathname.replace("/storage/v1/object/", "");
    const [bucket, ...parts] = rest.split("/");
    const path = parts.join("/");
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      if (bucket !== "diligent-files") { res.statusCode = 404; return res.end('{"error":"Bucket not found"}'); }
      if (req.method === "POST") { objects.set(path, Buffer.concat(chunks)); res.setHeader("Content-Type", "application/json"); return res.end(JSON.stringify({ Key: `${bucket}/${path}` })); }
      if (req.method === "GET") { const b = objects.get(path); if (!b) { res.statusCode = 400; return res.end('{"error":"not_found"}'); } return res.end(b); }
      if (req.method === "DELETE") { const { prefixes = [] } = JSON.parse(Buffer.concat(chunks).toString() || "{}"); prefixes.forEach((p) => objects.delete(p)); res.setHeader("Content-Type", "application/json"); return res.end("[]"); }
      res.statusCode = 405; res.end();
    });
  }
  server.on("upgrade", (req, socket) => {
    if (!req.url.startsWith("/realtime/v1/websocket")) return socket.destroy();
    const accept = crypto.createHash("sha1").update(req.headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    const client = { socket, topics: new Set() };
    realtime.sockets.add(client);
    const send = (obj) => {
      const data = Buffer.from(JSON.stringify(obj));
      const head = data.length < 126 ? Buffer.from([0x81, data.length]) : Buffer.from([0x81, 126, data.length >> 8, data.length & 255]);
      socket.write(Buffer.concat([head, data]));
    };
    let buf = Buffer.alloc(0);
    socket.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      while (buf.length >= 2) {
        const op = buf[0] & 15;
        let len = buf[1] & 127, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        const masked = buf[1] & 128;
        if (buf.length < off + (masked ? 4 : 0) + len) return;
        const mask = masked ? buf.subarray(off, off + 4) : null;
        off += masked ? 4 : 0;
        const payload = Buffer.from(buf.subarray(off, off + len));
        if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        buf = buf.subarray(off + len);
        if (op === 8) { socket.end(); return; }
        if (op !== 1) continue;
        const m = JSON.parse(payload.toString());
        if (m.event === "phx_join") { client.topics.add(m.topic); send({ topic: m.topic, event: "phx_reply", payload: { status: "ok", response: {} }, ref: m.ref }); }
        else if (m.event === "phx_leave") client.topics.delete(m.topic);
        else if (m.event === "heartbeat") send({ topic: "phoenix", event: "phx_reply", payload: { status: "ok", response: {} }, ref: m.ref });
        else if (m.event === "broadcast" && client.topics.has(m.topic)) {
          realtime.broadcasts++;
          for (const other of realtime.sockets) if (other !== client && other.topics.has(m.topic) && ++realtime.delivered) other.send({ topic: m.topic, event: "broadcast", payload: m.payload, ref: null });
        }
      }
    });
    client.send = send;
    socket.on("close", () => realtime.sockets.delete(client));
    socket.on("error", () => realtime.sockets.delete(client));
  });
  return new Promise((resolve) => server.listen(port, () => resolve({ server, tables, realtime, objects })));
}
