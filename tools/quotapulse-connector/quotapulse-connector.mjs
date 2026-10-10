#!/usr/bin/env node
/**
 * QuotaPulse desktop connector — zero dependencies, Node 18+.
 *
 * Reads usage numbers that only exist on this computer and relays them, end-to-end
 * encrypted, to the QuotaPulse widget on your phone through a secret GitHub gist:
 *
 *   Codex CLI    → plan rate-limit windows (% left, reset time) from ~/.codex/sessions
 *   Claude Code  → today's session token totals and "limit reached" events from ~/.claude/projects
 *
 * Only aggregate numbers leave this machine — never prompts, file paths, or project names.
 * The gist holds AES-256-GCM ciphertext; only the phone that has the pairing code can read it.
 *
 * Usage:
 *   node quotapulse-connector.mjs pair            create the relay and print the pairing code
 *   node quotapulse-connector.mjs push            collect + upload once
 *   node quotapulse-connector.mjs watch [--interval 120]   push repeatedly (Ctrl+C to stop)
 *   node quotapulse-connector.mjs print           show exactly what would be sent (plaintext)
 *   node quotapulse-connector.mjs code            print the pairing code again
 *   node quotapulse-connector.mjs unpair          delete the gist and local config
 *
 * GitHub token (needs the "gist" scope) is read from QUOTAPULSE_GITHUB_TOKEN, GITHUB_TOKEN,
 * GH_TOKEN, or `gh auth token`. It is never written to disk or printed.
 */
import { createCipheriv, randomBytes } from 'node:crypto';
import { createReadStream, existsSync, mkdirSync, readFileSync, readdirSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { homedir, hostname } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

const HOME = homedir();
const CONFIG_DIR = join(HOME, '.quotapulse');
const CONFIG_FILE = join(CONFIG_DIR, 'connector.json');
const FILE_NAME = 'quotapulse.enc';
const AAD = Buffer.from('quotapulse-v1');
const DAY = 86_400_000;

// ─── Crypto ────────────────────────────────────────────────────

export function encrypt(plaintext, key, iv = randomBytes(12)) {
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(AAD);
  const ciphertext = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  return 'QP1:' + Buffer.concat([iv, ciphertext, cipher.getAuthTag()]).toString('base64url');
}

// ─── Local file helpers ────────────────────────────────────────

function walk(dir, predicate, out = [], depth = 0) {
  if (depth > 6 || !existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path, predicate, out, depth + 1);
    else if (predicate(entry.name)) {
      try { out.push({ path, mtime: statSync(path).mtimeMs }); } catch { /* vanished */ }
    }
  }
  return out;
}

async function* lines(path) {
  const rl = createInterface({ input: createReadStream(path, { encoding: 'utf8' }), crlfDelay: Infinity });
  for await (const line of rl) yield line;
}

const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n));

const fmt = (n) => n >= 1e9 ? (n / 1e9).toFixed(1) + 'B'
  : n >= 1e6 ? (n / 1e6).toFixed(1) + 'M'
  : n >= 1e3 ? (n / 1e3).toFixed(1) + 'K'
  : String(Math.round(n));

function windowTitle(minutes) {
  if (minutes === 300) return '5-HOUR';
  if (minutes === 1440) return 'DAILY';
  if (minutes === 10080) return 'WEEKLY';
  if (minutes === 43200) return '30-DAY';
  if (typeof minutes === 'number' && minutes > 0) return `${Math.round(minutes / 60)}-HOUR`;
  return 'WINDOW';
}

function limitTitle(type) {
  switch (type) {
    case 'five_hour': return '5-HOUR';
    case 'seven_day': return 'WEEKLY';
    case 'seven_day_opus': return 'WEEKLY OPUS';
    case 'seven_day_sonnet': return 'WEEKLY SONNET';
    default: return 'PLAN LIMIT';
  }
}

// ─── Collectors ────────────────────────────────────────────────

