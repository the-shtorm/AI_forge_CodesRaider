package ru.codesraider.smartsearch.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import ru.codesraider.smartsearch.storage.Sqlite;

final class CatalogDatabase {
    private final Connection db;
    private final Map<String, PreparedStatement> statements = new HashMap<>();
    private final Set<String> vendors = new HashSet<>();
    final Map<String, String> categories = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Map<String, String> paths = new HashMap<>();
    final Set<String> families = new HashSet<>();
    private final PreparedStatement param;
    private int buffered;

    CatalogDatabase(Connection db) throws SQLException {
        this.db = db;
        try (var stmt = db.createStatement()) {
            stmt.execute("PRAGMA cache_size=-32000");
        }
        Sqlite.createV1(db, "catalog");
        db.setAutoCommit(false);
        param =
                prepared(
                        "INSERT INTO offer_param(offer_id,ordinal,name,value,field_key,value_norm)"
                            + " VALUES(?,?,?,?,?,?)");
    }

    private PreparedStatement prepared(String sql) throws SQLException {
        var statement = statements.get(sql);
        if (statement == null) {
            statement = db.prepareStatement(sql);
            statements.put(sql, statement);
        }
        return statement;
    }

    private void update(String sql, Object... fields) throws SQLException {
        var statement = prepared(sql);
        bind(statement, fields);
        statement.executeUpdate();
    }

    private static void bind(PreparedStatement statement, Object... fields) throws SQLException {
        for (int n = 0; n < fields.length; n++) statement.setObject(n + 1, fields[n]);
    }

    private void entity(String key, String kind, String id, String name, String url)
            throws SQLException {
        update(
                "INSERT INTO entity(entity_key,kind,id,name,canonical_url) VALUES(?,?,?,?,?)",
                key,
                kind,
                id,
                name,
                url);
    }

    void category(String id, String name, String parent) throws SQLException {
        entity("category/" + id, "category", id, name, null);
        update(
                "INSERT INTO category(category_id,parent_id,entity_key) VALUES(?,?,?)",
                id,
                parent,
                "category/" + id);
        categories.put(id, name);
        parents.put(id, parent);
    }

    String categoryPath(String id) {
        return paths.computeIfAbsent(
                id,
                key -> {
                    var parts = new java.util.ArrayDeque<String>();
                    var visited = new HashSet<String>();
                    for (String next = key; next != null; next = parents.get(next)) {
                        if (!visited.add(next) || !categories.containsKey(next))
                            throw new IllegalStateException("Invalid category tree: " + key);
                        parts.addFirst(categories.get(next));
                    }
                    return String.join(" / ", parts);
                });
    }

    void offer(FeedOffer o) throws SQLException {
        if (vendors.add(o.vendorId)) {
            entity(
                    "vendor/" + o.vendorId,
                    "vendor",
                    o.vendorId,
                    o.get("vendor"),
                    o.get("vendorUrl"));
            update("INSERT INTO vendor VALUES(?,?)", o.vendorId, "vendor/" + o.vendorId);
        }
        if (o.family != null && families.add(o.family)) {
            entity(o.family, "family", o.familyId, o.familyName, null);
        }
        entity("offer/" + o.id, "offer", o.id, o.get("name"), o.get("url"));
        update(
                """
                INSERT INTO offer(offer_id,entity_key,group_id,family_key,vendor_id,category_id,name,sku_raw,sku_norm,
                  url,description,image_url,sales_notes,available,currency,price_minor,old_price_minor,product_type,base_model,
                  version,edition,license_type,term_months,device_count,seat_count,seat_max,min_order_qty,price_unit,fact_sources_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)\
                """,
                o.id,
                "offer/" + o.id,
                o.group,
                o.family,
                o.vendorId,
                o.get("categoryId"),
                o.get("name"),
                o.get("vendorCode"),
                o.skuNorm,
                o.get("url"),
                o.get("description"),
                o.get("picture"),
                o.get("sales_notes"),
                o.available ? 1 : 0,
                o.currency,
                o.price,
                o.oldPrice,
                o.productType,
                o.baseModel,
                o.version,
                o.edition,
                o.licenseType,
                o.termMonths,
                o.deviceCount,
                o.seatCount,
                o.seatMax,
                o.minOrder,
                o.priceUnit,
                FeedOffer.JSON.writeValueAsString(o.facts));
        int i = 0;
        for (FeedOffer.Param p : o.params) {
            bind(
                    param,
                    o.id,
                    i++,
                    p.name(),
                    p.value(),
                    FeedOffer.paramKey(p.name()),
                    FeedOffer.normalizedValue(p.value()));
            param.addBatch();
            if (++buffered == 512) flush();
        }
    }

