-- Optional caller-supplied reference that makes creates idempotent: a second create with the same
-- external_ref returns the existing row instead of a duplicate. Used by the AdCP sales agent.
ALTER TABLE advertiser ADD COLUMN external_ref VARCHAR(200) UNIQUE;
ALTER TABLE ad_order   ADD COLUMN external_ref VARCHAR(200) UNIQUE;
ALTER TABLE creative   ADD COLUMN external_ref VARCHAR(200) UNIQUE;
ALTER TABLE line_item  ADD COLUMN external_ref VARCHAR(200) UNIQUE;
