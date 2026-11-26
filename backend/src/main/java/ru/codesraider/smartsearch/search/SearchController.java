package ru.codesraider.smartsearch.search;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/v1")
class SearchController {
    private final SearchEngine engine;

    SearchController(SearchEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/search")
    Map<String, Object> search(@RequestBody SearchEngine.Input input) throws IOException {
        return engine.search(input);
    }

    @GetMapping("/suggest")
    List<SearchEngine.Suggestion> suggest(
            @RequestParam String q, @RequestParam(required = false) String category_id) {
        return engine.suggest(q, category_id);
    }

    @GetMapping("/entities/{kind}/{id}")
    Map<String, Object> entity(
            @PathVariable String kind,
            @PathVariable String id,
            @RequestParam Map<String, String> filters) {
        return engine.entity(kind, id, filters);
    }

    record Explain(String search_id, String offer_id) {}

    @PostMapping("/explain")
    Map<String, Object> explain(@RequestBody Explain input) {
        SearchEngine.validate(input.search_id());
        SearchEngine.validate(input.offer_id());
        return engine.explain(input.search_id(), input.offer_id());
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        ArithmeticException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<?> invalid() {
        return problem(400, "INVALID_REQUEST", "Проверьте запрос и фильтры.");
    }

    @ExceptionHandler(SearchEngine.UnknownTarget.class)
    ResponseEntity<?> stale() {
        return problem(400, "UNKNOWN_TARGET", "Подсказка отсутствует в этом снимке.");
    }

    @ExceptionHandler(SearchEngine.Missing.class)
    ResponseEntity<?> absent() {
        return problem(404, "ENTITY_NOT_FOUND", "Карточка или цель отсутствует.");
    }

    private ResponseEntity<?> problem(int status, String code, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                        Map.of(
                                "type",
                                "about:blank",
                                "title",
                                detail,
                                "detail",
                                detail,
                                "status",
                                status,
                                "code",
                                code));
    }
}
