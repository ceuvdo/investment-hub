"""Fetch real market data and build data/catalog.json + data/series.json.

Stdlib only. Run from anywhere:  python hub/pipeline/build_data.py
Raw responses are cached in pipeline/cache/ for 12 hours (use --fresh to refetch).
"""
import csv, io, json, math, os, statistics, sys, time, urllib.request
from datetime import datetime, timezone, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from universe import GROUPS, WAYS, ASSETS, HOUSES, DEFI  # noqa: E402

CACHE = os.path.join(HERE, "cache")
DATA = os.path.join(ROOT, "data")
FRESH = "--fresh" in sys.argv
UA = {"User-Agent": "Mozilla/5.0 (open investment catalog data builder)"}
NOW = datetime.now(timezone.utc)
TODAY = NOW.strftime("%Y-%m-%d")
os.makedirs(CACHE, exist_ok=True)


def dt(t, tz=None):
    return datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(seconds=t)


def slug(s):
    out = "".join(c.lower() if c.isalnum() else "-" for c in s)
    while "--" in out:
        out = out.replace("--", "-")
    return out.strip("-")


def fetch(url, key, ttl=12 * 3600, timeout=40, ua=None):
    path = os.path.join(CACHE, key)
    if not FRESH and os.path.exists(path) and time.time() - os.path.getmtime(path) < ttl:
        with open(path, "rb") as f:
            return f.read()
    last = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": ua} if ua else UA)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read()
            with open(path, "wb") as f:
                f.write(body)
            time.sleep(0.25)
            return body
        except Exception as e:  # network hiccup, rate limit
            last = e
            time.sleep(2 + attempt * 3)
    if os.path.exists(path):  # stale cache beats nothing
        print("  ! using stale cache for", key, last)
        with open(path, "rb") as f:
            return f.read()
    raise RuntimeError(f"fetch failed {url}: {last}")


# ---------- metric helpers ----------

def value_at(ts, vs, target):
    """Last value at or before target timestamp."""
    best = None
    for t, v in zip(ts, vs):
        if t <= target:
            best = v
        else:
            break
    return best


def pct(a, b):
    return None if a in (None, 0) or b is None else round((b / a - 1) * 100, 1)


def ann(a, b, years):
    if a in (None, 0) or b is None or a <= 0 or b <= 0:
        return None
    return round(((b / a) ** (1 / years) - 1) * 100, 1)


def drawdown(ts, vs):
    peak, peak_t, worst, w_peak, w_low = None, None, 0.0, None, None
    for t, v in zip(ts, vs):
        if peak is None or v > peak:
            peak, peak_t = v, t
        dd = v / peak - 1
        if dd < worst:
            worst, w_peak, w_low = dd, peak_t, t
    if w_peak is None:
        return None, None
    span = f"{dt(w_peak, timezone.utc):%b %Y} to {dt(w_low, timezone.utc):%b %Y}"
    return round(worst * 100, 1), span


def log_returns(vs):
    return [math.log(b / a) for a, b in zip(vs, vs[1:]) if a and b and a > 0 and b > 0]


def week_key(t):
    y, w, _ = dt(t, timezone.utc).isocalendar()
    return f"{y}-{w:02d}"


def returns_block(ts, vs, now_t, last):
    yr = 365.25 * 86400
    out = {}
    out["ret_1y"] = pct(value_at(ts, vs, now_t - yr), last)
    for n in (3, 5, 10):
        start = value_at(ts, vs, now_t - n * yr)
        # need history that really reaches back n years (allow 3 weeks slack)
        out[f"ret_{n}y"] = ann(start, last, n) if ts and ts[0] <= now_t - n * yr + 21 * 86400 else None
    return out


# ---------- sources ----------

