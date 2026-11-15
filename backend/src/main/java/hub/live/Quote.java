package hub.live;

/**
 * Latest live price of one asset.
 * @param chg24h  price change over 24 hours, percent (as reported by the exchange)
 * @param vol24hUsd traded value over 24 hours in USD, if known
 * @param ts      receive time, epoch millis
 */
public record Quote(String id, double price, Double chg24h, Double vol24hUsd, long ts, String source) {}
