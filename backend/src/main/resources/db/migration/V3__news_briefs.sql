-- Our own short briefs for headlines from the news feeds (written by Claude from headline + teaser)
CREATE TABLE news_briefs (
  url        text PRIMARY KEY,
  title      text NOT NULL,
  brief      text NOT NULL,
  model      text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON news_briefs (created_at DESC);
