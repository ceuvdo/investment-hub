/* Shared helpers: data access, formatting, header/footer, search, charts, watchlist, theme. */
(function () {
  var SITE = { name: 'wyndton', tagline: 'open investment data' }; // site name: change here
  var C = window.CATALOG;
  var store = {
    get: function (k, d) { try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { } }
  };
  // theme first, to avoid a flash
  var theme = store.get('theme', 'navy');
  if (theme !== 'navy') document.documentElement.setAttribute('data-theme', theme);

  var byId = {}, waysBySlug = {}, waysByName = {}, groupsBySlug = {};
  C.assets.forEach(function (a) { byId[a.id] = a; });
  C.ways.forEach(function (w) { waysBySlug[w.slug] = w; waysByName[w.name] = w; });
  C.groups.forEach(function (g) { groupsBySlug[g.slug] = g; });

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function num(v) { return typeof v === 'number' && isFinite(v); }
  function pct(v, d) {
    if (!num(v)) return '<span class="nodata"></span>';
    var s = Math.abs(v).toFixed(d == null ? 1 : d);
    return (v > 0 ? '+' : v < 0 ? '−' : '') + s + '%';
  }
  function cls(v) { return !num(v) ? '' : v < 0 ? 'down' : 'up'; }
  function pctc(v, d) { return '<span class="' + cls(v) + '">' + pct(v, d) + '</span>'; }
  function arrow(v) { return !num(v) ? '' : v < 0 ? '▼ ' : '▲ '; }
  var CUR = { USD: '$', EUR: '€', GBP: '£', GBp: '', JPY: '¥' };
  function money(v, cur) {
    if (!num(v)) return '<span class="nodata"></span>';
    if (cur === 'index') return v.toLocaleString('en-US', { maximumFractionDigits: 1 }) + ' pts';
    var d = v >= 1000 ? 0 : v >= 10 ? 2 : v >= 1 ? 3 : 4;
    var s = Math.abs(v) < 0.01 ? Number(v.toPrecision(4)).toString()
      : v.toLocaleString('en-US', { minimumFractionDigits: Math.min(d, 2), maximumFractionDigits: d });
    if (cur === 'GBp') return s + 'p';
    return (CUR[cur] != null ? CUR[cur] : '') + s + (CUR[cur] == null && cur ? ' ' + cur : '');
  }
  function big(v) {
    if (!num(v)) return '<span class="nodata"></span>';
    var u = [[1e12, 'T'], [1e9, 'B'], [1e6, 'M'], [1e3, 'k']];
    for (var i = 0; i < u.length; i++) if (Math.abs(v) >= u[i][0]) return '$' + (v / u[i][0]).toFixed(v / u[i][0] >= 100 ? 0 : 1) + u[i][1];
    return '$' + Math.round(v);
  }
  function corr(v) { return num(v) ? v.toFixed(2) : '<span class="nodata"></span>'; }
  function liq(n) { var s = '<span class="liq" role="img" aria-label="liquidity ' + n + ' of 5">'; for (var i = 1; i <= 5; i++) s += '<i class="' + (i <= n ? 'on' : '') + '"></i>'; return s + '</span>'; }
  var TRUST = { market: 'market price', index: 'index', report: 'report', platform: 'platform figure', estimate: 'estimate' };
  function safeUrl(u) { return typeof u === 'string' && /^https?:\/\//i.test(u); }
  function trust(t) { return t ? '<span class="trust ' + esc(t) + '">' + esc(TRUST[t] || t) + '</span>' : ''; }
  function src(m) {
    if (!m || !m.source) return '';
    var t = esc(m.source) + (m.asof ? ' · ' + esc(m.asof) : '');
    var tip = esc([m.period, m.note].filter(Boolean).join('. '));
    return safeUrl(m.url) ? '<a class="src" href="' + esc(m.url) + '" target="_blank" rel="noopener" title="' + tip + '">' + t + '</a>' : '<span class="src" title="' + tip + '">' + t + '</span>';
  }
  var COLORS = ['#7d9cc9', '#d6b66f', '#a99bc4', '#86b79a', '#c69aae', '#77b3a8', '#d1a07e'];
  function color(id) { var h = 0; for (var i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0; return COLORS[h % COLORS.length]; }
  // coloured icon per kind of asset (as on the design canvas)
  var ICONS = {
    chart: 'M3 17l6-6 4 4 8-8M15 7h6v6', coin: 'M12 3a9 9 0 1 0 .01 0zM10 8v8M10 8h3a2 2 0 0 1 0 4h-3M10 12h3.5a2 2 0 0 1 0 4H10',
    shield: 'M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z', bulb: 'M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9V16h7v-2.1A6 6 0 0 0 12 3z',
    house: 'M3 11l9-7 9 7M5 10v10h14V10M10 20v-6h4v6', leaf: 'M12 21V11M12 11c0-4-3-6-7-6 0 4 3 6 7 6zM12 13c0-4 3-6 7-6 0 4-3 6-7 6z',
    bar: 'M4 15h16l-2 4H6zM7 15l2-6h6l2 6', bond: 'M6 3h12v18H6zM9 8h6M9 12h6M9 16h4', cash: 'M3 7h18v10H3zM12 9.5a2.5 2.5 0 1 0 .01 0zM6 10v4M18 10v4',
    ball: 'M12 3a9 9 0 1 0 .01 0zM12 7l3.5 2.5-1.3 4h-4.4l-1.3-4zM12 3v4M20.5 9.5l-5 0M3.5 9.5h5M7 19.5l2.8-6M17 19.5l-2.8-6',
    bolt: 'M13 3L5 14h6l-1 7 8-11h-6z', fx: 'M4 8h13l-3-3M20 16H7l3 3', card: 'M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM9 8h6M9 12h6'
  };
  var ICON_COLORS = { chart: '#4f8dff', coin: '#f0b90b', shield: '#2fc28a', bulb: '#ff6fa8', house: '#22c3b5', leaf: '#6bcf5a', bar: '#f2c230', bond: '#8fb3ff', cash: '#9be37a', ball: '#3c8cff', bolt: '#7cd4ff', fx: '#b49cff', card: '#3c8cff' };
  function iconKind(a) {
    var w = a.way || '', g = a.group;
    if (/club/i.test(w)) return 'ball';
    if (/bond/i.test(w)) return 'bond';
    if (/currenc/i.test(w)) return 'fx';
    if (/gold|bullion/i.test(w)) return 'bar';
    if (/money market|deposit/i.test(w)) return 'cash';
    if (/hedge|quant|credit|loans/i.test(w)) return 'shield';
    if (/farm|forest|grain|carbon/i.test(w)) return 'leaf';
    if (g === 'crypto' || /virtual land/i.test(w)) return 'coin';
    if (g === 'property') return 'house';
    if (g === 'infra') return 'bolt';
    if (g === 'private') return 'bulb';
    if (g === 'commodities') return 'bar';
    if (g === 'cards' || g === 'collectibles') return 'card';
    return 'chart';
  }
  function ini(a) {
    var k = iconKind(a);
    var own = window.ICONS && ((a.waySlug && window.ICONS.ways[a.waySlug]) || (a.groupSlug && window.ICONS.groups[a.groupSlug]));
    if (own) return '<span class="ini ini-own" style="background:' + ICON_COLORS[k] + '" aria-hidden="true">' + own + '</span>';
    return '<span class="ini" style="background:' + ICON_COLORS[k] + '" aria-hidden="true"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#0b0e11" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"><path d="' + ICONS[k] + '"/></svg></span>';
  }
  var ENTRY = { 1: 'from $100', 2: 'from $1k', 3: 'from $25k', 4: 'from $250k' };
  function wayMetric(w, k) { var m = w.metrics[k]; return m && num(m.value) ? m.value : null; }
  function wayHref(w) { return 'way.html?w=' + w.slug; }
  function assetHref(a) { return 'asset.html?id=' + a.id; }
  function q(name) { return new URLSearchParams(location.search).get(name); }
  function fmtDate(s) { var d = new Date(s); return isNaN(d) ? s : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }); }

  // ---------- header / footer ----------
  var ICON = {
    search: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>',
    map: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M4 5h7v7H4zM13 5h7v4h-7zM13 12h7v7h-7zM4 15h7v4H4z"/></svg>',
    rank: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 17l6-6 4 4 8-8M15 7h6v6"/></svg>',
    cmp: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="M7 4v16M17 4v16M3 8h8M13 16h8"/></svg>',
    star: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M12 4l2.5 5 5.5.8-4 3.9.9 5.5L12 16.6 7.1 19.2l.9-5.5-4-3.9 5.5-.8L12 4z"/></svg>',
    home: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M4 11l8-6 8 6v8h-5v-5h-6v5H4z"/></svg>'
  };
  function header(active) {
    var m = C.meta;
    var built = m.built;
    var nav = [['map.html', 'All ways to invest', 'map'], ['rankings.html', 'Rankings', 'rank'], ['compare.html', 'Compare', 'cmp'], ['news.html', 'News', 'news'], ['methodology.html', 'Methodology', 'meth']];
    var h = '<div class="strip"><div class="wrap">' +
      '<span>Ways to invest <b>' + m.n_ways + '</b></span>' +
      '<span>Assets tracked <b>' + m.n_assets + '</b></span>' +
      '<span>Countries <b>' + m.n_countries + '</b></span>' +
      '<span>Data sources <b>' + m.n_sources + '</b></span>' +
      '<span class="live" id="live-badge" title="Daily data: prices at close, indexes and reports">● daily data · built ' + esc(built) + '</span></div></div>' +
      '<header class="wrap hdr">' +
      '<a class="logo" href="index.html"><span class="logo-mark" aria-hidden="true"><i style="height:7px"></i><i style="height:12px"></i><i style="height:18px"></i></span>' + SITE.name + '</a>' +
      '<nav class="nav" id="nav">' + nav.map(function (n) { return '<a href="' + n[0] + '" class="' + (active === n[2] ? 'on' : '') + '">' + n[1] + '</a>'; }).join('') + '</nav>' +
      '<a class="btn wl" href="watchlist.html">' + 'Watchlist <span class="mono acc" id="wl-n"></span></a>' +
      '<button class="btn menu-btn" aria-label="Menu" aria-expanded="false" aria-controls="nav" onclick="var n=document.getElementById(\'nav\');n.classList.toggle(\'open\');this.setAttribute(\'aria-expanded\',n.classList.contains(\'open\'))"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16"/></svg></button>' +
      '</header>';
    document.getElementById('top').innerHTML = h;
    var tabs = [['index.html', 'Home', 'home', 'home'], ['map.html', 'Map', 'map', 'map'], ['rankings.html', 'Rankings', 'rank', 'rank'], ['compare.html', 'Compare', 'cmp', 'cmp'], ['watchlist.html', 'Watchlist', 'star', 'wl']];
    var tb = document.createElement('nav'); tb.className = 'tabbar'; tb.setAttribute('aria-label', 'Sections');
    tb.innerHTML = tabs.map(function (t) { return '<a href="' + t[0] + '" class="' + (active === t[3] ? 'on' : '') + '">' + ICON[t[2]] + t[1] + '</a>'; }).join('');
    document.body.appendChild(tb);
    updateWl();
    startLive();
  }

  // ---------- live prices ----------
  // Local backend (site served by it on :8080) streams at /live; everyone else gets the Cloudflare relay.
  var RELAY = 'wss://investment-hub-live.ceuvdo.workers.dev/live';
  var live = { q: {}, timing: {}, t: 0, rt: 0, skew: 0, ws: null, wait: 1000, ok: false };
  // age of the newest price, measured on the relay's clock (rt), corrected for this browser's clock (skew)
  function liveAge() { return live.rt ? Date.now() + live.skew - live.rt : Date.now() - live.t; }
  function liveUrl() {
    if (location.port === '8080') return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/live';
    return RELAY;
  }
  function startLive() {
    if (!('WebSocket' in window)) return;
    var ws;
    try { ws = new WebSocket(liveUrl()); } catch (e) { return; }
    live.ws = ws;
    ws.onopen = function () { live.wait = 1000; };
    ws.onmessage = function (e) {
      if (e.data === 'pong') return;
      var m; try { m = JSON.parse(e.data); } catch (x) { return; }
      if (!m || !m.q) return;
      if (typeof m.now === 'number') live.skew = m.now - Date.now();
      live.t = m.t || Date.now(); live.rt = m.rt || 0; live.timing = m.timing || live.timing;
      var ids = Object.keys(m.q);
      ids.forEach(function (id) { live.q[id] = m.q[id]; });
      live.ok = Object.keys(live.q).length > 0 && liveAge() < 120000;
      // an old snapshot (backend PC off) must not replace the daily close with stale "live" prices
      if (live.ok) paint(m.snapshot ? Object.keys(live.q) : ids, !m.snapshot);
      badge();
      if (window.onLive) window.onLive(live, ids);
    };
    ws.onclose = function () {
      live.ok = false; badge();
      setTimeout(startLive, live.wait); live.wait = Math.min(60000, live.wait * 2);
    };
    ws.onerror = function () { try { ws.close(); } catch (x) { } };
  }
  setInterval(function () { if (live.ws && live.ws.readyState === 1) try { live.ws.send('ping'); } catch (e) { } badge(); }, 30000);
  function paint(ids, flash) {
    ids.forEach(function (id) {
      var x = live.q[id]; if (!x) return;
      var a = byId[id], cur = a ? a.currency : 'USD';
      document.querySelectorAll('[data-lp="' + id + '"]').forEach(function (el) {
        var old = parseFloat(el.dataset.v || 'NaN');
        el.innerHTML = money(x[0], cur);
        el.dataset.v = x[0];
        el.title = 'Live, ' + new Date(x[2]).toLocaleTimeString() + (a && a.live ? '' : '');
        if (flash && isFinite(old) && old !== x[0]) {
          el.classList.remove('tick-up', 'tick-down'); void el.offsetWidth;
          el.classList.add(x[0] > old ? 'tick-up' : 'tick-down');
        }
      });
      document.querySelectorAll('[data-lc="' + id + '"]').forEach(function (el) { el.innerHTML = num(x[1]) ? pctc(x[1], 2) : ''; });
    });
  }
  function badge() {
    var b = document.getElementById('live-badge'); if (!b) return;
    var n = Object.keys(live.q).length;
    live.ok = n > 0 && liveAge() < 120000;
    if (live.ok) {
      var r = live.timing['binance.rtt_ms'];
      b.innerHTML = '● live · ' + n + ' prices' + (r && r.median ? ' · Binance ' + (r.median / 1000).toFixed(2) + ' s' : '') + ' · ' + Math.max(0, Math.round(liveAge() / 1000)) + ' s ago';
      b.title = 'Crypto from Binance, currencies from Kraken, via our backend. Stocks and funds: daily close prices.';
    } else {
      b.textContent = '● daily data · built ' + C.meta.built;
    }
  }
  function footer() {
    var f = document.createElement('footer'); f.className = 'ft';
    f.innerHTML = '<div class="wrap"><span><span style="font-family:var(--ff-h);font-weight:700;color:var(--text)">' + SITE.name + '</span> · ' + SITE.tagline + '</span>' +
      '<nav><a href="methodology.html">Methodology</a><a href="methodology.html#sources">Sources</a><a href="methodology.html#photos">Photo credits</a><a href="data/catalog.json">Download data (JSON)</a></nav>' +
      '<span class="themes" role="group" aria-label="Palette">Palette ' +
      [['navy', '#0b0e11', '#f0b90b'], ['a', '#f4f0e6', '#1f5c4a'], ['b', '#eef1f5', '#1f3a5f'], ['c', '#f2efe9', '#7a560e']].map(function (t) {
        return '<button title="' + (t[0] === 'navy' ? 'Navy and brass' : 'Light ' + t[0].toUpperCase()) + '" aria-pressed="' + (theme === t[0]) + '" data-t="' + t[0] + '" style="background:linear-gradient(135deg,' + t[1] + ' 50%,' + t[2] + ' 50%)"></button>';
      }).join('') + '</span>' +
      '<span>Not investment advice. Past returns do not predict future ones.</span></div>';
    document.body.insertBefore(f, document.querySelector('.tabbar'));
    f.querySelectorAll('.themes button').forEach(function (b) {
      b.onclick = function () {
        theme = b.dataset.t; store.set('theme', theme);
        if (theme === 'navy') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', theme);
        f.querySelectorAll('.themes button').forEach(function (x) { x.setAttribute('aria-pressed', x === b); });
        if (window.onThemeChange) window.onThemeChange();
      };
    });
  }

  // ---------- watchlist ----------
  function wl() { return store.get('watchlist', []); }
  function inWl(id) { return wl().indexOf(id) >= 0; }
  function toggleWl(id) { var l = wl(), i = l.indexOf(id); if (i >= 0) l.splice(i, 1); else l.push(id); store.set('watchlist', l); updateWl(); return i < 0; }
  function updateWl() { var e = document.getElementById('wl-n'); if (e) e.textContent = wl().length || ''; }

  // ---------- search ----------
  function searchIndex() {
    var items = [];
    C.ways.forEach(function (w) { items.push({ t: w.name, s: groupsBySlug[w.group].name + ' · way to invest', h: wayHref(w), k: (w.name + ' ' + w.desc).toLowerCase() }); });
    C.groups.forEach(function (g) { items.push({ t: g.name, s: g.n + ' ways', h: 'class.html?g=' + g.slug, k: (g.name + ' ' + g.about).toLowerCase() }); });
    C.assets.forEach(function (a) { items.push({ t: a.name, s: a.symbol + ' · ' + a.way, h: assetHref(a), k: (a.name + ' ' + a.symbol + ' ' + a.sub + ' ' + a.way).toLowerCase() }); });
    (window.CATALOG.deposits || []).forEach(function (d) { items.push({ t: d.country + ': deposit rate', s: d.rate + '% · ' + d.rate_year, h: 'way.html?w=savings-and-deposits#c-' + d.iso, k: (d.country + ' deposit savings bank').toLowerCase() }); });
    return items;
  }
  function attachSearch(input, box) {
    var idx = searchIndex(), sel = -1, cur = [];
    function render() {
      var v = input.value.trim().toLowerCase();
      if (v.length < 1) { box.hidden = true; return; }
      var words = v.split(/\s+/);
      cur = idx.filter(function (i) { return words.every(function (w) { return i.k.indexOf(w) >= 0; }); })
        .sort(function (a, b) { return (b.t.toLowerCase().indexOf(v) === 0) - (a.t.toLowerCase().indexOf(v) === 0); }).slice(0, 12);
      box.innerHTML = cur.length ? cur.map(function (i, n) { return '<a href="' + i.h + '" class="' + (n === sel ? 'sel' : '') + '"><span>' + esc(i.t) + '</span><small>' + esc(i.s) + '</small></a>'; }).join('')
        : '<div class="empty">Nothing found. <a class="link" href="map.html">Browse all ways →</a></div>';
      box.hidden = false;
    }
    input.addEventListener('input', function () { sel = -1; render(); });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown') { sel = Math.min(sel + 1, cur.length - 1); render(); e.preventDefault(); }
      else if (e.key === 'ArrowUp') { sel = Math.max(sel - 1, 0); render(); e.preventDefault(); }
      else if (e.key === 'Enter') { var t = cur[sel >= 0 ? sel : 0]; if (t) location.href = t.h; }
      else if (e.key === 'Escape') { box.hidden = true; }
    });
    document.addEventListener('click', function (e) { if (!box.contains(e.target) && e.target !== input) box.hidden = true; });
  }

  // ---------- charts ----------
  // lines: [{id, label, color, dash, m:[YYYY-MM], v:[values]}], normalised to 1000 at the first common month
  function lineChart(el, lines, opts) {
    opts = opts || {};
    var W = 800, H = opts.h || 280, P = 8;
    var start = opts.from || null;
    lines = lines.filter(function (l) { return l && l.m && l.m.length; });
    if (!lines.length) { el.innerHTML = '<div class="empty">No price history for this item.</div>'; return; }
    var common = lines.map(function (l) { return l.m.filter(function (m) { return !start || m >= start; })[0]; }).sort().pop();
    var prepared = lines.map(function (l) {
      var i0 = l.m.indexOf(common); if (i0 < 0) i0 = 0;
      var base = l.v[i0];
      var pts = [];
      for (var i = i0; i < l.m.length; i++) pts.push([l.m[i], opts.raw ? l.v[i] : 1000 * l.v[i] / base]);
      return { l: l, pts: pts };
    });
    var months = prepared[0].pts.map(function (p) { return p[0]; });
    prepared.forEach(function (p) { p.pts.forEach(function (x) { if (months.indexOf(x[0]) < 0) months.push(x[0]); }); });
    months.sort();
    var all = []; prepared.forEach(function (p) { p.pts.forEach(function (x) { all.push(x[1]); }); });
    var lo = Math.min.apply(null, all), hi = Math.max.apply(null, all);
    if (hi === lo) { hi += 1; lo -= 1; }
    var pad = (hi - lo) * 0.08; lo -= pad; hi += pad;
    var X = function (m) { return P + (W - 2 * P) * (months.indexOf(m) / Math.max(1, months.length - 1)); };
    var Y = function (v) { return H - P - (H - 2 * P) * ((v - lo) / (hi - lo)); };
    var s = '<svg class="chart" viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none" role="img" aria-label="' + esc(opts.label || 'Price chart') + '">';
    for (var g = 1; g < 4; g++) s += '<line x1="0" x2="' + W + '" y1="' + (H * g / 4) + '" y2="' + (H * g / 4) + '" stroke="var(--line2)" stroke-width="1" vector-effect="non-scaling-stroke"/>';
    if (!opts.raw && lo < 1000 && hi > 1000) s += '<line x1="0" x2="' + W + '" y1="' + Y(1000) + '" y2="' + Y(1000) + '" stroke="var(--line3)" stroke-dasharray="3 5" stroke-width="1" vector-effect="non-scaling-stroke"/>';
    prepared.forEach(function (p, i) {
      var pts = p.pts.map(function (x) { return X(x[0]).toFixed(1) + ',' + Y(x[1]).toFixed(1); }).join(' ');
      if (i === 0 && opts.fill !== false) s += '<polygon points="' + X(p.pts[0][0]).toFixed(1) + ',' + H + ' ' + pts + ' ' + X(p.pts[p.pts.length - 1][0]).toFixed(1) + ',' + H + '" fill="' + (p.l.color || 'var(--acc)') + '" opacity="0.10"/>';
      s += '<polyline points="' + pts + '" fill="none" stroke="' + (p.l.color || 'var(--acc)') + '" stroke-width="2.5" stroke-linejoin="round" ' + (p.l.dash ? 'stroke-dasharray="' + p.l.dash + '"' : '') + ' vector-effect="non-scaling-stroke"/>';
    });
    s += '<line id="hair" x1="0" x2="0" y1="0" y2="' + H + '" stroke="var(--line3)" stroke-width="1" vector-effect="non-scaling-stroke" visibility="hidden"/></svg>';
    var years = {}; months.forEach(function (m) { years[m.slice(0, 4)] = 1; });
    var ys = Object.keys(years); var step = Math.ceil(ys.length / 8);
    var axis = '<div class="axis">' + ys.filter(function (y, i) { return i % step === 0; }).map(function (y) { return '<span>' + y + '</span>'; }).join('') + '</div>';
    el.innerHTML = '<div class="chart-box">' + s + '<div class="tip" hidden></div></div>' + axis;
    // hover readout
    var svg = el.querySelector('svg'), tip = el.querySelector('.tip'), hair = el.querySelector('#hair');
    function move(e) {
      var r = svg.getBoundingClientRect();
      var x = ((e.touches ? e.touches[0].clientX : e.clientX) - r.left) / r.width;
      var i = Math.round(x * (months.length - 1)); i = Math.max(0, Math.min(months.length - 1, i));
      var m = months[i];
      var rows = prepared.map(function (p) {
        var hit = p.pts.filter(function (x) { return x[0] === m; })[0];
        return hit ? '<div><span style="color:' + (p.l.color || 'var(--acc)') + '">■</span> ' + esc(p.l.short || p.l.label) + ': ' + (opts.raw ? hit[1].toLocaleString('en-US', { maximumFractionDigits: 1 }) : '$' + Math.round(hit[1]).toLocaleString('en-US')) + '</div>' : '';
      }).join('');
      tip.innerHTML = '<div class="muted">' + m + '</div>' + rows;
      tip.hidden = false; tip.style.left = (x * 100) + '%'; tip.style.top = '30px';
      hair.setAttribute('x1', X(m)); hair.setAttribute('x2', X(m)); hair.setAttribute('visibility', 'visible');
    }
    svg.addEventListener('mousemove', move); svg.addEventListener('touchmove', move, { passive: true });
    svg.addEventListener('mouseleave', function () { tip.hidden = true; hair.setAttribute('visibility', 'hidden'); });
    return prepared.map(function (p) { return { id: p.l.id, end: p.pts[p.pts.length - 1][1], from: p.pts[0][0] }; });
  }
  function fromMonth(range) {
    var d = new Date(C.meta.built.slice(0, 10));
    if (range === 'all') return null;
    d.setUTCFullYear(d.getUTCFullYear() - (range === '1y' ? 1 : range === '3y' ? 3 : range === '5y' ? 5 : 10));
    return d.toISOString().slice(0, 7);
  }

  // ---------- rankings table ----------
  var COLS = [
    ['name', 'Asset', 'l'], ['price', 'Price'], ['chg_30d', '30 days'], ['ret_1y', '1 year'], ['ret_5y', '5y / yr'], ['ret_10y', '10y / yr'],
    ['swing', 'Swing'], ['dd', 'Worst drop'], ['corr', 'Vs stocks'], ['vol30_usd', 'Volume 30d'], ['sell', 'Time to sell', 'l'], ['min', 'Min ticket']
  ];
  function rankTable(el, rows, state) {
    state = state || { sort: 'ret_1y', dir: -1 };
    function draw() {
      var k = state.sort, d = state.dir;
      var r = rows.slice().sort(function (a, b) {
        var x = a[k], y = b[k];
        if (k === 'sell') { x = a.sell_s; y = b.sell_s; }
        if (typeof x === 'string' || typeof y === 'string') return d * String(x || '').localeCompare(String(y || ''));
        if (!num(x)) return 1; if (!num(y)) return -1; return d * (x - y);
      });
      var h = '<div class="tbl-wrap desk-tbl"><table class="tbl"><thead><tr>' + COLS.map(function (c) {
        return '<th class="' + (c[2] || '') + '"><button ' + (state.sort === c[0] ? 'aria-sort="' + (d > 0 ? 'ascending' : 'descending') + '"' : '') + ' data-k="' + c[0] + '">' + c[1] + (state.sort === c[0] ? (d > 0 ? ' ↑' : ' ↓') : '') + '</button></th>';
      }).join('') + '</tr></thead><tbody>' + r.map(function (a) {
        return '<tr><td><div class="asset-cell">' + ini(a) + '<div style="min-width:0"><a href="' + assetHref(a) + '">' + esc(a.name) + '</a><small>' + esc(a.way) + ' · ' + esc(a.sub) + '</small></div></div></td>' +
          '<td data-lp="' + a.id + '">' + (live.q[a.id] ? money(live.q[a.id][0], a.currency) : money(a.price, a.currency)) + '</td><td>' + pctc(a.chg_30d) + '</td><td>' + pctc(a.ret_1y) + '</td><td>' + pctc(a.ret_5y) + '</td><td>' + pctc(a.ret_10y) + '</td>' +
          '<td>' + (num(a.swing) ? a.swing.toFixed(0) + '%' : pct(null)) + '</td><td class="down">' + pct(a.dd, 0) + '</td><td class="muted">' + corr(a.corr) + '</td>' +
          '<td>' + big(a.vol30_usd) + '</td><td class="sell" title="' + esc(sellTip(a)) + '">' + esc(a.sell || '') + '</td><td class="muted">' + esc(a.min) + '</td></tr>';
      }).join('') + '</tbody></table></div>';
      h += '<div class="mob-list">' + r.slice(0, state.mobileLimit || 999).map(function (a) {
        return '<a class="mob-row" href="' + assetHref(a) + '">' + ini(a) + '<span style="min-width:0"><span class="nm">' + esc(a.name) + '</span><small>' + esc(a.way) + ' · ' + esc(a.sell || '') + '</small></span>' +
          '<span class="r"><span data-lp="' + a.id + '">' + money(a.price, a.currency) + '</span><br><small>' + pctc(a.ret_1y) + ' 1y · ± ' + (num(a.swing) ? a.swing.toFixed(0) + '%' : '–') + '</small></span></a>';
      }).join('') + '</div>';
      el.innerHTML = h;
      el.querySelectorAll('th button').forEach(function (b) {
        b.onclick = function () { var k = b.dataset.k; if (state.sort === k) state.dir = -state.dir; else { state.sort = k; state.dir = (k === 'name' || k === 'sell' || k === 'min' || k === 'swing' || k === 'corr') ? 1 : -1; if (k === 'dd') state.dir = -1; } draw(); };
      });
    }
    draw();
  }

  // news helpers: related assets are matched by keywords in the headline (a link, not a forecast)
  var NEWS_URL = 'https://investment-hub-live.ceuvdo.workers.dev/news';
  var RELATED = [[/bitcoin|btc|crypto|spot etf/i, 'btc'], [/ether(eum)?|eth/i, 'eth'], [/solana/i, 'sol'], [/xrp|ripple/i, 'xrp'], [/gold/i, 'gld'], [/silver/i, 'slv'],
    [/oil|crude|opec|brent/i, 'uso'], [/natural gas|lng/i, 'ung'], [/nvidia/i, 'nvda'], [/apple|iphone/i, 'aapl'], [/tesla|musk/i, 'tsla'], [/microsoft/i, 'msft'],
    [/amazon/i, 'amzn'], [/alphabet|google/i, 'googl'], [/meta platforms|facebook/i, 'meta'], [/treasur|bond yield|yields/i, 'govt'], [/fed|rate cut|inflation|cpi|powell/i, 'bil'],
    [/s&p|stocks|wall street|dow|nasdaq/i, 'spy'], [/dollar|euro|fx/i, 'eurusd'], [/china|beijing/i, 'mchi'], [/india/i, 'inda'], [/japan|yen/i, 'ewj'], [/housing|home prices|mortgage/i, 'vnq'],
    [/wheat|grain|corn/i, 'weat'], [/carbon/i, 'krbn'], [/manchester united/i, 'manu'], [/solar|renewable|clean energy/i, 'icln']];
  function related(n, max) {
    var out = [], text = n.t + ' ' + (n.sum || '');
    RELATED.forEach(function (r) { if (out.length < (max || 3) && r[0].test(text) && byId[r[1]] && out.indexOf(r[1]) < 0) out.push(r[1]); });
    return out.map(function (id) { return byId[id]; });
  }
  function ago(ts) { var m = Math.max(1, Math.round((Date.now() - ts) / 60000)); return m < 60 ? m + ' min ago' : m < 1440 ? Math.round(m / 60) + ' h ago' : Math.round(m / 1440) + ' days ago'; }
  var CAT_IMG = { Crypto: 'crypto.jpg', Stocks: 'stocks.jpg', Markets: 'stocks.jpg', Economy: 'cash-v2.jpg' };
  function newsImg(n) {
    if (n.img && /^https:\/\//.test(n.img)) return n.img;
    var rel = related(n, 1)[0], g = rel && groupsBySlug[rel.group];
    return 'assets/img/' + ((g && g.img) || CAT_IMG[n.cat] || 'stocks.jpg');
  }
  function newsChips(n) {
    var rel = related(n, 3);
    return rel.length ? '<span class="news-rel"><span class="cap">May move</span>' + rel.map(function (a) {
      var c = a.chg_30d; return '<span class="chip">' + esc(a.name.split(' (')[0]) + ' <span class="' + cls(c) + '">' + (num(c) ? (c < 0 ? '▼' : '▲') + ' ' + Math.abs(c).toFixed(1) + '%' : '') + '</span></span>';
    }).join('') + '</span>' : '';
  }
  function newsCard(n) {
    return '<a class="card news-card" href="' + newsHref(n) + '"><img class="news-img" src="' + esc(newsImg(n)) + '" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.onerror=null;this.src=&quot;assets/img/stocks.jpg&quot;">' +
      '<span class="note">' + esc(n.cat) + ' · ' + esc(ago(n.ts)) + ' · ' + esc(n.src) + '</span><span class="news-t">' + esc(n.t) + '</span>' +
      (n.sum ? '<span class="note news-sum">' + esc(n.sum) + '</span>' : '') + newsChips(n) + '</a>';
  }
  function newsHref(n) { return 'news.html?u=' + encodeURIComponent(n.url); }
  function loadNews() { return fetch(NEWS_URL).then(function (r) { return r.json(); }).then(function (d) { return d.items || []; }); }

  function sellTip(a) {
    var d = a.sell_detail; if (!d) return '';
    return [d.exec && 'Order: ' + d.exec, d.hours && 'Hours: ' + d.hours, d.settle && 'Yours after: ' + d.settle].filter(Boolean).join('\n');
  }

  window.K = {
    live: live, paint: paint, sellTip: sellTip, safeUrl: safeUrl, related: related, ago: ago, newsHref: newsHref, loadNews: loadNews, newsCard: newsCard,
    C: C, byId: byId, waysBySlug: waysBySlug, waysByName: waysByName, groupsBySlug: groupsBySlug, SITE: SITE,
    esc: esc, num: num, pct: pct, pctc: pctc, cls: cls, arrow: arrow, money: money, big: big, corr: corr, liq: liq, trust: trust, src: src,
    color: color, ini: ini, ENTRY: ENTRY, wayMetric: wayMetric, wayHref: wayHref, assetHref: assetHref, q: q, fmtDate: fmtDate,
    header: header, footer: footer, attachSearch: attachSearch, lineChart: lineChart, fromMonth: fromMonth, rankTable: rankTable,
    wl: wl, inWl: inWl, toggleWl: toggleWl, store: store, ICON: ICON
  };
})();
