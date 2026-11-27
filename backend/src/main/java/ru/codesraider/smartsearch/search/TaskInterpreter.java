package ru.codesraider.smartsearch.search;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static ru.codesraider.smartsearch.search.QueryPlan.Purpose;

/** Proposes a catalog operation for an empty text search, then asks Qwen to verify the user's goal. */
final class TaskInterpreter {
    private record Topic(Purpose purpose, String tool, Pattern subject, Pattern context, Set<String> names) {
        Topic(Purpose purpose, String tool, String subject, String context, String... names) {
            this(purpose, tool, prefixes(subject), prefixes(context), Set.of(names));
        }
    }

    private static Pattern prefixes(String stems) {
        return Pattern.compile("(?U)\\b(?:" + stems + ")");
    }

    // Require evidence for both the domain and the requested change. The model judges the whole
    // phrase: working with video, for example, does not by itself imply video editing.
    private static final List<Topic> TOPICS = List.of(
            new Topic(Purpose.VIDEO_EDITOR, "video editing software",
                    "видео|ролик|клип|фильм|кадр|титр", "скле|соедин|собра|объедин|обрез|вырез|нарез|убра|добав|налож|смен|ускор|редакт|монтаж",
                    "video editor", "video editing software", "video editing tool"),
            new Topic(Purpose.OCR, "optical character recognition software",
                    "изображ|картин|фото|сним|скан|отскан|страниц|бума[гж]", "текст|букв|слов|надпис|символ|копиров|перепеч|набор|реквизит",
                    "ocr", "ocr software", "optical character recognition", "optical character recognition software", "image to text converter", "image-to-text converter"),
            new Topic(Purpose.UPS, "an uninterruptible power supply",
                    "питани|запита|электр|свет|напряжени|розетк|ток|батаре", "автоном|перебо|авари|пропад|выключ|отключ|скачк|отказ|погас|исчез",
                    "ups", "uninterruptible power supply", "uninterruptible power supply (ups)"),
            new Topic(Purpose.PHOTO_EDITOR, "photo editing software",
                    "фото|сним|изображ|портрет|картин", "исправ|цвет|освет|светл|выровн|обрез|удал|убра|замен|фон|ярк|ретуш",
                    "photo editor", "photo editing software", "image editor", "image editing software"),
            new Topic(Purpose.BACKUP, "backup software",
                    "данн|файл|баз|диск|каталог|папк|сервер|хранилищ", "запасн|втор|копи|дубли|сохран|расписани|потер",
                    "backup software", "backup tool", "backup manager", "data backup software"),
            new Topic(Purpose.ARCHIVER, "a file archiver",
                    "папк|файл|архив|пакет", "упаков|упакова|распаков|сжа|разж",
                    "file archiver", "archiver", "file compression software"),
            new Topic(Purpose.WEBINAR, "web conferencing software",
                    "участник|слушател|аудитори|команд|коллег", "созвон|трансляц|онлайн|интернет|видеосвяз|удален",
                    "web conferencing software", "video conferencing software", "webinar platform"),
            new Topic(Purpose.REMOTE_ACCESS, "remote desktop software",
                    "компьютер|машин|пк\\b|мыш|экран|ноутбук", "расстояни|удален|из дома|дистанцион|из квартир",
                    "remote desktop software", "remote access software", "remote desktop"),
            new Topic(Purpose.VIRTUALIZATION, "a hypervisor",
                    "систем|ос\\b|машин|сервер", "гостев|внутри|независим|виртуал|одном",
                    "hypervisor", "virtualization software"),
            new Topic(Purpose.ANTIVIRUS, "antivirus software",
                    "файл|ноутбук|устройств|компьютер|станци", "вредонос|троян|зараж|вирус",
                    "antivirus", "antivirus software", "anti-malware software"),
            new Topic(Purpose.PRINTER, "a printer",
                    "лист|бума[гж]", "документ|договор|счет|текст|электрон|вывест",
                    "printer", "document printer"));

    private static final Pattern ALTERNATIVE = Pattern.compile("(?U)\\b(?:или|вместо)\\b");

    private static final Pattern TASK = Pattern.compile("(?U)\\b(?:как|чем|чтобы|[а-я]{2,}(?:ть|ться|ти|чь))\\b");

    private static final Pattern REFERENCE = prefixes("руководств|инструкц|учебник|книг|курс|стать[яию]|запис[ьи] лекци");

    private static final Pattern PERSONAL_COMPUTER = Pattern.compile("(?U)\\b(?:пк|компьютер[ау]?|ноутбук[ау]?)\\b");
    private static final Pattern SERVER_ROOM = Pattern.compile("(?U)\\b(?:сервер|стойк|подстанц|цод|трехфаз)");
    private static final Pattern UPS_RATING = Pattern.compile(
            "(?iuU)(?<![\\d.,])([0-9]{1,3}(?:[ \\u00a0\\u202f][0-9]{3})+|[0-9]+)([.,][0-9]+)?\\s*([кk]?)[вv][аa]\\b");

