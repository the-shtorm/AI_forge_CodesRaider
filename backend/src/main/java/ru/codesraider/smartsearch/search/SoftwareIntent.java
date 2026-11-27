package ru.codesraider.smartsearch.search;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Software tasks whose purpose and qualifiers can be checked on a catalog offer. */
@FunctionalInterface
interface SoftwareIntent extends CatalogTask {
    Pattern NEGATED = Pattern.compile("(?U)\\b(?:не|кроме|без|вместо)\\b");
    Pattern VIDEO_CONVERTER = Pattern.compile("(?U)\\b(?:видео[- ]?конверт\\w*|конверт\\w*\\s+видео\\w*|video converter)\\b");
    Pattern BUSINESS = Pattern.compile("(?U)\\b(?:для (?:компани\\w*|организаци\\w*|юрлиц\\w*)|корпоративн\\w*|бизнес[- ]лицензи\\w*)\\b");
    Pattern CAMERA = Pattern.compile("(?U)\\b(?:ip[- ]?)?(?:видео)?камер\\w*\\b");
    Pattern RECORD = Pattern.compile("(?U)\\b(?:запис\\w*|видеозапис\\w*|запиш\\w*)\\b");
    Pattern PROGRAM = Pattern.compile("(?U)\\b(?:программ(?:а|у|ы|ное обеспечение)|софт|по)\\b");
    Pattern SOFTWARE_FOR_RECORDING = Pattern.compile(
            "(?U)\\b(?:программ[ауые]|софт|по)\\s+(?:для\\s+записи|чтобы\\s+запис\\w*)\\b");
    Pattern CAMERA_WITH_PROGRAM = Pattern.compile("(?U)\\bкамер\\w*\\s+(?:с|со)\\s+(?:программ\\w*|софт\\w*|по)\\b");
    Pattern TRANSLATOR = Pattern.compile("(?U)\\b(?:переводчик\\w*|перевод\\w* текст\\w*)\\b");
    Pattern LANGUAGE_PAIR = Pattern.compile("(?U)\\b(?:с|из)\\s+(\\p{L}+)\\s+(?:языка\\s+)?на\\s+(\\p{L}+)\\b");
    Pattern CHART_LIBRARY = Pattern.compile("(?U)\\b(?:библиотек\\w*|компонент\\w*)\\b.*\\b(?:диаграмм\\w*|график\\w*)\\b");
    Pattern WEB = Pattern.compile("(?U)\\b(?:веб(?:а|е)?|web|javascript)\\b");
    Pattern WPF = Pattern.compile("(?U)\\bwpf\\b");
    Pattern DISJUNCTION = Pattern.compile("(?U)\\b(?:или|либо)\\b");
    Pattern REQUEST_WORDS = Pattern.compile("(?U)\\b(?:ищу|ищем|найди|нужен|нужна|нужно|нужны|мне|нам|подбери|пожалуйста|для|на|с|из|под|в|только)\\b");
    Pattern CAMERA_CONTEXT = Pattern.compile("(?U)\\b(?:уже есть|этой|чтобы)\\b");
    Pattern BACKUP_ACTION = Pattern.compile("(?U)\\b(?:б[еэ]кап\\w*|резервн\\w* копи\\w*|запасн\\w* (?:копи\\w*|экземпляр\\w*))\\b");
    Pattern SERVER = Pattern.compile("(?U)\\b(?:физическ\\w* )?сервер(?:а|ов|у)?\\b");
    Pattern SCHEDULE = Pattern.compile("(?U)\\b(?:по расписанию|ежедневно|каждую ночь|каждый день)\\b");
    Pattern BACKUP_CONTEXT = Pattern.compile("(?U)\\b(?:делать|сделать|создавать|создать|сохранять|настроить|содержимого|данных|файлов)\\b");
    Pattern EDUCATION = Pattern.compile("(?U)\\b(?:школ[а-я]*|вуз[а-я]*|учебн[а-я]* заведени[а-я]*|образовательн[а-я]* организаци[а-я]*)\\b");
    Pattern PURCHASE_CONTEXT = Pattern.compile("(?U)\\b(?:отделу маркетинга|лицензи(?:я|ю|ей|и))\\b");
    Pattern PDF_CONTEXT = Pattern.compile("(?U)\\b(?:какой|редактор|поставить|установить|чтобы|вносить|внести|документ[а-я]*)\\b");
    SoftwareIntent PDF_EDITOR = where -> where.add("pdf_editor(o.name)");
    Pattern TEXT_CONTEXT = Pattern.compile("(?U)\\b(?:(?:техническ(?:ий|ого|их) )?текст(?:а|ов|ы)?|язык)\\b");
    Pattern CHART_CONTEXT = Pattern.compile("(?U)\\b(?:библиотек[ауи]|компонент(?:ы|а)?|диаграмм[уы]?|график(?:и|ов|а)?"
            + "|приложени(?:е|я|й)|подойдет|вариант|и)\\b");