    private void flush() throws SQLException {
        if (buffered > 0) param.executeBatch();
        buffered = 0;
    }

    private void familyAlias(String id, String label, String term, String path)
            throws SQLException {
        String key = "family/" + id;
        if (!families.contains(key)) return;
        update(
                "UPDATE entity SET canonical_url=?,name=? WHERE entity_key=?",
                "https://store.softline.ru/" + path,
                label,
                key);
        alias(term, key);
    }

    private void alias(String term, String key) throws SQLException {
        update("INSERT OR IGNORE INTO alias(term,entity_key,scope) VALUES(?,?,'*')", term, key);
    }

    private void relation(String device, String cartridge, String url) throws SQLException {
        String prefix = "hardware/pantum/";
        String anchor = prefix + device.toLowerCase(java.util.Locale.ROOT);
        String candidate = prefix + cartridge.toLowerCase(java.util.Locale.ROOT);
        if (families.contains(anchor) && families.contains(candidate)) {
            String predicates =
                    FeedOffer.JSON.writeValueAsString(
                            java.util.List.of(
                                    Map.of(
                                            "field",
                                            "supported_device",
                                            "anchor_sku",
                                            device,
                                            "candidate_sku",
                                            cartridge,
                                            "vendor_id",
                                            "pantum")));
            update(
                    "INSERT INTO relation VALUES(?,?,?,?,?,?)",
                    anchor,
                    candidate,
                    "supported_device",
                    "1",
                    url,
                    predicates);
        }
    }

    void finish() throws SQLException {
        flush();
        familyAlias(
                "121231",
                "Astra Linux Special Edition",
                "astra linux",
                "astralinux/astra-linux-special-edition/");
        familyAlias(
                "391338",
                "Microsoft Office для дома и бизнеса 2024",
                "microsoft office для дома и бизнеса",
                "microsoft/microsoft-office-dlya-doma-i-biznesa-2024/");
        familyAlias(
                "3761",
                "Kaspersky Small Office Security",
                "kaspersky small office security",
                "kaspersky/kaspersky-small-office-security/");
        familyAlias("22268", "КриптоПро CSP", "крипто про csp 5.0", "kripto-pro/cryptopro-csp/");
        familyAlias("306539", "AnyDesk", "anydesk", "anydesk/anydesk/");
        familyAlias("46421", "СКАНЕР-ВС", "сканер-вс", "npo-echelon/eshelon-skaner-vs/");
        familyAlias(
                "437421",
                "MaxPatrol Endpoint Protection Platform",
                "max patrol",
                "positive-technologies/maxpatrol-endpoint-protection-platform/");
        familyAlias("165034", "Microsoft 365 Family", "365", "microsoft/microsoft-365-for-family/");
        if (vendors.contains("webinar")) alias("мтс", "vendor/webinar");
        if (vendors.contains("microsoft")) alias("микрософт", "vendor/microsoft");
        if (vendors.contains("cyberprotect") && categories.containsKey("1033")) {
            String key = "collection/cyberprotect-1033";
            entity(
                    key,
                    "collection",
                    "cyberprotect-1033",
                    "Кибер Бэкап",
                    "https://store.softline.ru/cyberprotect/?filter%5Bcategory%5D%5B%5D=1033");
            update("INSERT INTO collection_scope VALUES(?,?,?)", key, "cyberprotect", "1033");
            for (String term : java.util.List.of("кибер бэкап", "кибер бекап", "cyber backup", "кибер backup", "кибербэкап")) {
                alias(term, key);
            }
        }
        // Register spelling variants only when their destination exists in this feed.
        for (var entry : Map.of(
                "family/121231", List.of("астра линукс"),
                "family/391338", List.of("офис для дома и бизнеса"),
                "family/22268", List.of("крипто про csp", "криптопро csp"),
                "family/306539", List.of("энидеск"),
                "family/46421", List.of("сканер вс"),
                "family/437421", List.of("maxpatrol"),
                "family/165034", List.of("microsoft 365 family", "microsoft 365 для семьи"),
                "vendor/webinar", List.of("мтс линк")).entrySet()) {
            for (String term : entry.getValue())
                update("INSERT OR IGNORE INTO alias(term,entity_key,scope) SELECT ?,entity_key,'*' FROM entity WHERE entity_key=?",
                        term, entry.getKey());
        }
        relation(
                "P2500W",
                "PC-211P",
                "https://www.pantum.ru/products/laser-devices/monohromnyj-printer-p2500w/");
        relation(
                "BM1800W",
                "TL-C2318H",
                "https://www.pantum.ru/options-and-supplies/toner-kartridzh-tl-c2318h/");
        // Deferred parent FKs and offer FKs must all succeed before publishing anything.
        db.commit();
        for (var s : statements.values()) s.close();
    }
}
