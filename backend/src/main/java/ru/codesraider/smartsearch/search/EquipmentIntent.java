package ru.codesraider.smartsearch.search;

import java.util.regex.Pattern;

/** A clearly requested equipment type (or warehouse system) with verifiable SKU facts. */
record EquipmentIntent(Kind kind, double minimumWatts) implements CatalogTask {
    enum Kind { WAREHOUSE_SERVER, BARCODE_READER, ONLINE_UPS, NAS }

    private static final Pattern NAS = words("nas|сетев\\w* (?:накопител\\w*|хранилищ\\w*)");
    private static final Pattern WMS = words("wms|(?:систем\\w* )?управлени\\w* склад\\w*");
    private static final Pattern SCANNER = words("считывател\\w*|сканер\\w*");
    private static final Pattern BARCODE = words("штрих[- ]?код\\w*");
    private static final Pattern LINEAR = words("1d|линейн\\w*|одномерн\\w*");
    private static final Pattern MATRIX = words("2d|двумерн\\w*");
    private static final Pattern UPS = words("ибп|ups|бесперебойник\\w*|источник\\w* бесперебойного питания");
    private static final Pattern ONLINE = words("двойн\\w* преобразовани\\w*|online|онлайн");
    private static final Pattern POWER = Pattern.compile(
            "(?U)(?<![\\w.,])((?:\\d{1,3}(?: \\d{3})+|\\d+)(?:[.,]\\d+)?)\\s*(квт|kw|вт|w)\\b");

    // Only take over when all remaining words are compatible with the verified operation.
    // E.g. a disk for a NAS, a brand, or a requested but unsupported attribute stays in normal search.
    private static final Pattern REQUEST = words("я|мы|мне|нам|в|во|с|со|из|на|и|для|к|по|под|от|чтобы|бы|что|"
            + "купить|приобрести|хотим|хочу|ищу|ищем|нуж\\w*|необходим\\w*|интересует|подбира\\w*|"
            + "подберите|подобрать|посовет\\w*|подскажи\\w*|покажи\\w*|како\\w*|минимум|не менее|не ниже|как минимум");
    private static final Pattern NAS_CONTEXT = words("хран\\w*|хранени\\w*|общ(?:ие|их)|файл\\w*|"
            + "отдел\\w*|офис\\w*|отдельн\\w*|физическ\\w*|аппаратн\\w*|устройств\\w*|данны[ех]");
    private static final Pattern WAREHOUSE = words("распределительн\\w*|склад\\w*|систем\\w*|редакци\\w*|верси\\w*");
    private static final Pattern SERVER = words("серверн\\w*|server");
    private static final Pattern SCANNER_CONTEXT = words("касс\\w*|чита\\w*|считыва\\w*|поддерж\\w*|тип\\w*");
    private static final Pattern LOAD = words("нагрузк\\w*|мощност\\w*|ватт\\w*");

    private static Pattern words(String choices) {
        return Pattern.compile("(?U)\\b(?:" + choices + ")\\b");
    }

    private static boolean covers(String q, Pattern... phrases) {
        String rest = q;
        for (Pattern p : phrases) rest = p.matcher(rest).replaceAll(" ");
        rest = REQUEST.matcher(rest).replaceAll(" ");
        return rest.matches("[\\s.,:;!?/–—-]*");
    }

