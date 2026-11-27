package ru.codesraider.smartsearch.search;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/** The model selects typed operations. Catalog predicates and SKU facts execute them. */
record QueryPlan(String compiler, Purpose purpose, String text, List<Purpose> additionalPurposes, String join) {
    enum Purpose {
        WORDS("", ""),
        BACKUP("бэкап|бекап|резервн[а-я]* коп|копи[юяий]|дубликат", "документ|файл|диск"),
        WEBINAR("вебинар|видеовстреч|видеоконференц|онлайн[- ]встреч|лекци|презентаци.*слушател",
                "встреч|слушател|презентаци"),
        REMOTE_ACCESS("удаленн[а-я]* (?:доступ|администрир|управлен)|управля[а-я]* .*компьютер|подключ[а-я]* .*рабоч[а-я]* стол|экран[а-я]* другого"
                        + "|чуж[а-я]* (?:пк|компьютер).*по сети",
                "подключ|управля|из дома"),
        ANTIVIRUS("антивир|вредонос|заражен|троян|от вирус", "защит|обезопас"),
        SERVER("стоечн[а-я]* сервер|rack[- ]сервер|серверн[а-я]* стойк|сервер .*стойк|аппаратн[а-я]* сервер", "сервер"),
        SERVER_SOFTWARE("серверн[а-я]* программ|серверн[а-я]* лиценз", "сервер|установ"),
        PRINTER("принтер|печатать|распечата|для печати", "счет"),
        OCR("распозна[а-я]* текст|скан.* в (?:редактируемый )?текст|ocr", ""),
        VIDEO_EDITOR("видеоредактор|видеомонтаж|монтаж[а-я ]*видео|смонтир|редактир[а-я ]*видео", ""),
        PHOTO_EDITOR("фоторедактор|ретуш|отретуш|обработ[а-я]* (?:цифров[а-я]* )?фото|редактир[а-я ]*фото", ""),
        VIRTUALIZATION("виртуализац|гипервизор|виртуальн[а-я]* машин", ""),
        ARCHIVER("архиватор|сжа[а-я]* .*архив|распакова[а-я ]*архив|(?:zip|rar)\\b", ""),
        UPS("ибп\\b|бесперебой|отключени[а-я]* электричеств", ""),
        SWITCH("(?:соедин|объедин)[а-я]* .*компьютер.* проводн[а-я]* .*сети|коммутатор|свитч\\b", ""),
        MONITOR("монитор(?:ы|а|ов)?\\b|дисплей", ""),
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

    }

    private static final Pattern UNSUPPORTED_DETAIL = Pattern.compile("(?U)\\b(?:только|дешевле|дороже|рублей|дюйм|цветн|беспроводн|монохром|лазерн|"
                + "картридж|кабел|адаптер|креплен|кронштейн|подставк|чехол|ремонт|драйвер|расходник)");

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
            to install on a server. PRINTER means a printing device. OCR extracts text from scans.
            VIDEO_EDITOR and PHOTO_EDITOR edit video and still images. VIRTUALIZATION runs guest machines.
            ARCHIVER packs files. UPS supplies backup power, SWITCH connects network devices and MONITOR is a display. Use UNKNOWN for unrelated, ambiguous,
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

    static String canonical(String q, Set<String> aliases) {
        if (aliases.contains(q) || INTENTS.containsKey(q)) return q;
        // Recover visually identical Cyrillic characters inside a Latin product name.
        String folded = Pattern.compile("(?U)\\b[а-яa-z]+\\b").matcher(q).replaceAll(match -> {
            String word = match.group();
            if (word.chars().filter(ch -> ch >= 'a' && ch <= 'z').count() <= word.length() / 2) return word;
            return swap(word, "авекмнорстух", "abekmhopctyx");
        });
        if (aliases.contains(folded)) return folded;
        for (String v : List.of(swap(q, EN, RU), swap(q, RU, EN)))
            if (aliases.contains(v) || INTENTS.containsKey(v)) return v;
        if (q.length() < 5 || q.matches(".*\\d.*")) return q;
        var matches =
                aliases.stream()
                        .filter(a -> Math.abs(a.length() - q.length()) <= 1 && CatalogNames.oneEdit(a, q, false))
                        .toList();
        return matches.size() == 1 ? matches.getFirst() : q;
    }

