// Live price relay for the investment hub.
// The Java backend on the owner's PC POSTs signed batches of quotes to /push;
// visitors' browsers connect to /live (WebSocket) or read /snapshot (JSON).
// One Durable Object holds the latest prices and fans them out (hibernation API: idle sockets cost nothing).

const ORIGINS = [
  'https://investment-hub.ceuvdo.workers.dev',
  'http://localhost:8765', 'http://127.0.0.1:8765',
  'http://localhost:8080', 'http://127.0.0.1:8080',
];
const MAX_BODY = 256 * 1024;
const MAX_SKEW_MS = 120_000;

function cors(origin) {
  return ORIGINS.includes(origin)
    ? { 'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Methods': 'GET, OPTIONS', Vary: 'Origin' }
    : {};
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const origin = req.headers.get('Origin') || '';
    if (req.method === 'OPTIONS') return new Response(null, { headers: cors(origin) });
    if (url.pathname === '/health') return new Response('ok');
    if (!['/push', '/live', '/snapshot'].includes(url.pathname)) return new Response('not found', { status: 404 });
    // browsers always send Origin on websocket upgrades: only our site may use the feed (and our quota)
    if (url.pathname === '/live' && !ORIGINS.includes(origin)) return new Response('forbidden origin', { status: 403 });
    const hub = env.HUB.get(env.HUB.idFromName('main'));
    return hub.fetch(req);
  },
};

async function verify(secret, body, sigHex) {
  if (!secret || !sigHex || !/^[0-9a-f]{64}$/.test(sigHex)) return false;
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
  const sig = new Uint8Array(sigHex.match(/../g).map((h) => parseInt(h, 16)));
  return crypto.subtle.verify('HMAC', key, sig, new TextEncoder().encode(body)); // constant-time compare
}

function cleanQuotes(q) {
  const out = {};
  if (!q || typeof q !== 'object') return out;
  for (const [id, v] of Object.entries(q)) {
    if (!/^[a-z0-9_]{1,24}$/.test(id) || !Array.isArray(v)) continue;
    const [p, c, t] = v;
    if (typeof p !== 'number' || !(p > 0) || !Number.isFinite(p)) continue;
    out[id] = [p, typeof c === 'number' && Number.isFinite(c) ? c : null, typeof t === 'number' ? t : null];
  }
  return out;
}

export class LiveHub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.latest = null; // { t, rt, q: {id: [price, chg24h, ts]}, timing }
    this.savedAt = 0;
    // answer keep-alive pings without waking the object
    this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  async load() {
    if (!this.latest) this.latest = (await this.state.storage.get('latest')) || { t: 0, rt: 0, q: {}, timing: {} };
    return this.latest;
  }

  async fetch(req) {
    const url = new URL(req.url);
    const latest = await this.load();
    const origin = req.headers.get('Origin') || '';

    if (url.pathname === '/push') {
      if (req.method !== 'POST') return new Response('POST only', { status: 405 });
      if (Number(req.headers.get('Content-Length') || 0) > MAX_BODY) return new Response('too large', { status: 413 });
      const body = await req.text();
      if (body.length > MAX_BODY) return new Response('too large', { status: 413 });
      if (!(await verify(this.env.RELAY_SECRET, body, req.headers.get('X-Signature')))) return new Response('bad signature', { status: 401 });
      let m;
      try { m = JSON.parse(body); } catch (e) { return new Response('bad json', { status: 400 }); }
      const now = Date.now();
      // replay protection: a captured body cannot be resent later or out of order
      if (typeof m.t !== 'number' || Math.abs(m.t - now) > MAX_SKEW_MS || m.t <= latest.t) return new Response('stale', { status: 409 });
      const q = cleanQuotes(m.q);
      if (m.snapshot) latest.q = {};
      Object.assign(latest.q, q);
      latest.t = m.t;
      latest.rt = now; // relay clock: visitors judge freshness by this, not by the PC clock
      if (m.timing && typeof m.timing === 'object') latest.timing = m.timing;
      const out = JSON.stringify({ t: m.t, rt: now, q, timing: latest.timing, snapshot: !!m.snapshot });
      const sockets = this.state.getWebSockets();
      for (const ws of sockets) {
        try { ws.send(out); } catch (e) { /* closed */ }
      }
      if (now - this.savedAt > 30000) { // survive eviction without writing every push
        this.savedAt = now;
        await this.state.storage.put('latest', latest);
      }
      return new Response(JSON.stringify({ ok: true, sockets: sockets.length }), { headers: { 'Content-Type': 'application/json' } });
    }

    if (url.pathname === '/snapshot') {
      const now = Date.now();
      return new Response(JSON.stringify({ ...latest, snapshot: true, now, age_s: Math.round((now - (latest.rt || 0)) / 1000) }), {
        headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=3', ...cors(origin) },
      });
    }

    // /live
    if (req.headers.get('Upgrade') !== 'websocket') return new Response('expected websocket', { status: 426 });
    if (this.state.getWebSockets().length >= 5000) return new Response('busy', { status: 503 });
    const pair = new WebSocketPair();
    this.state.acceptWebSocket(pair[1]);
    pair[1].send(JSON.stringify({ ...latest, now: Date.now(), snapshot: true }));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage() { /* visitors never send data; pings are auto-answered */ }

  async webSocketClose(ws, code) {
    try { ws.close(code, 'bye'); } catch (e) { /* already closed */ }
  }
}
