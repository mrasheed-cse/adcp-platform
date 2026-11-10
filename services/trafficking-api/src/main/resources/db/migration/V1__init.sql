CREATE TABLE advertiser (
    id          VARCHAR(40)  PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ad_order (
    id             VARCHAR(40)  PRIMARY KEY,
    advertiser_id  VARCHAR(40)  NOT NULL REFERENCES advertiser (id),
    name           VARCHAR(200) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE placement (
    id          VARCHAR(64)  PRIMARY KEY,   -- human-readable slug used in ad tags, e.g. sports_top
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE creative (
    id             VARCHAR(40)   PRIMARY KEY,
    advertiser_id  VARCHAR(40)   NOT NULL REFERENCES advertiser (id),
    name           VARCHAR(200)  NOT NULL,
    type           VARCHAR(10)   NOT NULL,
    width          INT           NOT NULL,
    height         INT           NOT NULL,
    image_url      VARCHAR(2048),
    html           TEXT,
    landing_url    VARCHAR(2048) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE line_item (
    id                  VARCHAR(40)    PRIMARY KEY,
    order_id            VARCHAR(40)    NOT NULL REFERENCES ad_order (id),
    name                VARCHAR(200)   NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    priority            INT            NOT NULL,
    cpm                 NUMERIC(12, 4) NOT NULL,
    start_time          TIMESTAMPTZ    NOT NULL,
    end_time            TIMESTAMPTZ    NOT NULL,
    goal_impressions    BIGINT         NOT NULL DEFAULT 0,
    freq_max            INT,
    freq_window_seconds INT,
    countries           VARCHAR(1000)  NOT NULL DEFAULT '',
    devices             VARCHAR(100)   NOT NULL DEFAULT '',
    placements          VARCHAR(2000)  NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT line_item_flight CHECK (end_time > start_time),
    CONSTRAINT line_item_freq CHECK ((freq_max IS NULL) = (freq_window_seconds IS NULL))
);

CREATE INDEX line_item_status_idx ON line_item (status);
CREATE INDEX line_item_order_idx ON line_item (order_id);

CREATE TABLE line_item_creative (
    line_item_id VARCHAR(40) NOT NULL REFERENCES line_item (id) ON DELETE CASCADE,
    creative_id  VARCHAR(40) NOT NULL REFERENCES creative (id),
    PRIMARY KEY (line_item_id, creative_id)
);