    static QueryPlan words(String text) {
        return new QueryPlan("rules", Purpose.WORDS, text, List.of(), "all");
    }

    static QueryPlan compile(String normalized, Set<String> aliases, SearchCatalog db, ModelClient llm)
            throws SQLException {
        String exclusions = normalized.contains("чтобы") && Purpose.UPS.evidence(normalized) == 2
                ? normalized.replaceAll("(?U)\\bне выключал[а-я]*\\b", "") : normalized;
        if (Pattern.compile("(?U)\\b(не|без|кроме|исключая)\\b").matcher(exclusions).find())
            return new QueryPlan("rules", Purpose.UNKNOWN, normalized, List.of(), "all");
        Purpose purpose = INTENTS.get(normalized);
        if (purpose != null) return new QueryPlan("rules", purpose, normalized, List.of(), "all");
        boolean freeForm =
                normalized.length() <= 512
                        && normalized.replaceAll("(?U)\\b(?:rack|zip|rar|ocr|ethernet)\\b", "").matches("[а-я -]*")
                        && !UNSUPPORTED_DETAIL.matcher(normalized).find()
                        && aliases.stream().noneMatch(normalized::contains);
        String compiler = "rules";
        if (freeForm) {
            // Ground the instruction vocabulary in the request before asking the model to choose.
            // Specific capability words outrank ambiguous objects such as files or computers.
            int best =
                    Arrays.stream(Purpose.values())
                            .mapToInt(op -> op.evidence(normalized))
                            .max()
                            .orElse(0);
            if (best == 0 || db.mentionsVendor(normalized)) return words(normalized);
            List<Purpose> grounded =
                    Arrays.stream(Purpose.values())
                            .filter(op -> op.evidence(normalized) == best)
                            .toList();
            boolean compound =
                    Arrays.stream(normalized.split(" "))
                            .anyMatch(w -> w.equals("и") || w.equals("или"));
            // «Разослать приглашения и провести видеоконференцию» describes one
            // capability. A compound instruction needs evidence for two different capabilities.
            long capabilities = Arrays.stream(Purpose.values()).filter(op -> op.evidence(normalized) > 0).count();
            if ((!compound || capabilities == 1) && best == 2 && grounded.size() == 1)
                return new QueryPlan("rules", grounded.getFirst(), normalized, List.of(), "all");
            if (compound && best == 2) {
                var clauses = normalized.split(" (?:или|и) ");
                if (clauses.length == 2) {
                    List<Purpose> left = strongPurposes(clauses[0]), right = strongPurposes(clauses[1]);
                    if (left.size() == 1 && right.size() == 1 && !left.equals(right))
                        return new QueryPlan("rules", left.getFirst(), normalized, right,
                                normalized.contains(" или ") ? "any" : "all");
                }
            }
            if (!llm.configured() || normalized.split(" ").length < 3)
                return words(normalized);
            if (compound)
                grounded =
                        Arrays.stream(Purpose.values())
                                .filter(op -> op.evidence(normalized) > 0)
                                .toList();
            List<String> ops = new ArrayList<>(grounded.stream().map(Enum::name).toList());
            ops.add(Purpose.UNKNOWN.name());
            var program = llm.compile(SYSTEM, normalized, ops);
            if (program != null && !program.find().contains(Purpose.UNKNOWN)
                    && (best == 2 || program.find().size() > 1
                        || !program.find().contains(Purpose.BACKUP))) {
                return new QueryPlan(
                        "llm",
                        program.find().getFirst(),
                        normalized,
                        program.find().subList(1, program.find().size()),
                        program.join());
            }
            compiler = program == null ? "llm_fallback" : "llm_abstained";
        }
        return new QueryPlan(compiler, Purpose.WORDS, normalized, List.of(), "all");
    }

    private static List<Purpose> strongPurposes(String clause) {
        return Arrays.stream(Purpose.values()).filter(p -> p.evidence(clause) == 2).toList();
    }

    boolean isSinglePurpose() {
        return purpose != Purpose.UNKNOWN && purpose != Purpose.WORDS && additionalPurposes.isEmpty();
    }

    Map<String, Object> trace(Requirements filters) {
        return Map.of("compiler", compiler, "purpose", purpose.name(), "additional_purposes", additionalPurposes,
                "join", join, "terms", text, "excluded_versions", filters.excludedVersions,
                "excluded_licenses", filters.excludedLicenses);
    }
}
