package ru.codesraider.smartsearch.search;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class CatalogNamesTest {
    @Test
    void correctsWordsButKeepsEveryCharacterOfTheRequestedModel() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:");
             var sql = db.createStatement()) {
            sql.execute("CREATE TABLE offer(name TEXT, family_key TEXT)");
            sql.execute("INSERT INTO offer VALUES ('PANTUM P2506W','printer'), ('Photo Studio 2024','studio')");
            var names = new CatalogNames(db);
            assertEquals("printer", names.family("pantum p2506w"));
            assertEquals("printer", names.family("pamtum p2506w"));
            assertEquals("studio", names.family("photo stduio 2024"));
            assertNull(names.family("pantum p2506x"));
            assertNull(names.family("pantum p2506"));
            assertNull(names.family("pantum p2506wx"));
            assertNull(names.family("photo studio 2023"));
        }
    }
}