def yahoo(sym):
    q = urllib.request.quote(sym, safe="")
    p1 = int((NOW - timedelta(days=int(10.6 * 365))).timestamp())
    wk = json.loads(fetch(f"https://query1.finance.yahoo.com/v8/finance/chart/{q}?period1={p1}&period2={int(NOW.timestamp())}&interval=1wk&events=div",
                          f"y_{slug(sym)}_wk11.json"))
    dy = json.loads(fetch(f"https://query1.finance.yahoo.com/v8/finance/chart/{q}?range=3mo&interval=1d",
                          f"y_{slug(sym)}_d.json"))
    w = wk["chart"]["result"][0]
    d = dy["chart"]["result"][0]
    adj = (w["indicators"].get("adjclose") or [{}])[0].get("adjclose") or w["indicators"]["quote"][0]["close"]
    pts = [(t, v) for t, v in zip(w["timestamp"], adj) if v]
    ts, vs = [p[0] for p in pts], [p[1] for p in pts]
    dq = d["indicators"]["quote"][0]
    dpts = [(t, c, vol) for t, c, vol in zip(d["timestamp"], dq["close"], dq["volume"]) if c]
    return w["meta"], ts, vs, dpts


def build_assets():
    out, series, weekly = [], {}, {}
    for (aid, name, sym, src, way, sub, mn, sell) in ASSETS:
        print("asset", sym)
        try:
            meta, ts, vs, dpts = yahoo(sym)
        except Exception as e:
            print("  ! skipped", sym, e)
            continue
        if len(vs) < 10:
            print("  ! too little history", sym)
            continue
        price = meta.get("regularMarketPrice") or dpts[-1][1]
        last_t = dpts[-1][0] if dpts else ts[-1]
        # scale adjusted series so the last point equals the latest total-return level
        last_adj = vs[-1]
        r = returns_block(ts, vs, last_t, last_adj)
        # 30 days, from daily closes
        d_ts = [p[0] for p in dpts]
        d_cs = [p[1] for p in dpts]
        r["chg_30d"] = pct(value_at(d_ts, d_cs, last_t - 30 * 86400), d_cs[-1]) if dpts else None
        # $ volume over 30 days (exchange-reported volume x close)
        usd_vol = meta.get("instrumentType") == "CRYPTOCURRENCY"  # Yahoo reports crypto volume in USD already
        vol30 = sum((v or 0) * (1 if usd_vol else (c or 0)) for t, c, v in dpts if t > last_t - 30 * 86400)
        # risk: swing = annualised volatility of weekly returns, last 3 years
        cut = last_t - 3 * 365.25 * 86400
        rw = log_returns([v for t, v in zip(ts, vs) if t >= cut])
        swing = round(statistics.pstdev(rw) * math.sqrt(52) * 100, 1) if len(rw) > 20 else None
        dd, dd_span = drawdown(ts, vs)
        weekly[aid] = {week_key(t): v for t, v in zip(ts, vs) if t >= cut}
        # monthly series for charts (last close of each month) + latest point
        months = {}
        for t, v in zip(ts, vs):
            months[dt(t, timezone.utc).strftime("%Y-%m")] = round(v, 6)
        series[aid] = {"m": list(months.keys()), "v": list(months.values())}
        hist_from = dt(ts[0], timezone.utc).strftime("%b %Y")
        out.append({
            "id": aid, "name": name, "symbol": sym, "way": way, "sub": sub, "min": mn, "sell": sell,
            "price": round(price, 6), "currency": meta.get("currency") or "USD",
            "chg_30d": r["chg_30d"], "ret_1y": r["ret_1y"], "ret_3y": r["ret_3y"], "ret_5y": r["ret_5y"], "ret_10y": r["ret_10y"],
            "swing": swing, "dd": dd, "dd_span": dd_span, "vol30_usd": round(vol30) if vol30 > 0 and meta.get("currency") == "USD" else None,
            "history_from": hist_from,
            "asof": dt(last_t, timezone.utc).strftime("%Y-%m-%d"),
            "source": "Yahoo Finance", "url": f"https://finance.yahoo.com/quote/{urllib.request.quote(sym, safe='')}",
            "trust": "market", "total_return": bool((meta.get("instrumentType") or "") in ("ETF", "EQUITY", "MUTUALFUND")),
            "kind": meta.get("instrumentType"),
        })
    # correlation with the S&P 500 (weekly returns, 3 years)
    base = weekly.get("spy", {})
    for a in out:
        mine = weekly.get(a["id"], {})
        keys = sorted(set(base) & set(mine))
        xs = log_returns([base[k] for k in keys])
        ys = log_returns([mine[k] for k in keys])
        a["corr"] = round(statistics.correlation(xs, ys), 2) if len(xs) > 30 else None
    return out, series


