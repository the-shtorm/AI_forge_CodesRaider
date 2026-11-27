package ru.codesraider.smartsearch.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.codesraider.smartsearch.modelclient.EncoderClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

class CatalogImporterTest {
    private FeedOffer crypto(String title, String... versions) {
        var offer = new FeedOffer("fixture", "22268", true);
        offer.fields.putAll(
                java.util.Map.of(
                        "name",
                        title,
                        "url",
                        "https://store.softline.ru/kripto-pro/fixture/",
                        "vendor",
                        "КриптоПро",
                        "vendorUrl",
                        "https://store.softline.ru/kripto-pro/",
                        "categoryId",
                        "1",
                        "currencyId",
                        "RUR"));
        for (String value : versions) offer.params.add(new FeedOffer.Param("Версия", value));
        offer.finish();
        return offer;
    }

    @Test
    void ownCspVersionCanBeProvenByTitleOnlyWhenEvidenceAgrees() {
        String unknown = "другое или отсутствует";
        assertEquals("5.0", crypto("КриптоПро CSP 5.0: серверная лицензия СКЗИ", unknown).version);
        assertEquals("5.0", crypto("КриптоПро CSP версии 5.0 (Номер ЕРРП: 4332)", unknown).version);
        assertEquals(
                "4.0",
                crypto("КриптоПро CSP версии 4.0, поставка на одном сервере", unknown).version);
        assertNull(crypto("КриптоПро CSP версии 5.0, переход с версии 4.0", unknown).version);
        assertNull(crypto("КриптоПро CSP для Astra Linux версии 1.8", unknown).version);
        assertNull(crypto("КриптоПро CSP 5.0", "4.0", unknown).version);
    }

    @TempDir Path temp;

    @Test
    void failedVectorBuildAndRepeatedImportKeepCompletedSnapshot() throws Exception {
        Path source = temp.resolve("feed.xml");
        Path snapshots = temp.resolve("snapshots");
        Files.writeString(
                source,
                """
                <!DOCTYPE yml_catalog SYSTEM "file:///this-dtd-must-never-be-read">
                <yml_catalog date="2026-11-25"><shop><categories><category id="1">Office</category></categories>
                <offers><offer id="42" group_id="7" available="true"><name>Editor</name>
                <url>https://example.org/editor</url><vendor>Example</vendor><vendorUrl>https://example.org/example/</vendorUrl>
                <categoryId>1</categoryId><price>123.45</price><currencyId>RUR</currencyId>
                <param name="Версия">5.1</param><param name="Язык">ru</param><param name="Язык">en</param>
                </offer></offers></shop></yml_catalog>\
                """);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ready",
                exchange -> {
                    byte[] data =
                            "{\"ready\":true,\"profile_id\":\"test\",\"dimension\":384}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(data);
                    }
                });
        server.createContext(
                "/internal/v1/embeddings",
                exchange -> {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                });
        server.start();
        try (var client = new EncoderClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            CatalogImporter.importSnapshot(source, snapshots, false, client);
            String version = Files.readString(snapshots.resolve("active")).strip();
            CatalogImporter.importSnapshot(source, snapshots, false, client);
            assertThrows(
                    IOException.class,
                    () -> CatalogImporter.importSnapshot(source, snapshots, true, client));
            assertEquals(version, Files.readString(snapshots.resolve("active")).strip());
            try (var children = Files.list(snapshots)) {
                assertEquals(1, children.filter(Files::isDirectory).count());
            }
            try (var db =
                            DriverManager.getConnection(
                                    "jdbc:sqlite:"
                                            + snapshots.resolve(version).resolve("catalog.db"));
                    var stmt = db.createStatement();
                    var row =
                            stmt.executeQuery(
                                    "SELECT price_minor,version,currency,(SELECT count(*) FROM"
                                            + " offer_param) FROM offer")) {
                assertTrue(row.next());
                assertEquals(12345, row.getInt(1));
                assertEquals("5.1", row.getString(2));
                assertEquals("RUB", row.getString(3));
                assertEquals(3, row.getInt(4));
            }
        } finally {
            server.stop(0);
        }
    }
}