/** Latest plan rate-limit snapshot the Codex CLI recorded (from server headers). */
export async function collectCodex(now = Date.now(), home = HOME) {
  const dir = join(home, '.codex', 'sessions');
  if (!existsSync(dir)) {
    return { provider: 'CODEX', status: 'UNAVAILABLE', message: 'No Codex CLI sessions on this computer', metrics: [] };
  }
  const files = walk(dir, (n) => n.endsWith('.jsonl')).sort((a, b) => b.mtime - a.mtime).slice(0, 15);

  let latest = null;
  for (const file of files) {
    for await (const line of lines(file.path)) {
      if (!line.includes('"rate_limits"')) continue;
      try {
        const o = JSON.parse(line);
        const rl = o?.payload?.rate_limits;
        if (!rl) continue;
        const ts = Date.parse(o.timestamp) || file.mtime;
        if (!latest || ts > latest.ts) latest = { ts, rl };
      } catch { /* partial line */ }
    }
    if (latest) break; // newest file with a snapshot wins
  }

  if (!latest) {
    return { provider: 'CODEX', status: 'UNAVAILABLE', message: 'Codex has not recorded plan limits yet — run a Codex task', metrics: [] };
  }

  const { ts, rl } = latest;
  const plan = typeof rl.plan_type === 'string' ? `plan: ${rl.plan_type.slice(0, 20)}` : null;
  const metrics = [];
  for (const [slot, w] of [['primary', rl.primary], ['secondary', rl.secondary]]) {
    if (!w || typeof w.used_percent !== 'number') continue;
    const resetsAt = typeof w.resets_at === 'number' ? w.resets_at * 1000
      : typeof w.resets_in_seconds === 'number' ? ts + w.resets_in_seconds * 1000
      : null;
    const expired = resetsAt !== null && resetsAt <= now;
    metrics.push({
      key: `codex.${slot}`,
      kind: 'SUBSCRIPTION_QUOTA',
      title: windowTitle(w.window_minutes),
      // After the window resets the old number is no longer true; don't guess a new one.
      percentLeft: expired ? null : clamp(100 - w.used_percent, 0, 100),
      resetsAt,
      detail: [plan, expired ? 'window reset since last Codex use' : null].filter(Boolean).join(' · ') || null,
      measuredAt: ts,
    });
  }
  return { provider: 'CODEX', status: 'OK', message: null, metrics };
}

/** Today's Claude Code token totals and any currently-active plan limit. */
export async function collectClaude(now = Date.now(), home = HOME) {
  const dir = join(home, '.claude', 'projects');
  if (!existsSync(dir)) {
    return { provider: 'CLAUDE', status: 'UNAVAILABLE', message: 'No Claude Code data on this computer', metrics: [] };
  }
  const startOfToday = new Date(now);
  startOfToday.setHours(0, 0, 0, 0);
  const todayMs = startOfToday.getTime();

  const files = walk(dir, (n) => n.endsWith('.jsonl')).filter((f) => f.mtime >= now - 8 * DAY);
  const seen = new Set();
  const totals = { input: 0, output: 0, cacheCreate: 0, cacheRead: 0 };
  let limit = null;

  for (const file of files) {
    const touchedToday = file.mtime >= todayMs;
    for await (const line of lines(file.path)) {
      const hasUsage = touchedToday && line.includes('"usage"');
      const hasLimit = line.includes('"quotaLimits"');
      if (!hasUsage && !hasLimit) continue;
      let o;
      try { o = JSON.parse(line); } catch { continue; }
      const ts = Date.parse(o.timestamp);

      if (hasUsage && o.type === 'assistant' && o.message?.usage && ts >= todayMs) {
        // Claude Code writes one line per content block with the same usage; count each response once.
        const key = `${o.message.id}:${o.requestId}`;
        if (!seen.has(key)) {
          seen.add(key);
          const u = o.message.usage;
          totals.input += u.input_tokens || 0;
          totals.output += u.output_tokens || 0;
          totals.cacheCreate += u.cache_creation_input_tokens || 0;
          totals.cacheRead += u.cache_read_input_tokens || 0;
        }
      }
      if (hasLimit && o.quotaLimits && (!limit || ts > limit.ts)) limit = { ts, q: o.quotaLimits };
    }
  }

  const total = totals.input + totals.output + totals.cacheCreate + totals.cacheRead;
  const metrics = [{
    key: 'claude.session.today',
    kind: 'SESSION_TOKENS',
    title: 'TODAY',
    amount: total,
    unit: 'tokens',
    detail: `out ${fmt(totals.output)} · in ${fmt(totals.input + totals.cacheCreate)} · cache ${fmt(totals.cacheRead)}`,
    measuredAt: now,
  }];

  const resetsAt = typeof limit?.q?.resetsAt === 'number' ? limit.q.resetsAt * 1000 : null;
  if (limit && limit.q.status === 'rejected' && resetsAt && resetsAt > now) {
    metrics.push({
      key: 'claude.limit',
      kind: 'LIMIT_STATUS',
      title: limitTitle(limit.q.rateLimitType),
      resetsAt,
      detail: 'plan limit reached',
      measuredAt: limit.ts,
    });
  }
  return { provider: 'CLAUDE', status: 'OK', message: null, metrics };
}

export async function collect(label) {
  const now = Date.now();
  const [claude, codex] = await Promise.all([collectClaude(now), collectCodex(now)]);
  return { v: 1, host: (label || hostname()).slice(0, 40), collectedAt: now, providers: [claude, codex] };
}

// ─── GitHub relay ──────────────────────────────────────────────

