// A tiny PostgREST stand-in: eq/gt/gte filters, order, limit, upsert on_conflict, delete,
// with the same header scoping as the row policies in docs/CLOUD.md.
import http from "node:http";

const KEYS = { diligent_days: ["owner", "date", "activity"], diligent_clips: ["scope", "id"], diligent_presence: ["room", "member"] };

function allowed(table, row, h) {
  const owner = h["x-diligent-owner"] || "", room = h["x-diligent-room"] || "";
  if (table === "diligent_days") return row.owner === owner;
  if (table === "diligent_clips") return row.scope.length > 5 && (row.scope === owner || row.scope === "room:" + room);
  if (table === "diligent_presence") return row.room === room;
  return true;
}

export function startMockSupabase(port) {
  const tables = {};
  const server = http.createServer((req, res) => {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Headers", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET,POST,DELETE,PATCH,OPTIONS");
    if (req.method === "OPTIONS") return res.end();
    const url = new URL(req.url, "http://x");
    const table = url.pathname.replace("/rest/v1/", "");
    const rows = (tables[table] ||= []);
    const filters = [];
    for (const [k, v] of url.searchParams) {
      if (["select", "order", "limit", "on_conflict"].includes(k)) continue;
      const [op, ...rest] = v.split(".");
      const val = rest.join(".");
      filters.push((r) => (op === "eq" ? String(r[k]) === val : op === "gt" ? +r[k] > +val : op === "gte" ? String(r[k]) >= val : true));
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
  return new Promise((resolve) => server.listen(port, () => resolve({ server, tables })));
}
