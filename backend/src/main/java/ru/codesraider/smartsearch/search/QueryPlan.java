package ru.codesraider.smartsearch.search;

import static ru.codesraider.smartsearch.search.Product.norm;

import java.util.*;
import java.util.regex.*;

record QueryPlan(
        String compiler,
        Catalog.Target scope,
        Purpose purpose,
        String text,
        List<String> models,
        String unknownModel,
        List<String> printerFeatures,
        Requirements filters,
        List<Purpose> additionalPurposes,
        String join) {
    private QueryPlan(
            String compiler,
            Catalog.Target scope,
            Purpose purpose,
            String text,
            List<String> models,
            String unknownModel,
            List<String> printerFeatures,
            Requirements filters) {
        this(
                compiler,
                scope,
                purpose,
                text,
                models,
                unknownModel,
                printerFeatures,
                filters,
                List.of(),
                "all");
    }

    boolean matchesPurpose(Product p, Catalog catalog) {
        boolean first = purpose.matches(p, catalog);
        if (additionalPurposes.isEmpty()) return first;
        return join.equals("any")
                ? first || additionalPurposes.stream().anyMatch(op -> op.matches(p, catalog))
                : first && additionalPurposes.stream().allMatch(op -> op.matches(p, catalog));
    }

    enum Purpose {
        WORDS("", ""),
        BACKUP("бэкап|бекап|резерв|копи[юяий]|восстанов", "документ|файл|фотограф|диск"),
        WEBINAR(
                "вебинар|видеовстреч|видеоконференц|лекци",
                "встреч|собрать|слушател|интернет|презентаци"),
        REMOTE_ACCESS(
                "удаленн[а-я]* доступ|рабоч[а-я]* стол",
                "удален|рабоч|подключ|компьютер|сети|оператор"),
        ANTIVIRUS("антивир|вредонос|заражен", "защит|компьютер|файл"),
        SERVER("стойк|серверн[а-я]* стойк", "сервер|машин"),
        SERVER_SOFTWARE("серверн[а-я]* программ|серверн[а-я]* лиценз", "сервер|установ"),
        PRINTER("принтер|печат|бумаг", "документ|фотограф|счет"),
        UNKNOWN("", "");
        final Pattern strong, weak;

        Purpose(String strong, String weak) {
            this.strong = prefixes(strong);
            this.weak = prefixes(weak);
        }

        private static Pattern prefixes(String words) {
            return Pattern.compile(words.isEmpty() ? "(?!)" : "(?U)\\b(?:" + words + ")");
        }

        int evidence(String query) {
            if (strong.matcher(query).find()) return 2;
            return weak.matcher(query).find() ? 1 : 0;
        }

        boolean matches(Product p, Catalog catalog) {
            String category = norm(catalog.categories.get(p.category()));
            return switch (this) {
                case WORDS -> true;
                case UNKNOWN -> false;
                case BACKUP ->
                        p.category().equals("1033") || category.contains("резервное копирование");
                case WEBINAR -> category.contains("вебинар") || category.contains("видеоконференц");
                case REMOTE_ACCESS ->
                        category.contains("удаленный доступ")
                                || category.contains("удаленное администрирование");
                case ANTIVIRUS -> category.contains("антивирус");
                case SERVER ->
                        Set.of("86563", "87268").contains(p.category())
                                || category.equals("rack-серверы")
                                || category.equals("микросерверы");
                case SERVER_SOFTWARE ->
                        !p.group().isEmpty()
                                && norm(p.name()).matches("(?U).*\\b(сервер[а-я]*|server)\\b.*");
                case PRINTER -> category.contains("принтер") || category.contains("мфу");
            };
        }
    }

    static final Map<String, Purpose> INTENTS =
            Map.ofEntries(
                    Map.entry("сервер", Purpose.SERVER),
                    Map.entry("серверы", Purpose.SERVER),
                    Map.entry("стойковый сервер", Purpose.SERVER),
                    Map.entry("по для сервера", Purpose.SERVER_SOFTWARE),
                    Map.entry("софт для сервера", Purpose.SERVER_SOFTWARE),
                    Map.entry("антивирь", Purpose.ANTIVIRUS),
                    Map.entry("антивирус", Purpose.ANTIVIRUS),
                    Map.entry("резервное копирование", Purpose.BACKUP),
                    Map.entry("бэкап", Purpose.BACKUP),
                    Map.entry("удаленный доступ", Purpose.REMOTE_ACCESS),
                    Map.entry("вебинар", Purpose.WEBINAR),
                    Map.entry("принтер", Purpose.PRINTER));
    private static final String SYSTEM =
            """
            Compile a Russian shop request into one catalog search instruction. BACKUP means creating or
            restoring copies of files. WEBINAR means holding an online meeting, lesson or video conference.
            REMOTE_ACCESS means operating another computer remotely. ANTIVIRUS means protecting endpoints
            from malicious software. SERVER means physical server hardware. SERVER_SOFTWARE means software
            to install on a server. PRINTER means a printing device. Use UNKNOWN for unrelated, ambiguous,
            unsupported or unrepresentable requirements.
            """;
    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.";
    private static final String RU = "йцукенгшщзхъфывапролджэячсмитьбю";

    private static String swap(String s, String from, String to) {
        var out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            int i = from.indexOf(ch);
            out.append(i < 0 ? ch : to.charAt(i));
        }
        return out.toString();
    }

    private static String canonical(String q, Catalog c) {
        if (c.aliases.containsKey(q) || INTENTS.containsKey(q)) return q;
        for (String v : List.of(swap(q, EN, RU), swap(q, RU, EN)))
            if (c.aliases.containsKey(v) || INTENTS.containsKey(v)) return v;
        if (q.length() < 5 || q.matches(".*\\d.*")) return q;
        var matches =
                c.aliases.keySet().stream()
                        .filter(a -> Math.abs(a.length() - q.length()) <= 1 && oneEdit(a, q))
                        .toList();
        return matches.size() == 1 ? matches.getFirst() : q;
    }

    private static boolean oneEdit(String a, String b) {
        if (a.length() > b.length()) return oneEdit(b, a);
        int i = 0;
        while (i < a.length() && a.charAt(i) == b.charAt(i)) i++;
        if (i == a.length()) return b.length() - i <= 1;
        if (a.length() != b.length()) return a.substring(i).equals(b.substring(i + 1));
        return a.substring(i + 1).equals(b.substring(i + 1))
                || i + 1 < a.length()
                        && a.charAt(i) == b.charAt(i + 1)
                        && a.charAt(i + 1) == b.charAt(i)
                        && a.substring(i + 2).equals(b.substring(i + 2));
    }

    static QueryPlan compile(
            String query, Catalog.Target selected, Requirements f, Catalog c, ModelClient llm) {
        String q = norm(query.replaceAll("(?<=\\d),(?=\\d)", "."));
        var exact = c.bySku.getOrDefault(Product.code(query), List.of());
        if (selected == null && c.byId.containsKey(query.strip()))
            selected = c.target("offer", query.strip());
        if (selected == null && exact.size() == 1)
            selected = c.target("offer", exact.getFirst().id());
        if (selected != null)
            return new QueryPlan("rules", selected, Purpose.WORDS, "", List.of(), "", List.of(), f);
        if (!exact.isEmpty())
            return new QueryPlan(
                    "rules", null, Purpose.WORDS, "", List.of(Product.code(q)), "", List.of(), f);

        String terms = canonical(f.extract(q), c);
        if (Pattern.compile("(?U)\\b(не|без|кроме|исключая)\\b").matcher(terms).find())
            return new QueryPlan("rules", null, Purpose.UNKNOWN, "", List.of(), "", List.of(), f);
        Catalog.Target scope = c.aliases.get(terms);
        if (scope != null)
            return new QueryPlan("rules", scope, Purpose.WORDS, "", List.of(), "", List.of(), f);
        Purpose purpose = INTENTS.get(terms);
        if (purpose != null)
            return new QueryPlan("rules", null, purpose, "", List.of(), "", List.of(), f);

        // A vendor and the actual product name can qualify an exact code, including consumables.
        // Extra tokens (for example, "для" or a second model) still go through the full planner.
        List<String> words = Arrays.asList(terms.split(" "));
        for (String word : words) {
            var products = c.bySku.getOrDefault(Product.code(word), List.of());
            if (products.size() != 1) continue;
            Product product = products.getFirst();
            var nameWords =
                    new HashSet<>(
                            Arrays.asList(
                                    norm(product.name() + " " + product.vendor()).split(" ")));
            nameWords.removeAll(Set.of("для", "или", "без", "вместо"));
            nameWords.addAll(List.of(word, "купить", "покажи", "найди", "нужен", "артикул"));
            if (nameWords.containsAll(words))
                return new QueryPlan(
                        "rules",
                        c.target("offer", product.id()),
                        Purpose.WORDS,
                        "",
                        List.of(),
                        "",
                        List.of(),
                        f);
        }

        // Retain model identifiers and material constraints even when the requested model is
        // missing.
        if (terms.contains("pantum") && (terms.matches(".*\\d.*") || terms.contains("принтер"))) {
            List<String> codes = new ArrayList<>(), features = new ArrayList<>();
            String rest =
                    Requirements.take(
                            terms,
                            "[a-z]+-?\\d+[a-z\\d-]*",
                            m -> codes.add(Product.code(m.group())));
            rest = Requirements.take(rest, "лазерн[а-я]+", m -> features.add("лазерная"));
            rest = Requirements.take(rest, "монохромн[а-я]+", m -> features.add("монохромная"));
            rest = Requirements.take(rest, "a4", m -> features.add("a4"));
            if (codes.remove("a4")) features.add("a4");
            rest =
                    Requirements.take(
                            rest,
                            "покажи|нужен|нужна|устройств[а-я]*|pantum|принтер[а-я]*|мфу|или",
                            m -> {});
            f.require("vendor_id", "pantum");
            String missing =
                    codes.stream()
                            .filter(code -> !c.bySku.containsKey(code))
                            .findFirst()
                            .orElse("");
            return new QueryPlan(
                    "rules",
                    null,
                    Purpose.PRINTER,
                    rest.strip(),
                    List.copyOf(codes),
                    missing,
                    features,
                    f);
        }
        String normalized =
                Requirements.take(terms, "покажи|найди|мне|нужен|нужна|купить|пожалуйста", m -> {})
                        .strip()
                        .replaceAll(" +", " ");
        String name = canonical(normalized, c);
        scope = c.aliases.get(name);
        if (scope != null || INTENTS.containsKey(name))
            return new QueryPlan(
                    "rules",
                    scope,
                    INTENTS.getOrDefault(name, Purpose.WORDS),
                    "",
                    List.of(),
                    "",
                    List.of(),
                    f);
        // The small purpose vocabulary cannot encode arbitrary models, negations, ranges or brand
        // names.
        boolean freeForm =
                normalized.split(" ").length >= 3
                        && normalized.matches("[а-я -]+")
                        && !Pattern.compile(
                                        "(?U)\\b(только|дешевле|дороже|рублей|дюймов|цветной|цветная|беспроводной|монохромный|лазерный)\\b")
                                .matcher(normalized)
                                .find()
                        && c.aliases.keySet().stream().noneMatch(normalized::contains)
                        && !c.mentionsVendor(normalized);
        String compiler = "rules";
        if (freeForm) {
            // Ground the instruction vocabulary in the request before asking the model to choose.
            // Specific capability words outrank ambiguous objects such as files or computers.
            int best =
                    Arrays.stream(Purpose.values())
                            .mapToInt(op -> op.evidence(normalized))
                            .max()
                            .orElse(0);
            List<Purpose> grounded =
                    Arrays.stream(Purpose.values())
                            .filter(op -> best > 0 && op.evidence(normalized) == best)
                            .toList();
            boolean compound =
                    Arrays.stream(normalized.split(" "))
                            .anyMatch(w -> w.equals("и") || w.equals("или"));
            if (!compound && best == 2 && grounded.size() == 1)
                return new QueryPlan(
                        "rules", null, grounded.getFirst(), "", List.of(), "", List.of(), f);
            if (grounded.isEmpty() || !llm.configured())
                return new QueryPlan(
                        "rules", null, Purpose.WORDS, normalized, List.of(), "", List.of(), f);
            if (compound)
                grounded =
                        Arrays.stream(Purpose.values())
                                .filter(op -> op.evidence(normalized) > 0)
                                .toList();
            List<String> ops = new ArrayList<>(grounded.stream().map(Enum::name).toList());
            ops.add(Purpose.UNKNOWN.name());
            var program = llm.compile(SYSTEM, normalized, ops);
            if (program != null && !program.find().contains(Purpose.UNKNOWN)) {
                return new QueryPlan(
                        "llm",
                        null,
                        program.find().getFirst(),
                        "",
                        List.of(),
                        "",
                        List.of(),
                        f,
                        program.find().subList(1, program.find().size()),
                        program.join());
            }
            compiler = program == null ? "llm_fallback" : "llm_abstained";
        }
        return new QueryPlan(
                compiler, null, Purpose.WORDS, normalized, List.of(), "", List.of(), f);
    }

    boolean supportsPrinter(Product p) {
        return printerFeatures.stream()
                .allMatch(
                        f ->
                                switch (f) {
                                    case "a4" ->
                                            p.values(
                                                            "Формат",
                                                            "Максимальный формат",
                                                            "Формат бумаги")
                                                    .contains("a4");
                                    case "лазерная" ->
                                            p.values("Технология печати", "Тип печати").stream()
                                                    .anyMatch(s -> s.startsWith("лазерн"));
                                    case "монохромная" ->
                                            p.values("Цветность", "Цветность печати").stream()
                                                    .anyMatch(
                                                            s ->
                                                                    s.startsWith("монохром")
                                                                            || s.equals(
                                                                                    "черно-белая"));
                                    default -> false;
                                });
    }

    Map<String, Object> trace() {
        var m = new LinkedHashMap<String, Object>();
        m.put("compiler", compiler);
        m.put("purpose", purpose.name());
        m.put("terms", text);
        m.put("scope", scope);
        m.put("additional_purposes", additionalPurposes);
        m.put("join", join);
        m.put("models", models);
        m.put("printer_features", printerFeatures);
        m.put("filters", filters.values);
        m.put("excluded_licenses", filters.excludedLicenses);
        m.put("excluded_versions", filters.excludedVersions);
        m.put("conflict", filters.conflict);
        return m;
    }
}
