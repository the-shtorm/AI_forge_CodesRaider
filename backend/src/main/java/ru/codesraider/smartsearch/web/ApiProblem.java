package ru.codesraider.smartsearch.web;

import org.springframework.http.HttpStatus;

public final class ApiProblem extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiProblem(HttpStatus status, String code, String detail) {
        this(status, code, detail, null);
    }

    private ApiProblem(HttpStatus status, String code, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiProblem invalid(String detail) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail);
    }

    public static ApiProblem catalog() {
        return catalog(null);
    }

    public static ApiProblem catalog(Throwable cause) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE",
                "Активный снимок каталога недоступен.", cause);
    }

    public static ApiProblem events(Throwable cause) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "EVENT_STORE_UNAVAILABLE",
                "Журнал событий временно недоступен.", cause);
    }

    public static ApiProblem searchJournal(Throwable cause) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "JOURNAL_UNAVAILABLE",
                "Журнал поиска временно недоступен.", cause);
    }
}
