package ru.codesraider.smartsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ru.codesraider.smartsearch.catalog.CatalogImporter;

import ru.codesraider.smartsearch.catalog.CatalogImporter;

@SpringBootApplication
public class SmartSearchApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "import".equals(args[0])) {
            CatalogImporter.run(args);
            return;
        }
        SpringApplication.run(SmartSearchApplication.class, args);
    }
}
