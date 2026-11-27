package ru.codesraider.smartsearch.search;

/** A task that the catalog can verify on individual offers before grouping and faceting. */
interface CatalogTask {
    void constrain(SearchCatalog.Where where);

    static CatalogTask parse(String query) {
        CatalogTask task = InventoryIntent.parse(query);
        if (task == null) task = EquipmentIntent.parse(query);
        if (task == null) task = SoftwareIntent.parse(query);
        return task;
    }
}
