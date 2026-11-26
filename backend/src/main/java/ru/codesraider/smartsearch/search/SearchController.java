package ru.codesraider.smartsearch.search;

import java.util.List;
import java.util.Map;

import jakarta.validation.Validator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.codesraider.smartsearch.web.ApiProblem;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static ru.codesraider.smartsearch.search.SearchApi.*;

@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class SearchController {
    private final SearchService service;
    private final Validator validator;
    private final JsonMapper json;

    SearchController(SearchService service, Validator validator, JsonMapper json) {
        this.service = service;
        this.validator = validator;
        this.json = json;
    }

    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    SearchResponse search(@RequestBody JsonNode body) {
        if (!body.isObject()) throw ApiProblem.invalid("Передайте объект поискового запроса.");
        var filters = body.path("filters");
        rejectNull(body, "limit");
        rejectNull(body, "filters");
        rejectNull(filters, "available_only");
        rejectNull(filters, "currency");
        SearchRequest request;
        try {
            request = json.treeToValue(body, SearchRequest.class);
        } catch (JacksonException exception) {
            throw ApiProblem.invalid("Некорректное поле поискового запроса.");
        }
        return service.search(validate(request));
    }

    @GetMapping("/suggest")
    List<Suggestion> suggest(@RequestParam("q") String text,
                             @RequestParam(name = "category_id", required = false) String category) {
        if (!text.matches(NON_BLANK) || (category != null && !category.matches(NON_BLANK))) {
            throw ApiProblem.invalid("Укажите непустой префикс и, при необходимости, ID категории.");
        }
        return service.suggest(text, category);
    }

    @GetMapping("/entities/{kind}/{id}")
    Entity entity(@PathVariable("kind") String kind, @PathVariable("id") String id,
                  @RequestParam Map<String, String> params) {
        if (!kind.matches(KINDS) || !id.matches(NON_BLANK)) {
            throw ApiProblem.invalid("Некорректный вид или ID сущности.");
        }
        var filters = new Filters(params.get("category_id"), params.get("vendor_id"), params.get("platform"),
                params.get("version"), params.get("license_type"), integer(params, "term_months"),
                integer(params, "device_count"), integer(params, "seat_count"), bool(params.get("available_only")),
                integer(params, "max_price_minor"), params.get("currency"));
        return service.entity(kind, id, validate(filters));
    }

    private <T> T validate(T value) {
        var violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String field = violations.iterator().next().getPropertyPath().toString();
            throw ApiProblem.invalid("Некорректное значение поля " + field + ".");
        }
        return value;
    }

    private static void rejectNull(JsonNode node, String name) {
        if (node.path(name).isNull()) throw ApiProblem.invalid("Поле " + name + " не может быть пустым.");
    }

    private static Long integer(Map<String, String> params, String name) {
        var value = params.get(name);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw ApiProblem.invalid("Фильтр " + name + " должен быть целым числом.");
        }
    }

    private static Boolean bool(String value) {
        if (value == null) return null;
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw ApiProblem.invalid("Фильтр available_only должен быть true или false.");
        };
    }
}
