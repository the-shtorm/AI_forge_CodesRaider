package ru.codesraider.smartsearch.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CuratedOffers {
    private CuratedOffers() {}

    public static List<String> configured() {
        var stream = Objects.requireNonNull(CuratedOffers.class.getResourceAsStream("/search/curated-offers.txt"));
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().map(line -> line.split("#", 2)[0].strip())
                    .filter(id -> !id.isEmpty()).distinct().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static Map<String, String> skipped(Connection db, List<String> ids) throws SQLException {
        var skipped = new LinkedHashMap<String, String>();
        try (var ps = db.prepareStatement("SELECT available,price_minor,currency FROM offer WHERE offer_id=?")) {
            for (String id : ids) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) skipped.put(id, "missing");
                    else if (rs.getInt(1) != 1) skipped.put(id, "unavailable");
                    else if (rs.getLong(2) <= 0) skipped.put(id, "unpriced");
                    else if (!"RUB".equals(rs.getString(3))) skipped.put(id, "non_default_currency");
                }
            }
        }
        return skipped;
    }
}
