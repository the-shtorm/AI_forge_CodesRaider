package ru.codesraider.smartsearch.search;

import java.util.regex.Pattern;

import static ru.codesraider.smartsearch.search.QueryPlan.Purpose;

/** Strong evidence requires an operation and its digital object in the same clause. */
final class TaskGoals {
    private static Pattern words(String expression) {
        return Pattern.compile("(?U)\\b(?:" + expression + ")");
    }

    private static final Pattern STORAGE = words("файл|пап(?:к|ок)|каталог|директори|баз[а-я]*|сервер|диск|данны|документ");
    private static final Pattern FILES = words("файл|пап(?:к|ок)|документ|каталог|директори");
    private static final Pattern SPARE = words("(?:запасн|резервн|втор|предыдущ|вчерашн)[а-я]* (?:экземпляр|верси|копи)|дублиров|(?:созда|дела|сохраня|сохран|храни)[а-я]* копи");
    private static final Pattern RISK = words("утрат|потер|сбо[йяе]|стирани|поломк|удалени");
    private static final Pattern PROTECT_OR_COPY = words("застрахова|убереч|подстрахова|дубликат|копи");
    private static final Pattern IMAGE = words("скан|отскан|сним[а-я]*|фото|картин|изображ|вывеск");
    private static final Pattern TEXT = words("букв|надпис|текст|реквизит|сумм|номер|строк|слов|цифр|символ");
    private static final Pattern READ = words("преобраз|перене|перенос|извлеч|извлек|вытян|скопир|оцифров|распозна|преврат|получить|считать");
    private static final Pattern LANGUAGES = words("переводчик|француз|английск|немецк|китайск");
    static final Pattern MANUAL_ENTRY = words("без (?:ручного (?:набора|ввода)|перепечатки|перепечатывания)\\b");
    private static final Pattern ARCHIVE = words("архив(?:а|е|ы|ов)?\\b|архивн[а-я]* файл|zip\\b|rar\\b");
    private static final Pattern PACK = words("упакова|упаковат|запакова|сжа(?:ть|ти[ея]|ты[йех])|сжима");
    private static final Pattern UNPACK = words("распакова|разархивир|извлеч|извлек|достать|вынуть");
    private static final Pattern VIDEO = words("видео|клип|ролик|фильм");
    private static final Pattern EDIT_VIDEO = words("поверну|разверну|заглуш[а-я]* звук|приглуш[а-я]* звук|плавн[а-я]* переход");
    private static final Pattern AUDIENCE = words("аудитори|слушател|участник|коллег|команд|заказчик");
    private static final Pattern BROADCAST = words("прям[а-я]* эфир|трансляц|видеосвяз");
    private static final Pattern HOST = words("провес|вести|обсуд|собрать|задавать вопрос|отвечать на вопрос");
    private static final Pattern SERVICE_ORDER = words("заказа|оформить|записаться|доставить|получить|найти|выдать|сделать|вызвать");
    private static final Pattern SERVICE_OBJECT = words("фотосесси|фотографу|нотариальн|заверенн|курьер|доставк|копицентр|фотосалон");
    private static final Pattern PAPER_RECORD = words("свидетельств|паспорт|справк|бумажн");
    private static final Pattern ARCHIVE_SERVICE = words("справк|выписк|свидетельств|нотари|архивариус|архивист|библиотек|доставк");

    // These requests describe a material or a third-party service. Keep literal catalog retrieval
    // available (e.g. an offer for photo paper), while bypassing the software/device interpreter.
    static boolean literalRequest(String text) {
        if (text.contains("фотобумаг")) return true;
        return SERVICE_ORDER.matcher(text).find() && (SERVICE_OBJECT.matcher(text).find()
                || (PAPER_RECORD.matcher(text).find() && (text.contains("архив") || text.contains("достав"))));
    }

    static boolean matches(Purpose purpose, String clause) {
        return switch (purpose) {
            case BACKUP -> STORAGE.matcher(clause).find() && (SPARE.matcher(clause).find()
                    || (PROTECT_OR_COPY.matcher(clause).find() && RISK.matcher(clause).find()))
                    && !ARCHIVE_SERVICE.matcher(clause).find();
            case OCR -> IMAGE.matcher(clause).find() && TEXT.matcher(clause).find()
                    && (READ.matcher(clause).find() || MANUAL_ENTRY.matcher(clause).find())
                    && !LANGUAGES.matcher(clause).find();
            case VIDEO_EDITOR -> VIDEO.matcher(clause).find() && EDIT_VIDEO.matcher(clause).find();
            case WEBINAR -> AUDIENCE.matcher(clause).find() && BROADCAST.matcher(clause).find() && HOST.matcher(clause).find();
            case ARCHIVER -> !ARCHIVE_SERVICE.matcher(clause).find()
                    && ((PACK.matcher(clause).find() && (FILES.matcher(clause).find() || ARCHIVE.matcher(clause).find()))
                    || (UNPACK.matcher(clause).find() && ARCHIVE.matcher(clause).find()));
            default -> false;
        };
    }
}
