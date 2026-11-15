CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Price points from daily sources: weekly adjusted closes (10y+), daily closes (3 months),
-- quarterly index levels (house prices). kind: 'w' | 'd' | 'q'
CREATE TABLE price_points (
  asset_id  text        NOT NULL,
  kind      char(1)     NOT NULL,
  ts        timestamptz NOT NULL,
  value     double precision NOT NULL,
  volume    double precision,
  PRIMARY KEY (asset_id, kind, ts)
);

-- Live 1-minute candles built from websocket ticks (our own price history)
CREATE TABLE candles_1m (
  asset_id  text        NOT NULL,
  ts        timestamptz NOT NULL,
  open      double precision NOT NULL,
  high      double precision NOT NULL,
  low       double precision NOT NULL,
  close     double precision NOT NULL,
  ticks     integer     NOT NULL,
  source    text        NOT NULL,
  PRIMARY KEY (asset_id, ts)
);
SELECT create_hypertable('candles_1m', by_range('ts', INTERVAL '7 days'));
ALTER TABLE candles_1m SET (timescaledb.compress, timescaledb.compress_segmentby = 'asset_id', timescaledb.compress_orderby = 'ts');
SELECT add_compression_policy('candles_1m', INTERVAL '7 days');

-- Hourly and daily candles kept up to date by TimescaleDB itself
CREATE MATERIALIZED VIEW candles_1h WITH (timescaledb.continuous) AS
  SELECT asset_id, time_bucket(INTERVAL '1 hour', ts) AS ts,
         first(open, ts) AS open, max(high) AS high, min(low) AS low, last(close, ts) AS close, sum(ticks)::int AS ticks
  FROM candles_1m GROUP BY asset_id, time_bucket(INTERVAL '1 hour', ts) WITH NO DATA;
SELECT add_continuous_aggregate_policy('candles_1h', start_offset => INTERVAL '3 days', end_offset => INTERVAL '1 hour', schedule_interval => INTERVAL '30 minutes');

CREATE MATERIALIZED VIEW candles_1d WITH (timescaledb.continuous) AS
  SELECT asset_id, time_bucket(INTERVAL '1 day', ts) AS ts,
         first(open, ts) AS open, max(high) AS high, min(low) AS low, last(close, ts) AS close, sum(ticks)::int AS ticks
  FROM candles_1m GROUP BY asset_id, time_bucket(INTERVAL '1 day', ts) WITH NO DATA;
SELECT add_continuous_aggregate_policy('candles_1d', start_offset => INTERVAL '7 days', end_offset => INTERVAL '1 day', schedule_interval => INTERVAL '6 hours');

-- Network and exchange timing samples (latency to exchanges, block intervals)
CREATE TABLE timing_samples (
  key   text        NOT NULL,   -- e.g. 'binance.latency_ms', 'bitcoin.block_s'
  ts    timestamptz NOT NULL,
  value double precision NOT NULL
);
SELECT create_hypertable('timing_samples', by_range('ts', INTERVAL '30 days'));
CREATE INDEX ON timing_samples (key, ts DESC);

-- Snapshot of every built catalog (what was published, when)
CREATE TABLE catalog_builds (
  id       bigserial PRIMARY KEY,
  built_at timestamptz NOT NULL DEFAULT now(),
  n_assets integer NOT NULL,
  n_ways   integer NOT NULL,
  json     jsonb   NOT NULL
);

-- Small key/value store for slow sources (World Bank, DefiLlama, FRED days on market)
CREATE TABLE source_cache (
  key        text PRIMARY KEY,
  fetched_at timestamptz NOT NULL,
  body       text NOT NULL
);
