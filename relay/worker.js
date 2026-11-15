// Live price relay for the investment hub.
// The Java backend on the owner's PC POSTs signed batches of quotes to /push;
// visitors' browsers connect to /live (WebSocket) or read /snapshot (JSON).
// One Durable Object holds the latest prices and fans them out (hibernation API: idle sockets cost nothing).

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
};

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    if (!['/push', '/live', '/snapshot', '/health'].includes(url.pathname)) return new Response('not found', { status: 404 });
    if (url.pathname === '/health') return new Response('ok');
    const hub = env.HUB.get(env.HUB.idFromName('main'));
    return hub.fetch(req);
  },
};

async function verify(secret, body, sigHex) {
  if (!secret || !sigHex || !/^[0-9a-f]{64}$/.test(sigHex)) return false;
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
  const sig = new Uint8Array(sigHex.match(/../g).map((h) => parseInt(h, 16)));
  return crypto.subtle.verify('HMAC', key, sig, new TextEncoder().encode(body));
}

export class LiveHub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.latest = null; // { t, q: {id: [price, chg24h, ts]}, timing }
    this.savedAt = 0;
  }

  async load() {
    if (!this.latest) this.latest = (await this.state.storage.get('latest')) || { t: 0, q: {}, timing: {} };
    return this.latest;
  }

  async fetch(req) {
    const url = new URL(req.url);
    const latest = await this.load();

    if (url.pathname === '/push') {
      if (req.method !== 'POST') return new Response('POST only', { status: 405 });
      const body = await req.text();
      if (body.length > 512 * 1024) return new Response('too large', { status: 413 });
      if (!(await verify(this.env.RELAY_SECRET, body, req.headers.get('X-Signature')))) return new Response('bad signature', { status: 401 });
      const m = JSON.parse(body);
      if (m.snapshot) latest.q = {};
      Object.assign(latest.q, m.q || {});
      latest.t = m.t;
      latest.timing = m.timing || latest.timing;
      const out = JSON.stringify({ t: m.t, q: m.q, timing: m.timing, snapshot: !!m.snapshot });
      for (const ws of this.state.getWebSockets()) {
        try { ws.send(out); } catch (e) { /* closed */ }
      }
      if (Date.now() - this.savedAt > 30000) { // survive eviction without writing every push
        this.savedAt = Date.now();
        await this.state.storage.put('latest', latest);
      }
      return new Response('ok');
    }

    if (url.pathname === '/snapshot') {
      return new Response(JSON.stringify({ ...latest, snapshot: true, age_s: Math.round((Date.now() - latest.t) / 1000) }), {
        headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...CORS },
      });
    }

    // /live
    if (req.headers.get('Upgrade') !== 'websocket') return new Response('expected websocket', { status: 426 });
    if (this.state.getWebSockets().length >= 5000) return new Response('busy', { status: 503 });
    const pair = new WebSocketPair();
    this.state.acceptWebSocket(pair[1]);
    pair[1].send(JSON.stringify({ ...latest, snapshot: true }));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws, msg) {
    if (msg === 'ping') ws.send('pong'); // visitors never send data otherwise
  }

  async webSocketClose(ws, code) {
    try { ws.close(code, 'bye'); } catch (e) { /* already closed */ }
  }
}