    // Task SQL replaces lexical retrieval. Only take that route if it accounts for the entire request.
    private static boolean covers(String query, Pattern... fragments) {
        String rest = query;
        for (Pattern fragment : fragments) rest = fragment.matcher(rest).replaceAll(" ");
        try {
            rest = new Requirements().extract(rest);
        } catch (ArithmeticException | NumberFormatException invalidQuantity) {
            return false; // The normal search path reports an invalid filter.
        }
        rest = REQUEST_WORDS.matcher(rest).replaceAll(" ");
        return rest.matches("[\\s.,:;!?()-]*");
    }

    static SoftwareIntent parse(String q) {
        if (NEGATED.matcher(q).find()) return null;
        if ((Requirements.PDF_EDITOR_TITLE.matcher(q).find() || Requirements.EDIT_PDF.matcher(q).find())
                && covers(q, Requirements.PDF_EDITOR_TITLE, Requirements.EDIT_PDF, PDF_CONTEXT))
            return PDF_EDITOR;
        if (BACKUP_ACTION.matcher(q).find() && SERVER.matcher(q).find() && SCHEDULE.matcher(q).find()
                && covers(q, BACKUP_ACTION, SERVER, SCHEDULE, BACKUP_CONTEXT, PROGRAM, EDUCATION)) {
            boolean education = EDUCATION.matcher(q).find();
            return new ScheduledServerBackup(education);
        }
        if (CAMERA.matcher(q).find() && RECORD.matcher(q).find() && PROGRAM.matcher(q).find()
                && !CAMERA_WITH_PROGRAM.matcher(q).find()
                && (q.contains("уже есть") || q.contains("только программ") || SOFTWARE_FOR_RECORDING.matcher(q).find())
                && covers(q, CAMERA, RECORD, PROGRAM, CAMERA_CONTEXT)) {
            return SoftwareIntent::recordingSoftware;
        }
        if (VIDEO_CONVERTER.matcher(q).find() && covers(q, VIDEO_CONVERTER, BUSINESS, PURCHASE_CONTEXT)) {
            boolean business = BUSINESS.matcher(q).find();
            return where -> videoConverter(where, business);
        }
        if (TRANSLATOR.matcher(q).find()) {
            Matcher pair = LANGUAGE_PAIR.matcher(q);
            if (pair.find()) {
                Language source = Language.fromQuery(pair.group(1));
                Language destination = Language.fromQuery(pair.group(2));
                if (source != null && destination != null && source != destination && !pair.find()
                        && covers(q, TRANSLATOR, LANGUAGE_PAIR, TEXT_CONTEXT))
                    return where -> translator(where, source, destination);
            }
        }
        if (CHART_LIBRARY.matcher(q).find()) {
            var web = WEB.matcher(q);
            var wpf = WPF.matcher(q);
            boolean forWeb = web.find(), forWpf = wpf.find();
            if ((forWeb || forWpf) && covers(q, WEB, WPF, CHART_CONTEXT, DISJUNCTION)) {
                boolean either = forWeb && forWpf && DISJUNCTION.matcher(q.substring(
                        Math.min(web.end(), wpf.end()), Math.max(web.start(), wpf.start()))).find();
                return where -> diagramLibrary(where, forWeb, forWpf, either);
            }
        }
        return null;
    }

    private static void category(SearchCatalog.Where where, String pattern) {
        where.add("o.category_id IN (SELECT id FROM entity WHERE kind='category' AND name LIKE ?)", pattern);
    }

    private static void recordingSoftware(SearchCatalog.Where where) {
        category(where, "%идеонаблюдени%");
        where.add("""
                (lower(o.name) LIKE '%record%' OR o.name LIKE '%идеозапис%'
                 OR o.name LIKE '%апис%видео%' OR o.name LIKE '%апис%камер%'
                 OR (o.name LIKE '%идеонаблюдени%' AND o.name LIKE '% канал%'))
                AND o.name NOT LIKE '%аспознаван%' AND o.name NOT LIKE '%налитик%'
                AND lower(o.name) NOT LIKE '%recognition%' AND lower(o.name) NOT LIKE '%analytics%'
                """);
    }

    record ScheduledServerBackup(boolean education) implements SoftwareIntent {
        @Override public QueryPlan.Purpose purpose() { return QueryPlan.Purpose.BACKUP; }
        @Override public void constrain(SearchCatalog.Where where) { scheduledServerBackup(where, education); }
    }