function githubToken() {
  const env = process.env.QUOTAPULSE_GITHUB_TOKEN || process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  if (env) return env.trim();
  try {
    return execFileSync('gh', ['auth', 'token'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    throw new Error('No GitHub token. Set GITHUB_TOKEN (scope: gist) or run `gh auth login`.');
  }
}

async function github(method, path, body) {
  const res = await fetch(`https://api.github.com${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${githubToken()}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'QuotaPulse-Connector/1.0',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`GitHub ${method} failed: HTTP ${res.status}`);
  return res.status === 204 ? null : res.json();
}

function loadConfig() {
  if (!existsSync(CONFIG_FILE)) throw new Error('Not paired yet. Run: node quotapulse-connector.mjs pair');
  return JSON.parse(readFileSync(CONFIG_FILE, 'utf8'));
}

function saveConfig(config) {
  mkdirSync(CONFIG_DIR, { recursive: true, mode: 0o700 });
  writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2), { mode: 0o600 });
}

const pairingCode = (c) => `QP1.${c.gistId}.${c.key}`;

async function push(config, label) {
  const payload = await collect(label || config.label);
  const key = Buffer.from(config.key, 'base64url');
  await github('PATCH', `/gists/${config.gistId}`, { files: { [FILE_NAME]: { content: encrypt(JSON.stringify(payload), key) } } });
  return payload;
}

function summary(payload) {
  return payload.providers.map((p) => {
    if (p.status !== 'OK') return `${p.provider}: ${p.message}`;
    return `${p.provider}: ` + (p.metrics.map((m) => m.percentLeft != null ? `${m.title} ${Math.round(m.percentLeft)}% left`
      : m.kind === 'LIMIT_STATUS' ? `${m.title} limit reached`
      : m.amount != null ? `${fmt(m.amount)} ${m.unit}` : m.title).join(', ') || 'no data');
  }).join(' | ');
}

// ─── CLI ───────────────────────────────────────────────────────

async function main(argv) {
  const [command = 'help', ...rest] = argv;
  const flag = (name) => { const i = rest.indexOf(name); return i >= 0 ? rest[i + 1] : undefined; };
  const label = flag('--label');

  switch (command) {
    case 'pair': {
      if (existsSync(CONFIG_FILE) && !rest.includes('--new')) {
        const c = loadConfig();
        console.log('Already paired. Pairing code (treat it like a password):\n\n  ' + pairingCode(c) + '\n');
        console.log('Use `pair --new` to create a fresh relay and key.');
        return;
      }
      const key = randomBytes(32);
      const payload = await collect(label);
      const gist = await github('POST', '/gists', {
        description: 'QuotaPulse encrypted usage relay (safe to delete)',
        public: false,
        files: { [FILE_NAME]: { content: encrypt(JSON.stringify(payload), key) } },
      });
      const config = { gistId: gist.id, key: key.toString('base64url'), label: label || null };
      saveConfig(config);
      console.log('Paired. In the app: QuotaPulse → Desktop connector → CONNECT, then paste:\n\n  ' + pairingCode(config) + '\n');
      console.log('Treat this code like a password. First upload: ' + summary(payload));
      return;
    }
    case 'push': {
      const payload = await push(loadConfig(), label);
      console.log(new Date().toLocaleTimeString() + '  pushed  ' + summary(payload));
      return;
    }
    case 'watch': {
      const interval = Math.max(60, Number(flag('--interval')) || 120);
      const config = loadConfig();
      console.log(`Pushing every ${interval}s. Ctrl+C to stop.`);
      let delay = interval;
      for (;;) {
        try {
          const payload = await push(config, label);
          console.log(new Date().toLocaleTimeString() + '  pushed  ' + summary(payload));
          delay = interval;
        } catch (e) {
          delay = Math.min(delay * 2, 1800);
          console.error(new Date().toLocaleTimeString() + `  ${e.message} — retrying in ${delay}s`);
        }
        await new Promise((r) => setTimeout(r, delay * 1000));
      }
    }
    case 'print': {
      console.log(JSON.stringify(await collect(label), null, 2));
      return;
    }
    case 'code': {
      console.log(pairingCode(loadConfig()));
      return;
    }
    case 'unpair': {
      const c = loadConfig();
      try { await github('DELETE', `/gists/${c.gistId}`); } catch (e) { console.error('Could not delete gist: ' + e.message); }
      unlinkSync(CONFIG_FILE);
      console.log('Unpaired. Remove the connector in the app too.');
      return;
    }
    default:
      console.log(readFileSync(new URL(import.meta.url), 'utf8').split('\n').slice(2, 26).map((l) => l.replace(/^ \* ?/, '')).join('\n'));
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((e) => { console.error(e.message); process.exit(1); });
}
