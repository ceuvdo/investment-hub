# Investment hub: MVP

Free, independent catalog of 64 ways to invest in 9 groups. Every number has a source and a date. Static site: plain HTML, CSS and JS, no build step. The data comes from one Python script (standard library only).

## Run locally

From this folder:

```bash
python -m http.server 8765
```

Then open http://localhost:8765. You can also open `index.html` straight from disk: the data is also written as `data/*.js`.

## Update the data

```bash
python pipeline/build_data.py
```

Raw responses are cached in `pipeline/cache/` for 12 hours. Add `--fresh` to refetch everything. A full run takes about 1–2 minutes. On GitHub, `.github/workflows/refresh-data.yml` does this every day at 06:15 UTC and commits the new data. You can also start it by hand from the Actions tab.

## Deploy to Cloudflare Pages

Node.js and Wrangler are installed on `E:\tools` because drive C: is full. The script reads `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` from the user environment variables, so the token never appears in commands or logs.

```powershell
.\deploy.ps1
```

On the first run it creates the `investment-hub` project. After that, every run publishes a new version to `https://investment-hub.pages.dev`, or to a similar address if that name is taken. Only `*.html`, `assets/` and `data/` are uploaded.

## Where the numbers come from

| Data | Source | How often |
| --- | --- | --- |
| Prices, 1y/5y/10y return, swing, worst drop, correlation, 30-day volume for 73 ETFs, stocks, coins, FX, clubs | Yahoo Finance chart API (weekly, dividend-adjusted) | every build |
| House prices in 16 countries | BIS via FRED (quarterly) | every build |
| Deposit rates and inflation in 152 countries, Ukraine included | World Bank API | every build |
| Staking, lending and vault yields | DefiLlama yields API | every build |
| Private markets, property indexes, businesses, collectibles, sport | `data/research/*.json`: Cambridge Associates, NCREIF, HFR, Cliffwater, Knight Frank, Liv-ex, BizBuySell and others, each with a URL and date | by hand, when a new edition comes out |

45 of 64 ways have a return or yield figure. The other 19 show "no data" instead of a made-up number: structured notes, NFTs, angel deals, the business ways, sneakers, skins and some others. Their pages still show market size, fees and examples where these were found.

## Before a public launch

- **Yahoo Finance is not licensed for republishing.** For production, switch to a licensed feed (for example Twelve Data, Polygon or EOD Historical Data) or to official exchange and issuer data. Only the `yahoo()` function in `build_data.py` needs to change.
- **Some research figures need checking at the source.** They were seen only in search results (HFR, BizBuySell, Card Ladder, WatchCharts, PCGS), and each one's `note` in `data/research/*.json` says so. The Knight Frank PDF link points to a third-party mirror and should be replaced with the official one.
- **Photo licence.** crypto.jpg is CC BY-SA 2.0: the author's name still has to be added on the methodology page.
- **Working name.** "kapitalog" is a placeholder and is set in one line in `assets/js/core.js` (`SITE.name`).
- **Legal review.** The disclaimer and methodology pages exist; the legal check has not been done.

## Structure

```
index.html        home: search, budget entry, return vs risk by group, groups, rankings, head-to-heads
map.html          all 64 ways, filters by budget and risk
class.html?g=     a group: equal-weight chart, ways table, tracked assets
way.html?w=       a way: every figure with source, chart, assets, DeFi yields, deposits by country
asset.html?id=    an asset: price, returns, risk, chart vs S&P 500, liquidity, $1,000 then vs now
compare.html?ids= up to 4 assets side by side
rankings.html     every tracked asset, sortable
watchlist.html    saved in the browser (localStorage)
methodology.html  definitions, trust levels, full source list, photo credits
pipeline/         universe.py (what is tracked) + build_data.py (fetch and compute)
data/             catalog.json, series.json (+ .js copies), research/*.json
```

To add an asset, add a line to `ASSETS` in `pipeline/universe.py` with any Yahoo symbol, then rebuild. House price countries go in `HOUSES` (BIS series ids from FRED).

Palette: dark navy by default. The light palettes A, B and C can be switched in the footer, so the team can compare them on the real site.
