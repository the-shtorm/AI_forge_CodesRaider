-- EventStore initializes or migrates the database inside an IMMEDIATE transaction.
CREATE TABLE IF NOT EXISTS search_log (
    search_id TEXT PRIMARY KEY NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('demo','seed')),
    session_id TEXT,
    ts TEXT NOT NULL,
    q TEXT NOT NULL,
    normalized_q TEXT NOT NULL,
    selected_target_kind TEXT CHECK (selected_target_kind IN ('offer','family','vendor','category','collection')),
    selected_target_id TEXT,
    context_json TEXT NOT NULL CHECK (json_valid(context_json)),
    applied_filters_json TEXT NOT NULL CHECK (json_valid(applied_filters_json)),
    direct_count INTEGER NOT NULL CHECK (direct_count >= 0),
    shown_family_count INTEGER NOT NULL CHECK (shown_family_count BETWEEN 0 AND 4),
    shortfall_reason TEXT CHECK (shortfall_reason IN ('NO_DIRECT_MATCH','NOT_ENOUGH_VERIFIED_FAMILIES')),
    mode TEXT NOT NULL CHECK (mode IN ('lexical','hybrid')),
    index_version TEXT NOT NULL,
    elapsed_ms INTEGER NOT NULL CHECK (elapsed_ms >= 0),
    shown_json TEXT NOT NULL CHECK (json_valid(shown_json)),
    diagnostics_json TEXT NOT NULL CHECK (json_valid(diagnostics_json)),
    CHECK ((selected_target_kind IS NULL) = (selected_target_id IS NULL)),
    CHECK ((shown_family_count = 4 AND shortfall_reason IS NULL AND direct_count > 0)
        OR (shown_family_count = 0 AND direct_count = 0 AND shortfall_reason IS 'NO_DIRECT_MATCH')
        OR (shown_family_count BETWEEN 1 AND 3 AND direct_count > 0
            AND shortfall_reason IS 'NOT_ENOUGH_VERIFIED_FAMILIES'))
);
CREATE INDEX IF NOT EXISTS search_period ON search_log(source, ts);
CREATE INDEX IF NOT EXISTS search_by_session ON search_log(source, session_id, ts);
CREATE TABLE event (
    event_id TEXT PRIMARY KEY NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('demo','seed')),
    session_id TEXT NOT NULL,
    ts TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('impression','click','reformulation','add_to_cart','order')),
    search_id TEXT REFERENCES search_log(search_id),
    next_search_id TEXT REFERENCES search_log(search_id),
    offer_id TEXT,
    family_key TEXT,
    role TEXT CHECK (role IN ('direct','complement','alternative','related')),
    block TEXT CHECK (block IN ('top4','more_results','alternatives','upsell','details','navigation')),
    position INTEGER CHECK (position > 0),
    target_kind TEXT CHECK (target_kind IN ('offer','family','vendor','category','collection')),
    target_id TEXT,
    CHECK ((target_kind IS NULL) = (target_id IS NULL)),
    CHECK (CASE
        WHEN type IN ('impression','click') AND block IN ('top4','more_results','alternatives','upsell') THEN
            search_id IS NOT NULL AND offer_id IS NOT NULL AND family_key IS NOT NULL
            AND role IS NOT NULL AND position IS NOT NULL AND target_kind IS NULL AND next_search_id IS NULL
        WHEN type = 'click' AND block = 'details' THEN
            search_id IS NOT NULL AND offer_id IS NOT NULL AND family_key IS NOT NULL
            AND role IS NULL AND position IS NULL AND target_kind IS NULL AND next_search_id IS NULL
        WHEN type = 'click' AND block = 'navigation' THEN
            search_id IS NOT NULL AND target_kind IS NOT NULL AND offer_id IS NULL
            AND family_key IS NULL AND role IS NULL AND position IS NULL AND next_search_id IS NULL
        WHEN type = 'reformulation' THEN
            search_id IS NOT NULL AND next_search_id IS NOT NULL AND block IS NULL AND offer_id IS NULL
            AND family_key IS NULL AND role IS NULL AND position IS NULL AND target_kind IS NULL
        WHEN type IN ('add_to_cart','order') THEN
            offer_id IS NOT NULL AND next_search_id IS NULL AND block IS NULL AND family_key IS NULL
            AND role IS NULL AND position IS NULL AND target_kind IS NULL
        ELSE 0 END)
);
CREATE INDEX event_session ON event(source, session_id, ts);
CREATE INDEX event_attribution ON event(source, session_id, offer_id, type, ts, event_id);
CREATE INDEX event_search ON event(search_id);
PRAGMA user_version = 2;
