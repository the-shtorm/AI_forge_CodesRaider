package ru.codesraider.smartsearch.search;

import java.util.List;
import java.util.regex.Pattern;

/** Category affinities for shopping suggestions. They never establish compatibility between SKUs. */
final class Merchandising {
    private Merchandising() {}

    private static final String BACKUP = "Программное обеспечение для резервного копирования";
    private static final String KEYBOARDS = "Клавиатуры, мыши, комплекты";
    private static final String AUDIO = "Наушники и микрофоны";
    private static final String DOCUMENTS = "Программное обеспечение для работы с документами";
    private static final String ARCHIVERS = "Архиваторы";
    private static final String POWER = "ИБП (Источники бесперебойного питания)";
    private static final String NETWORK = "Сетевые утилиты";
    private static final String STORAGE = "Внешние HDD";

    private record Pairing(Pattern anchorCategory, List<String> companionCategories) {
        Pairing(String pattern, String... categories) {
            this(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), List.of(categories));
        }
    }

    // Use feed taxonomy instead of specific offers or fixture-specific category IDs.
    private static final List<Pairing> PAIRS = List.of(
            new Pairing("^ноутбуки$", "Подставки для ноутбуков", "Столы для ноутбуков", KEYBOARDS),
            new Pairing("^(столы|подставки) для ноутбуков$", KEYBOARDS, AUDIO),
            new Pairing("^(мониторы|десктопы|моноблоки|минипк|персональные компьютеры)$", KEYBOARDS, AUDIO),
            new Pairing("принтер|мфу", "Ламинаторы", "Брошюраторы"),
            new Pairing("сканер(?!ы штрих)", DOCUMENTS),
            new Pairing("сканеры штрих|терминалы сбора|pos-терминалы", "Складские программы"),
            new Pairing("складские программы|управление логистикой", "Сканеры штрихкодов"),
            new Pairing("видеокамер", "ПО для видеонаблюдения"),
            new Pairing("видеонаблюдени", "IP-видеокамеры"),
            new Pairing("хранилищ|хранения данных|rack-сервер|микросервер|напольные сервер", POWER, BACKUP),
            new Pairing("^ибп|бесперебойного питания", NETWORK),
            new Pairing("коммутатор|роутер|точки доступа", POWER),
            new Pairing("^связь$|конференц|онлайн общения|вебинар|ip-телефони", AUDIO),
            new Pairing("решения удал[её]нного доступа|интернет и сеть|учета компьютеров", NETWORK, BACKUP),
            new Pairing("сетевые утилиты|мониторинг сети", BACKUP),
            new Pairing("схемы и диаграммы", "Программы для разработки ПО", "Программное обеспечение для управления проектами"),
            new Pairing("видеоредактор|обработки цифрового фото|графические редакторы|кодировщики", STORAGE),
            new Pairing("файловые менеджеры|резервного копирования|восстановления данных", STORAGE, BACKUP),
            new Pairing("антивирус|защит[аы] информации|серверное по|операционные систем|базами данных|виртуализац", BACKUP),
            new Pairing("программы для разработки|библиотек|^vcl$|^activex$", "Программное обеспечение для управления проектами"),
            new Pairing("документ|офисн.*(по|пакет)|текстовые редакторы|переводчики", ARCHIVERS));

    static List<String> pairedCategories(String anchorCategory) {
        for (var pairing : PAIRS) {
            if (pairing.anchorCategory.matcher(anchorCategory).find()) return pairing.companionCategories;
        }
        return List.of();
    }

    static String preferredType(String productType, String profile) {
        if (productType != null && !productType.isBlank()) return productType;
        if ("hardware_buyer".equals(profile)) return "электроника";
        if ("software_admin".equals(profile)) return "софт";
        return null;
    }

    static List<String> catalogSections(String productType) {
        if ("электроника".equals(productType)) return List.of(KEYBOARDS, AUDIO, POWER);
        if ("софт".equals(productType)) return List.of(BACKUP, DOCUMENTS, ARCHIVERS);
        return List.of(KEYBOARDS, ARCHIVERS);
    }

    static String preferredTitleOrder(String category) {
        // Some catalog sections also contain mouse pads or metered storage capacity.
        // Prefer a standalone input device or backup product when either is in stock.
        return switch (category) {
            case KEYBOARDS -> "CASE WHEN o.name LIKE '%лавиатур%' OR o.name LIKE '%ышь%'"
                    + " OR lower(o.name) LIKE '%mouse%' OR lower(o.name) LIKE '%keyboard%' THEN 0 ELSE 1 END,";
            case BACKUP -> "CASE WHEN (lower(o.name) LIKE '%backup%' OR o.name LIKE '%экап%'"
                    + " OR o.name LIKE '%езервн%копир%') AND o.name NOT LIKE '%ространство%' THEN 0 ELSE 1 END,";
            case NETWORK -> "CASE WHEN lower(o.name) LIKE '%monitor%' OR o.name LIKE '%ониторинг%'"
                    + " OR lower(o.name) LIKE '%audit%' OR o.name LIKE '%удит%' THEN 0 ELSE 1 END,";
            case AUDIO -> "CASE WHEN o.name LIKE '%арнитур%' OR lower(o.name) LIKE '%headset%' THEN 0 ELSE 1 END,";
            default -> "";
        };
    }
}
