-- Build once per snapshot, inside a new catalog.db. Open read-only for search.
PRAGMA foreign_keys = ON;
BEGIN;

CREATE TABLE entity (
    entity_key TEXT PRIMARY KEY NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('offer','family','vendor','category','collection')),
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    canonical_url TEXT, -- NULL selects the standalone UI's detail route
    UNIQUE (kind, id)
);
CREATE TABLE vendor (
    vendor_id TEXT PRIMARY KEY NOT NULL,
    entity_key TEXT NOT NULL UNIQUE REFERENCES entity(entity_key),
    CHECK (entity_key = 'vendor/' || vendor_id)
);
CREATE TABLE category (
    category_id TEXT PRIMARY KEY NOT NULL,
    parent_id TEXT REFERENCES category(category_id) DEFERRABLE INITIALLY DEFERRED,
    entity_key TEXT NOT NULL UNIQUE REFERENCES entity(entity_key),
    CHECK (entity_key = 'category/' || category_id)
);
CREATE INDEX category_parent ON category(parent_id);
CREATE TABLE collection_scope (
    entity_key TEXT PRIMARY KEY NOT NULL REFERENCES entity(entity_key),
    vendor_id TEXT NOT NULL REFERENCES vendor(vendor_id),
    category_id TEXT NOT NULL REFERENCES category(category_id)
);
CREATE TABLE alias (
    term TEXT NOT NULL,
    entity_key TEXT NOT NULL REFERENCES entity(entity_key),
    scope TEXT NOT NULL DEFAULT '*', -- '*' or category/{id}
    PRIMARY KEY (term, entity_key, scope)
);
CREATE TABLE offer (
    offer_id TEXT PRIMARY KEY NOT NULL,
    entity_key TEXT NOT NULL UNIQUE REFERENCES entity(entity_key),
    group_id TEXT,
    family_key TEXT REFERENCES entity(entity_key),
    vendor_id TEXT NOT NULL REFERENCES vendor(vendor_id),
    category_id TEXT NOT NULL REFERENCES category(category_id),
    name TEXT NOT NULL,
    sku_raw TEXT,
    sku_norm TEXT,
    url TEXT NOT NULL,
    description TEXT,
    sales_notes TEXT,
    available INTEGER NOT NULL CHECK (available IN (0,1)),
    currency TEXT NOT NULL CHECK (currency GLOB '[A-Z][A-Z][A-Z]'),
    price_minor INTEGER CHECK (price_minor >= 0),
    old_price_minor INTEGER CHECK (old_price_minor >= 0),
    product_type TEXT,
    base_model TEXT,
    version TEXT,
    edition TEXT,
    license_type TEXT CHECK (license_type IN ('new','renewal','upgrade')),
    term_months INTEGER CHECK (term_months > 0),
    device_count INTEGER CHECK (device_count > 0),
    seat_count INTEGER CHECK (seat_count > 0),
    device_min INTEGER CHECK (device_min > 0),
    device_max INTEGER CHECK (device_max > 0),
    seat_min INTEGER CHECK (seat_min > 0),
    seat_max INTEGER CHECK (seat_max > 0),
    min_order_qty INTEGER CHECK (min_order_qty > 0),
    price_unit TEXT CHECK (price_unit IN ('package','device','seat')),
    fact_sources_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(fact_sources_json)),
    CHECK (device_min <= device_max AND seat_min <= seat_max),
    CHECK (entity_key = 'offer/' || offer_id)
);
CREATE INDEX offer_by_sku ON offer(sku_norm);
CREATE INDEX offer_by_family ON offer(family_key);
CREATE INDEX offer_by_card ON offer(coalesce(family_key,entity_key));
CREATE INDEX offer_by_vendor_category ON offer(vendor_id, category_id, family_key);
CREATE INDEX offer_by_category ON offer(category_id, family_key);
-- Optional rows from the labelled synthetic merchandising dataset.
CREATE TABLE demo_signal (
    offer_id TEXT PRIMARY KEY NOT NULL REFERENCES offer(offer_id),
    popularity_bucket INTEGER NOT NULL CHECK (popularity_bucket BETWEEN 0 AND 2),
    margin_bucket INTEGER NOT NULL CHECK (margin_bucket BETWEEN 0 AND 2)
);
CREATE TABLE offer_param (
    offer_id TEXT NOT NULL REFERENCES offer(offer_id),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    name TEXT NOT NULL,
    value TEXT NOT NULL,
    field_key TEXT,
    value_norm TEXT,
    PRIMARY KEY (offer_id, ordinal)
);
CREATE INDEX param_facet ON offer_param(field_key, value_norm, offer_id);
CREATE TABLE relation (
    anchor_family TEXT NOT NULL REFERENCES entity(entity_key),
    candidate_family TEXT NOT NULL REFERENCES entity(entity_key),
    rule_id TEXT NOT NULL,
    rule_version TEXT NOT NULL,
    source_url TEXT NOT NULL,
    predicates_json TEXT NOT NULL CHECK (json_valid(predicates_json)),
    PRIMARY KEY (anchor_family, candidate_family, rule_id),
    CHECK (anchor_family <> candidate_family)
);
PRAGMA user_version = 1;
COMMIT;
