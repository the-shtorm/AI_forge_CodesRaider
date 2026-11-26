package ru.codesraider.smartsearch.search;

final class SearchFailure extends RuntimeException {
    final int status;
    final String code;

    SearchFailure(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    static SearchFailure invalid(String field) {
        return new SearchFailure(400, "INVALID_REQUEST", "Некорректное поле запроса: " + field);
    }

    static SearchFailure unavailable() {
        return new SearchFailure(
                503, "CATALOG_UNAVAILABLE", "Активный индекс каталога недоступен.");
    }
}
