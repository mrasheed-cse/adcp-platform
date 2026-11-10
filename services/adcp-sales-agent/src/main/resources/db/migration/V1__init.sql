-- Sales agent state, in its own schema (sales_agent). The ad server's data lives in the trafficking API.

CREATE TABLE media_buy (
    id                        VARCHAR(40)    PRIMARY KEY,
    account_id                VARCHAR(100)   NOT NULL,
    idempotency_key           VARCHAR(255)   NOT NULL,
    request_hash              VARCHAR(64)    NOT NULL,
    brand                     VARCHAR(255)   NOT NULL,
    status                    VARCHAR(30)    NOT NULL,
    start_time                TIMESTAMPTZ    NOT NULL,
    end_time                  TIMESTAMPTZ    NOT NULL,
    total_budget              NUMERIC(14, 2) NOT NULL,
    currency                  VARCHAR(3)     NOT NULL,
    packages                  JSONB          NOT NULL,
    provisioning              VARCHAR(20)    NOT NULL,
    provisioning_error        TEXT,
    dirty                     BOOLEAN        NOT NULL,
    trafficking_advertiser_id VARCHAR(40),
    trafficking_order_id      VARCHAR(40),
    version                   BIGINT         NOT NULL,
    created_at                TIMESTAMPTZ    NOT NULL,
    updated_at                TIMESTAMPTZ    NOT NULL,
    CONSTRAINT media_buy_idempotency UNIQUE (account_id, idempotency_key)
);

CREATE INDEX media_buy_account_idx ON media_buy (account_id);
CREATE INDEX media_buy_dirty_idx ON media_buy (updated_at) WHERE dirty;

CREATE TABLE creative (
    account_id              VARCHAR(100)  NOT NULL,
    creative_id             VARCHAR(100)  NOT NULL,   -- the buyer's id
    name                    VARCHAR(200)  NOT NULL,
    format_id               VARCHAR(50)   NOT NULL,
    image_url               VARCHAR(2048),
    html                    TEXT,
    click_url               VARCHAR(2048) NOT NULL,
    trafficking_creative_id VARCHAR(40),
    created_at              TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (account_id, creative_id)
);
