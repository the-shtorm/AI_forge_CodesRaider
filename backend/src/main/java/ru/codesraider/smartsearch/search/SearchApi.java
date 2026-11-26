package ru.codesraider.smartsearch.search;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class SearchApi {
    public static final String KINDS = "offer|family|vendor|category|collection";
    public static final String NON_BLANK = "(?Us).*\\S.*";

    private SearchApi() {}

    public record SearchRequest(@NotNull @Pattern(regexp = NON_BLANK) String q,
                                @Pattern(regexp = NON_BLANK) String sessionId, @Valid Context context,
                                @Valid Filters filters, @Valid SelectedTarget selectedTarget,
                                @Min(4) @Max(50) Integer limit) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Context(@Pattern(regexp = NON_BLANK) String categoryId,
                          @Pattern(regexp = NON_BLANK) String segment,
                          @Pattern(regexp = NON_BLANK) String profileId) {}

    public record SelectedTarget(@NotNull @Pattern(regexp = KINDS) String kind,
                                 @NotNull @Pattern(regexp = NON_BLANK) String targetId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filters(@Pattern(regexp = NON_BLANK) String categoryId,
                          @Pattern(regexp = NON_BLANK) String vendorId,
                          @Pattern(regexp = NON_BLANK) String platform,
                          @Pattern(regexp = NON_BLANK) String version,
                          @Pattern(regexp = "new|renewal|upgrade") String licenseType,
                          @Positive Long termMonths, @Positive Long deviceCount, @Positive Long seatCount,
                          Boolean availableOnly, @PositiveOrZero Long maxPriceMinor,
                          @Pattern(regexp = "[A-Z]{3}") String currency) {
        public static Filters empty() {
            return new Filters(null, null, null, null, null, null, null, null, null, null, null);
        }

        Filters withScope(String category, String vendor) {
            return new Filters(categoryId == null ? category : categoryId, vendorId == null ? vendor : vendorId,
                    platform, version, licenseType, termMonths, deviceCount, seatCount,
                    Boolean.TRUE.equals(availableOnly), maxPriceMinor, currency == null ? "RUB" : currency);
        }
    }

    public record Target(String kind, String id, String url) {}
    public record Suggestion(String kind, String targetId, String label) {}
    public record Entity(String kind, String id, String name, String url, List<Offer> offers, List<Target> children) {}

    public record Offer(String offerId, String sku, String name, String vendor, String url,
                        boolean available, Long priceMinor, String currency, String offerTier,
                        @JsonInclude(JsonInclude.Include.NON_NULL) Long oldPriceMinor,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, List<String>> attributes) {}

    public record Check(String field, String anchorValue, String candidateValue, String sourceUrl, String result) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Card(@JsonUnwrapped Offer offer, String familyKey, List<String> reasonCodes, String role,
                       String anchorOfferId, List<Check> checks, List<String> differences) {}

    public record SearchResponse(String searchId, String indexVersion, String mode, String normalizedQ,
                                 Filters appliedFilters, Target primaryTarget, int directCount, List<Card> top4,
                                 List<Card> moreResults, List<Card> alternatives, String shortfallReason) {}
}