    private static void scheduledServerBackup(SearchCatalog.Where where, boolean education) {
        category(where, "%езервн%копирован%");
        // Education-only licenses require a buyer in that customer segment.
        if (!education) where.add("o.name NOT LIKE '%бразовательн%' AND lower(o.name) NOT LIKE '%education%'");
        // Server entitlement is per SKU. Standard and Advanced Cyber Backup document scheduled plans:
        // https://docs.cyberprotect.ru/ru-RU/CyberBackup/18.0/user/schedule.html
        where.add("""
                (o.name LIKE '%ля физического сервера%' OR lower(o.name) LIKE '%server backup%'
                 OR lower(o.name) LIKE '%backup for server%')
                AND coalesce(o.license_type,'') NOT IN ('upgrade','renewal')
                AND ((o.vendor_id='cyberprotect' AND (o.name LIKE '%ибер Бэкап Стандартная редакция%'
                    OR o.name LIKE '%ибер Бэкап Расширенная редакция%'))
                    OR EXISTS (SELECT 1 FROM offer_param p WHERE p.offer_id=o.offer_id
                        AND p.name IN ('Копирование по расписанию','Резервное копирование по расписанию')
                        AND p.value_norm IN ('да','есть','поддерживается','yes')))
                """);
    }

    private static void videoConverter(SearchCatalog.Where where, boolean business) {
        category(where, "%онвертер%");
        where.add("""
                ((o.name LIKE '%идео%' OR lower(o.name) LIKE '%video%')
                 AND (o.name LIKE '%онвертер%' OR lower(o.name) LIKE '%convert%'))
                AND o.name NOT LIKE '%удиоконвертер%' AND lower(o.name) NOT LIKE '%audio converter%'
                """);
        if (business) where.add("""
                (o.name LIKE '%изнес-лицензи%' OR o.name LIKE '%орпоративн%лицензи%'
                 OR lower(o.name) LIKE '%business licen%' OR o.description LIKE '%ип клиента: юрлицо%'
                 OR EXISTS(SELECT 1 FROM offer_param p WHERE p.offer_id=o.offer_id
                           AND p.name='Пользователь' AND p.value LIKE '%рлицо%'))
                AND o.name NOT LIKE '%ерсональн%' AND lower(o.name) NOT LIKE '%personal%'
                """);
    }

    enum Language {
        ENGLISH("англ", List.of("нглийско", "нгло")),
        RUSSIAN("русск", List.of("усско")),
        GERMAN("немец", List.of("емецко")),
        FRENCH("француз", List.of("ранцузско"));

        private final String stem;
        private final List<String> sourcePrefixes;

        Language(String stem, List<String> sourcePrefixes) {
            this.stem = stem;
            this.sourcePrefixes = sourcePrefixes;
        }

        static Language fromQuery(String word) {
            for (Language lang : values()) if (word.startsWith(lang.stem)) return lang;
            return null;
        }
    }

    private static void translator(SearchCatalog.Where where, Language from, Language to) {
        category(where, "%ереводчик%");
        // Russian catalog titles encode ordered pairs, e.g. «англо-русско-английский».
        // A different language elsewhere in the title is not evidence of the requested direction.
        List<String> pairs = from.sourcePrefixes.stream().map(prefix -> "%" + prefix + "-" + to.stem + "%").toList();
        String title = "replace(replace(o.name,'–','-'),'‑','-')";
        if (pairs.size() == 1) where.add(title + " LIKE ?", pairs.getFirst());
        else where.add("(" + title + " LIKE ? OR " + title + " LIKE ?)", pairs.getFirst(), pairs.getLast());
    }

    private static void diagramLibrary(SearchCatalog.Where where, boolean web, boolean wpf, boolean either) {
        category(where, "%иаграмм%");
        where.add("""
                (lower(o.name) LIKE '%developer%' OR lower(o.name) LIKE '%library%'
                 OR lower(o.name) LIKE '%component%' OR o.name LIKE '%иблиотек%' OR o.name LIKE '%омпонент%')
                """);
        String webProof = "(lower(o.name) LIKE '% web %' OR lower(o.name) LIKE '% web' OR lower(o.name) LIKE '%javascript%')";
        String wpfProof = "(lower(o.name) LIKE '% wpf %' OR lower(o.name) LIKE '% wpf')";
        if (web && wpf) where.add("(" + webProof + (either ? " OR " : " AND ") + wpfProof + ")");
        else where.add(web ? webProof : wpfProof);
    }
}
