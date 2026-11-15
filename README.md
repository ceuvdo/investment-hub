# Investment hub

A free, independent catalog of 64 ways to invest. Every number has a source and a date. Crypto and currency prices are live.

- **Site:** https://investment-hub.ceuvdo.workers.dev (static HTML, CSS and JS on Cloudflare)
- **Live relay:** https://investment-hub-live.ceuvdo.workers.dev (Cloudflare Worker + Durable Object)
- **Backend:** Java 21 + Spring Boot 4 on this PC, with PostgreSQL 17 + TimescaleDB. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## How it runs

The Windows Task Scheduler task **investment-hub backend** starts at logon. It runs `backend/start-backend.ps1`, which:

1. starts PostgreSQL (`E:\data\pg`) if it is not running;
2. starts the backend (`backend/target/hub-backend.jar`) on http://127.0.0.1:8080. The backend only listens locally.

The backend then does three things:

- **Live feeds over public websockets.**
  - Prices: Binance (24 crypto pairs and tokenised gold) and Kraken (6 currency pairs).
  - Network timing: mempool.space (Bitcoin blocks), a public Ethereum node (blocks) and Solana RPC (slots).
  - Every 30 s it measures the round trip to the Binance and Kraken APIs.
  - Every minute it writes 1-minute candles to TimescaleDB. Hourly and daily candles are aggregated automatically.
  - About every 3 s it sends changed prices, signed with HMAC, to the Cloudflare relay. Visitors' browsers connect to the relay, never to this PC.
- **Daily build at 09:00.**
  - Sources: Yahoo Finance closes (10+ years, weekly), BIS house prices and US days on market via FRED, World Bank deposit rates and inflation, DefiLlama yields, plus `data/research/*.json`.
  - It computes returns, risk and time to sell, then writes `data/catalog.*` and `data/series.*` and runs `deploy.ps1`.
- **API** (local):
  - `GET /api/status`
  - `GET /api/catalog`
  - `GET /api/assets/{id}`
  - `GET /api/series/{id}?range=1d|7d|30d|10y`
  - `GET /api/live`
  - `WS /live`
  - `POST /api/admin/build?deploy=true`, `POST /api/admin/backup` (both need the header `X-Hub-Admin: 1`)

## Common tasks

Check that everything is alive:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/status
```

Rebuild the data and publish the site now:

```powershell
Invoke-RestMethod -Method Post -Headers @{ "X-Hub-Admin" = "1" } "http://127.0.0.1:8080/api/admin/build?deploy=true"
```

Rebuild the backend after a code change. Tools live on `E:\tools`, because drive C: is full.

```powershell
$env:JAVA_HOME='E:\tools\jdk'; & E:\tools\maven\bin\mvn.cmd -q -f backend\pom.xml -DskipTests package
```

Then restart the task:

```powershell
Stop-ScheduledTask 'investment-hub backend'; Start-ScheduledTask 'investment-hub backend'
```

**Add an asset:** add it to `backend/src/main/resources/universe.json`, then rebuild. For a Yahoo symbol, put it under `assets`. For a live feed, add it under `live.binance` or `live.kraken`. For a new country, add its BIS series id under `houses`.

**Redeploy the relay Worker:**

```powershell
relay\deploy-relay.ps1
```

**Logs:** `E:\tools\logs` (`hub-backend.log`, `postgres.log`, `deploy-last.log`).

## Safety nets

- **Validation before publishing.** A build is published only if it has all 64 ways and at least 90% of the previous asset count, contains no impossible prices or returns, has no more than 20% of market assets older than a week, and every research link is `https`. Otherwise the old site stays online and `/api/status` explains why.
- **Code and data are published separately.** The site's code is published from the last git commit, and only the freshly built data comes from the working folder.
- **Missed builds catch up.** If the PC was off at 09:00, the build runs at the next startup.
- **Nightly backup** to `E:ackups\hub` at 03:30, keeping 14 days. This is the same disk; an off-site copy is still on the to-do list.
- **Tests:** 84 JUnit tests. Run them with `mvn -f backend/pom.xml test` after setting `HUB_DB_PASSWORD` in the shell. They use the separate `hub_test` database and never touch the internet.

## Secrets

All secrets are Windows user environment variables. None of them are in the repo.

| Variable | Used for |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` (`CLOUDFLARE_ACCOUNT_ID` optional) | Deploying the site and the relay |
| `HUB_DB_PASSWORD`, `HUB_PG_SUPERUSER_PASSWORD` | Local database |
| `HUB_RELAY_SECRET`, `HUB_RELAY_URL` | Signing pushes to the relay |

## Data and honesty rules

- **Live data:** only crypto and currencies. Shares, funds, bonds and indexes use daily closing prices. Free sources do not allow showing live stock prices publicly; a licensed feed is needed for that.
- **Time to sell** has two parts:
  - **Order fills:** the measured network round trip for crypto and currencies; under 1 s in trading hours for exchanges.
  - **Money is yours:** the market's settlement rule (US T+1, EU and UK T+2, India T+1), or for coins the measured block or finality time.
- **Empty fields:** where no reliable figure exists, the site says "no data" instead of a made-up number.

## Before a public launch

- Yahoo Finance data is not licensed for republishing; switch `Sources.yahoo()` to a licensed feed.
- Some research figures were seen only in search results; each one's `note` in `data/research/*.json` says so.
- crypto.jpg (CC BY-SA 2.0) still needs its author named on the methodology page.
- The site name is Wyndton, set in `assets/js/core.js` (`SITE.name`).
- The PC must stay on for live prices. When it is off, the site falls back to the daily snapshot. Moving to a server means the same jar, PostgreSQL and environment variables there.
