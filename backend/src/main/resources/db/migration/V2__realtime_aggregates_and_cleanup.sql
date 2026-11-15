-- Show the most recent, not yet materialised hours/days in the hourly and daily candles (real-time aggregates)
ALTER MATERIALIZED VIEW candles_1h SET (timescaledb.materialized_only = false);
ALTER MATERIALIZED VIEW candles_1d SET (timescaledb.materialized_only = false);

-- Earlier builds stored Yahoo's still-forming bar under a new timestamp on every run.
-- Drop the weekly and daily points; the next daily build refetches the full history (10+ years).
DELETE FROM price_points WHERE kind IN ('w', 'd');

-- Timing samples: one row per key per minute; keep 90 days, compress after 7
ALTER TABLE timing_samples SET (timescaledb.compress, timescaledb.compress_segmentby = 'key', timescaledb.compress_orderby = 'ts');
SELECT add_compression_policy('timing_samples', INTERVAL '7 days');
SELECT add_retention_policy('timing_samples', INTERVAL '90 days');

-- What each build produced, whether it passed validation and what the deploy said
ALTER TABLE catalog_builds ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'published';
