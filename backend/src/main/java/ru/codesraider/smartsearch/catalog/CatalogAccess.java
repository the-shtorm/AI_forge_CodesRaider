package ru.codesraider.smartsearch.catalog;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ru.codesraider.smartsearch.web.ApiProblem;

@Component
public final class CatalogAccess {
    private final CatalogSnapshot snapshot;

    CatalogAccess(@Value("${search.snapshots-dir:data/snapshots}") String directory) {
        Path root = Path.of(directory);
        Path pointer = root.resolve("active");
        CatalogSnapshot loaded = null;
        if (Files.exists(pointer)) {
            try {
                String version = Files.readString(pointer).strip();
                loaded = CatalogSnapshot.open(root, version);
                if (loaded == null) throw new IllegalStateException("Inconsistent catalog snapshot " + version);
            } catch (Exception exception) {
                LoggerFactory.getLogger(CatalogAccess.class).error("Unable to open the active catalog", exception);
            }
        }
        snapshot = loaded;
    }

    public CatalogSnapshot require() {
        if (snapshot == null) throw ApiProblem.catalog();
        return snapshot;
    }

    public String version() {
        return snapshot == null ? null : snapshot.version();
    }

    @PreDestroy
    void close() throws java.io.IOException {
        if (snapshot != null) snapshot.close();
    }
}