def build_houses():
    out, series = [], {}
    for hid, country, sid in HOUSES:
        print("house", country)
        try:
            raw = fetch(f"https://fred.stlouisfed.org/graph/fredgraph.csv?id={sid}", f"fred_{sid}.csv", ttl=7 * 86400, timeout=30, ua="curl/8.4.0").decode()
        except Exception as e:
            print("  ! skipped", sid, e)
            continue
        rows = [r for r in csv.reader(io.StringIO(raw))][1:]
        pts = []
        for d, v in rows:
            try:
                pts.append((datetime.strptime(d, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp(), float(v), d))
            except ValueError:
                pass
        if len(pts) < 8:
            continue
        ts, vs = [p[0] for p in pts], [p[1] for p in pts]
        last_t, last = ts[-1], vs[-1]
        r = returns_block(ts, vs, last_t, last)
        tail = [v for t, v in zip(ts, vs) if t >= last_t - 10 * 365.25 * 86400]
        rq = log_returns(tail)
        swing = round(statistics.pstdev(rq) * 2 * 100, 1) if len(rq) > 8 else None
        dd, span = drawdown(ts, vs)
        last_d = dt(last_t, timezone.utc)
        q = (last_d.month - 1) // 3 + 1
        series[hid] = {"m": [p[2][:7] for p in pts if p[0] >= last_t - 20 * 365.25 * 86400],
                       "v": [p[1] for p in pts if p[0] >= last_t - 20 * 365.25 * 86400]}
        out.append({"id": hid, "name": f"{country} house prices", "country": country, "symbol": sid,
                    "way": "Apartments and houses", "sub": f"{country} · residential price index",
                    "min": "price of a home", "sell": "3–6 months",
                    "price": round(last, 1), "currency": "index", "chg_30d": None,
                    "ret_1y": r["ret_1y"], "ret_3y": r["ret_3y"], "ret_5y": r["ret_5y"], "ret_10y": r["ret_10y"],
                    "swing": swing, "dd": dd, "dd_span": span, "vol30_usd": None, "corr": None,
                    "history_from": dt(ts[0], timezone.utc).strftime("%Y"),
                    "asof": f"Q{q} {last_d.year}", "source": "BIS residential property prices via FRED",
                    "url": f"https://fred.stlouisfed.org/series/{sid}", "trust": "index", "total_return": False,
                    "kind": "INDEX"})
    return out, series


def build_deposits():
    def wb(ind):
        raw = json.loads(fetch(f"https://api.worldbank.org/v2/country/all/indicator/{ind}?format=json&mrnev=1&per_page=400",
                               f"wb_{ind}.json", ttl=7 * 86400))
        return {r["countryiso3code"]: r for r in raw[1] if r.get("value") is not None and r["countryiso3code"]}
    dep, inf = wb("FR.INR.DPST"), wb("FP.CPI.TOTL.ZG")
    out = []
    for iso, r in dep.items():
        i = inf.get(iso)
        out.append({"iso": iso, "country": r["country"]["value"], "rate": round(r["value"], 2), "rate_year": r["date"],
                    "infl": round(i["value"], 2) if i else None, "infl_year": i["date"] if i else None,
                    "real": round(r["value"] - i["value"], 2) if i else None})
    out.sort(key=lambda x: x["country"])
    print("deposits", len(out), "countries")
    return out


def build_defi():
    raw = json.loads(fetch("https://yields.llama.fi/pools", "llama_pools.json", ttl=6 * 3600, timeout=90))["data"]
    out = []
    for way, label, project, chain, symbol in DEFI:
        cands = [p for p in raw if p.get("project") == project and (chain is None or p.get("chain") == chain)
                 and (symbol is None or p.get("symbol") == symbol)]
        if not cands:
            print("  ! no pool", label)
            continue
        p = max(cands, key=lambda p: p.get("tvlUsd") or 0)
        out.append({"way": way, "name": label, "apy": round(p.get("apy") or 0, 2),
                    "apy_30d": round(p["apyMean30d"], 2) if p.get("apyMean30d") is not None else None,
                    "tvl_usd": round(p.get("tvlUsd") or 0), "chain": p.get("chain"),
                    "source": "DefiLlama yields", "url": f"https://defillama.com/yields/pool/{p['pool']}", "asof": TODAY})
    print("defi", len(out), "pools")
    return out


def load_research():
    res = {}
    d = os.path.join(DATA, "research")
    if not os.path.isdir(d):
        return res
    for fn in sorted(os.listdir(d)):
        if fn.endswith(".json"):
            with open(os.path.join(d, fn), encoding="utf-8") as f:
                for w in json.load(f).get("ways", []):
                    res[w["way"]] = w
    print("research", len(res), "ways")
    return res


# ---------- assemble ----------

def mk(value, source, url, asof, trust, period=None, note=None):
    return {"value": value, "source": source, "url": url, "asof": asof, "trust": trust, "period": period, "note": note}


def main():
    assets, series = build_assets()
    houses, hseries = build_houses()
    series.update(hseries)
    deposits = build_deposits()
    defi = build_defi()
    research = load_research()
    all_assets = assets + houses
    by_way = {}
    for a in all_assets:
        by_way.setdefault(a["way"], []).append(a)

    ways = []
    for g in GROUPS:
        for name, entry, liq, risk, desc in WAYS[g["slug"]]:
            w = {"slug": slug(name), "name": name, "group": g["slug"], "entry": entry, "liq": liq, "risk": risk,
                 "desc": desc, "assets": [a["id"] for a in by_way.get(name, [])], "metrics": {}, "examples": [],
                 "yields": [d for d in defi if d["way"] == name], "note": None}
            m = w["metrics"]
            tracked = by_way.get(name, [])
            if name == "Apartments and houses" and tracked:
                for k, label in (("ret_1y", "1y"), ("ret_5y", "5y"), ("ret_10y", "10y")):
                    vals = [a[k] for a in tracked if a[k] is not None]
                    if vals:
                        m[k] = mk(round(statistics.median(vals), 1), f"BIS house price indexes, median of {len(vals)} countries",
                                  "https://data.bis.org/topics/RPP", tracked[0]["asof"], "index", note="nominal, local currency")
                sw = [a["swing"] for a in tracked if a["swing"] is not None]
                if sw:
                    m["swing"] = mk(round(statistics.median(sw), 1), "BIS house price indexes, median", "https://data.bis.org/topics/RPP", tracked[0]["asof"], "index")
            elif tracked:
                rep = tracked[0]
                for k in ("ret_1y", "ret_5y", "ret_10y", "swing", "dd", "corr"):
                    if rep.get(k) is not None:
                        m[k] = mk(rep[k], f"{rep['name']} ({rep['symbol']}), {rep['source']}", rep["url"], rep["asof"],
                                  rep["trust"], note=("representative asset" + (", total return incl. dividends" if rep["total_return"] else "")))
                v30 = [a["vol30_usd"] for a in tracked if a.get("vol30_usd")]
                if v30:
                    m["vol30"] = mk(sum(v30), f"sum of {len(v30)} tracked assets, Yahoo Finance", None, rep["asof"], "market")
            if name == "Savings and deposits" and deposits:
                reals = [d["real"] for d in deposits if d["real"] is not None]
                rates = [d["rate"] for d in deposits]
                m["yield"] = mk(round(statistics.median(rates), 2), f"World Bank, deposit interest rate, median of {len(rates)} countries",
                                "https://data.worldbank.org/indicator/FR.INR.DPST", max(d["rate_year"] for d in deposits), "index")
                m["real_yield"] = mk(round(statistics.median(reals), 2), "World Bank: deposit rate minus inflation, median",
                                     "https://data.worldbank.org/indicator/FP.CPI.TOTL.ZG", max(d["rate_year"] for d in deposits), "index")
            if w["yields"]:
                best = w["yields"][0]
                m["yield"] = mk(best["apy_30d"] if best["apy_30d"] is not None else best["apy"], f"{best['name']}, DefiLlama (30-day average APY)",
                                best["url"], best["asof"], "market")
            r = research.get(name)
            if r:
                for k, v in (r.get("metrics") or {}).items():
                    if not v:
                        continue
                    key = {"ret_5y_ann": "ret_5y", "ret_10y_ann": "ret_10y", "volatility": "swing", "max_drawdown": "dd"}.get(k, k)
                    if key in m and m[key].get("trust") == "market":
                        m[key + "_report"] = v  # keep both: live proxy and published figure
                    elif key not in m:
                        m[key] = v
                w["examples"] = r.get("examples") or []
                w["note"] = r.get("note")
            ways.append(w)

    groups = []
    for g in GROUPS:
        gw = [w for w in ways if w["group"] == g["slug"]]
        y1 = [w["metrics"]["ret_1y"]["value"] for w in gw if w["metrics"].get("ret_1y") and isinstance(w["metrics"]["ret_1y"].get("value"), (int, float))]
        sw = [w["metrics"]["swing"]["value"] for w in gw if w["metrics"].get("swing") and isinstance(w["metrics"]["swing"].get("value"), (int, float))]
        v30 = sum(w["metrics"]["vol30"]["value"] for w in gw if w["metrics"].get("vol30"))
        groups.append(dict(g, n=len(gw), ret_1y=round(statistics.median(y1), 1) if y1 else None, ret_1y_n=len(y1),
                           swing=round(statistics.median(sw), 1) if sw else None, vol30=v30 or None,
                           sells=min(gw, key=lambda w: -w["liq"])["liq"], ways=[w["slug"] for w in gw]))

    grp_of = {w["name"]: w["group"] for w in ways}
    for a in all_assets:
        a["group"] = grp_of.get(a["way"])
        a["way_slug"] = slug(a["way"])

    catalog = {
        "meta": {"built": NOW.strftime("%Y-%m-%d %H:%M UTC"), "n_ways": len(ways), "n_assets": len(all_assets),
                 "n_countries": len(deposits), "n_sources": None},
        "groups": groups, "ways": ways, "assets": all_assets, "deposits": deposits,
    }
    srcs = {"Yahoo Finance", "FRED / BIS", "World Bank", "DefiLlama"}
    for w in ways:
        for v in w["metrics"].values():
            if isinstance(v, dict) and v.get("source"):
                srcs.add(v["source"].split(",")[0].split("(")[0].strip())
    catalog["meta"]["n_sources"] = len(srcs)
    os.makedirs(DATA, exist_ok=True)
    with open(os.path.join(DATA, "catalog.json"), "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(DATA, "series.json"), "w", encoding="utf-8") as f:
        json.dump(series, f, separators=(",", ":"))
    # same data as scripts, so pages also work when opened straight from disk (file://)
    with open(os.path.join(DATA, "catalog.js"), "w", encoding="utf-8") as f:
        f.write("window.CATALOG=" + json.dumps(catalog, ensure_ascii=False, separators=(",", ":")) + ";")
    with open(os.path.join(DATA, "series.js"), "w", encoding="utf-8") as f:
        f.write("window.SERIES=" + json.dumps(series, separators=(",", ":")) + ";")
    with_ret = sum(1 for w in ways if w["metrics"].get("ret_1y") or w["metrics"].get("ret_10y") or w["metrics"].get("yield"))
    print(f"done: {len(ways)} ways ({with_ret} with a return or yield figure), {len(all_assets)} assets, {len(deposits)} countries")


if __name__ == "__main__":
    main()