    static boolean wantsDesktopUps(QueryPlan plan) {
        return plan.interpretedTask() && plan.purpose() == Purpose.UPS
                && PERSONAL_COMPUTER.matcher(plan.text()).find() && !SERVER_ROOM.matcher(plan.text()).find();
    }

    // A 10 kVA unit and larger is sized for multiple machines. Demote it for an unspecified
    // single PC load while preserving explicit sort orders and every eligible SKU.
    static boolean highCapacityUps(String name) {
        var rating = UPS_RATING.matcher(name);
        if (!rating.find()) return false;
        String digits = rating.group(1).replaceAll("(?U)\\s", "");
        String fraction = rating.group(2) == null ? "" : rating.group(2).replace(',', '.');
        double va = Double.parseDouble(digits + fraction) * (rating.group(3).isEmpty() ? 1 : 1000);
        return va >= 10_000;
    }

    static QueryPlan interpret(String text, Set<String> aliases, SearchCatalog catalog, ModelClient model,
                               SearchIndex.QueryRanking ranking, List<SearchCatalog.Row> direct)
            throws SQLException {
        String terms = QueryPlan.sentenceText(text);
        if (!model.configured() || !terms.contains(" ") || !QueryPlan.freeForm(terms, aliases)
                || ALTERNATIVE.matcher(terms).find()) return null;
        var topics = TOPICS.stream().filter(topic -> topic.subject().matcher(terms).find()).toList();
        if (catalog.mentionsVendor(terms)) return null;
        var candidates = topics.stream().filter(topic -> topic.context().matcher(terms).find()).toList();
        if (!direct.isEmpty()) {
            if (!TASK.matcher(terms).find() || REFERENCE.matcher(terms).find() || topics.isEmpty()) return null;
            var plausible = candidates.isEmpty() ? topics : candidates;
            if (catalog.hasPurposeAmongTopCards(direct, plausible.stream().map(Topic::purpose).toList())) return null;
            // The returned cards match words but none can perform a plausible operation. Let Qwen
            // describe the tool itself; only a recognized operation can replace the original list.
            return rewrite(terms, topics, catalog, model);
        }
        if (candidates.size() == 1) {
            var candidate = candidates.getFirst();
            if (!catalog.hasInterpretablePurpose(candidate.purpose())) return null;
            return model.confirmsTool(candidate.tool(), terms) ? plan("llm_paraphrase", candidate, terms) : null;
        }
        if (!TASK.matcher(terms).find()) return null;
        var suggestion = ranking.suggestedPurpose();
        if (suggestion != null) {
            var candidate = topics.stream().filter(topic -> topic.purpose() == suggestion).findFirst().orElse(null);
            if (candidate != null && catalog.hasInterpretablePurpose(suggestion))
                return model.confirmsTool(candidate.tool(), terms) ? plan("llm_semantic", candidate, terms) : null;
        }
        return rewrite(terms, topics, catalog, model);
    }

    private static QueryPlan rewrite(String terms, List<Topic> topics, SearchCatalog catalog, ModelClient model)
            throws SQLException {
        if (topics.isEmpty()) return null;
        String name = model.toolName(terms);
        if (name == null) return null;
        for (var topic : topics) {
            if (topic.names().contains(name) && catalog.hasInterpretablePurpose(topic.purpose()))
                return plan("llm_rewrite", topic, terms);
        }
        return null;
    }

    private static final Pattern ENGLISH_TASK = Pattern.compile("\\b(?:how|need|want|can|to)\\b");

    static boolean describesTask(String terms) {
        return !REFERENCE.matcher(terms).find() && (TASK.matcher(terms).find() || ENGLISH_TASK.matcher(terms).find());
    }

    static QueryPlan chatPlan(String terms, AssistantTurn chat, ModelClient model, SearchCatalog catalog,
                              List<String> previousTitles) throws SQLException {
        var instruction = model.chatInstruction(terms, chat, previousTitles);
        if (instruction == null) return null;
        String tool = instruction.tool();
        for (var topic : TOPICS)
            if (topic.names().contains(tool) && model.confirmsTool(topic.tool(), terms))
                return plan("llm_chat", topic, terms);
        var purpose = switch (tool) {
            case "network switch", "ethernet switch" -> Purpose.SWITCH;
            case "computer monitor", "monitor" -> Purpose.MONITOR;
            case "rack server", "server hardware" -> Purpose.SERVER;
            default -> Purpose.WORDS;
        };
        if (purpose != Purpose.WORDS && model.confirmsTool(tool, terms))
            return new QueryPlan("llm_chat", purpose, terms, List.of(), "all");
        String name = catalog.exactLeafCategoryName(instruction.category());
        // A generic shop section cannot verify a concrete tool.
        if (name == null || !model.confirmsTool(name, terms)) return null;
        return new QueryPlan("llm_chat", Purpose.WORDS, name, List.of(), "all");
    }

    private static QueryPlan plan(String compiler, Topic topic, String terms) {
        return new QueryPlan(compiler, topic.purpose(), terms, List.of(), "all");
    }
}
