-- Ad events: Kafka topic 'ad-events' -> queue table -> materialized view -> ad_events.
-- ReplacingMergeTree collapses duplicate event_ids (e.g. collector retries) during merges;
-- use FINAL or GROUP BY event_id in queries that must be exact before merges run.

CREATE DATABASE IF NOT EXISTS adtech;

CREATE TABLE IF NOT EXISTS adtech.ad_events
(
    event_date   Date DEFAULT toDate(ts),
    ts           DateTime64(3, 'UTC'),
    event_type   LowCardinality(String),
    event_id     String,
    request_id   String,
    line_item_id String,
    creative_id  String,
    placement_id String,
    publisher_id String,
    user_key     String,
    country      LowCardinality(String),
    device       LowCardinality(String),
    cpm          Float64
)
ENGINE = ReplacingMergeTree(ts)
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_type, line_item_id, event_id);

CREATE TABLE IF NOT EXISTS adtech.ad_events_queue
(
    event_id     String,
    event_type   String,
    ts_ms        Int64,
    request_id   String,
    line_item_id String,
    creative_id  String,
    placement_id String,
    publisher_id String,
    user_key     String,
    country      String,
    device       String,
    cpm          Float64
)
ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = 'ad-events',
         kafka_group_name = 'clickhouse-ad-events',
         kafka_format = 'JSONEachRow',
         kafka_skip_broken_messages = 100;

CREATE MATERIALIZED VIEW IF NOT EXISTS adtech.ad_events_mv TO adtech.ad_events AS
SELECT
    fromUnixTimestamp64Milli(ts_ms) AS ts,
    event_type, event_id, request_id, line_item_id, creative_id,
    placement_id, publisher_id, user_key, country, device, cpm
FROM adtech.ad_events_queue;

-- Daily delivery report per line item (example query):
-- SELECT event_date, line_item_id,
--        countIf(event_type = 'impression') AS impressions,
--        countIf(event_type = 'click')      AS clicks,
--        round(clicks / nullIf(impressions, 0), 4) AS ctr,
--        round(sumIf(cpm, event_type = 'impression') / 1000, 2) AS revenue_usd
-- FROM adtech.ad_events FINAL
-- GROUP BY event_date, line_item_id
-- ORDER BY event_date DESC, impressions DESC;