    static EquipmentIntent parse(String q) {
        if (NAS.matcher(q).find() && !q.matches("(?U).*\\bдля (?:nas|сетев\\w* (?:хранилищ|накопител)\\w*).*")
                && covers(q, NAS, NAS_CONTEXT))
            return new EquipmentIntent(Kind.NAS, 0);
        if (WMS.matcher(q).find() && SERVER.matcher(q).find() && covers(q, WMS, SERVER, WAREHOUSE))
            return new EquipmentIntent(Kind.WAREHOUSE_SERVER, 0);
        if (BARCODE.matcher(q).find() && SCANNER.matcher(q).find()
                && LINEAR.matcher(q).find() && MATRIX.matcher(q).find()
                && covers(q, BARCODE, SCANNER, LINEAR, MATRIX, SCANNER_CONTEXT))
            return new EquipmentIntent(Kind.BARCODE_READER, 0);
        if (UPS.matcher(q).find() && ONLINE.matcher(q).find() && covers(q, UPS, ONLINE, POWER, LOAD)) {
            var power = POWER.matcher(q);
            if (power.find()) {
                double watts = Double.parseDouble(power.group(1).replace(" ", "").replace(',', '.'));
                if (power.group(2).equals("квт") || power.group(2).equals("kw")) watts *= 1000;
                if (!power.find() && Double.isFinite(watts) && watts > 0)
                    return new EquipmentIntent(Kind.ONLINE_UPS, watts);
            }
        }
        return null;
    }

    @Override
    public void constrain(SearchCatalog.Where w) {
        switch (kind) {
            case WAREHOUSE_SERVER -> {
                category(w, "Складские программы");
                w.add("(lower(o.name) LIKE '%wms%' OR o.name LIKE '%истем% управлен% склад%')"
                        + " AND (lower(o.name) LIKE '%server%' OR o.name LIKE '%ерверн%')");
                w.add("o.name NOT LIKE '%лиентск%' AND o.name NOT LIKE '%одул%'");
            }
            case NAS -> {
                category(w, "Сетевые хранилища (NAS)");
                w.add("(o.name LIKE 'Сетевое хранилище %' OR o.name LIKE 'Сетевой накопитель %'"
                        + " OR (lower(' '||o.name||' ') LIKE '% nas %' AND EXISTS (SELECT 1 FROM offer_param p"
                        + " WHERE p.offer_id=o.offer_id AND p.name='Количество отсеков' AND CAST(p.value_norm AS INTEGER)>0)))");
            }
            case BARCODE_READER -> {
                category(w, "Сканеры штрихкодов");
                w.add("(o.name LIKE 'Сканер %' OR o.name LIKE 'Считыватель %' OR EXISTS"
                        + " (SELECT 1 FROM offer_param p WHERE p.offer_id=o.offer_id AND p.name='Вид сканера'))");
                w.add("(instr(' '||replace(lower(o.name), '/', ' ')||' ', ' 1d ')>0 OR EXISTS (SELECT 1 FROM offer_param p WHERE p.offer_id=o.offer_id"
                        + " AND p.name='Поддерживаемые штрих-коды' AND p.value_norm IN"
                        + " ('1d imager','ean 8','ean 13','code 39','code 93','code 128','upc-a','upc-e','codabar')))");
                w.add("(instr(' '||replace(lower(o.name), '/', ' ')||' ', ' 2d ')>0 OR EXISTS (SELECT 1 FROM offer_param p WHERE p.offer_id=o.offer_id"
                        + " AND p.name='Поддерживаемые штрих-коды' AND p.value_norm IN"
                        + " ('2d imager','2d laser','qr-код','pdf-417','datamatrix','aztec')))");
            }
            case ONLINE_UPS -> {
                category(w, "ИБП (Источники бесперебойного питания)");
                w.add("EXISTS (SELECT 1 FROM offer_param p WHERE p.offer_id=o.offer_id AND p.name='Тип ИБП'"
                        + " AND (p.value_norm LIKE '%двойным преобразованием%' OR p.value_norm='online'))");
                // This catalog reports active power in watts and apparent output power in VA.
                w.add("EXISTS (SELECT 1 FROM offer_param p WHERE p.offer_id=o.offer_id AND p.name='Эффективная мощность'"
                        + " AND p.value_norm<>'' AND p.value_norm NOT GLOB '*[^0-9]*'"
                        + " AND CAST(p.value_norm AS REAL)>=?)", minimumWatts);
            }
        }
    }

    private static void category(SearchCatalog.Where w, String label) {
        w.add("o.category_id IN (SELECT id FROM entity WHERE kind='category' AND name=?)", label);
    }
}
