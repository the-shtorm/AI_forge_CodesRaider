package ru.codesraider.smartsearch.search;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.util.BytesRef;

/** Conservative second pass over indexed title terms. SKUs are resolved before this stage. */
final class ProductSpelling {
    private static final Pattern LATIN = Pattern.compile("[a-z]{3,48}");
    private final DirectoryReader reader;
    private Map<String,List<BytesRef>> phonetic;

    ProductSpelling(DirectoryReader reader) { this.reader = reader; }

    Query titleQuery(String text) throws IOException {
        String[] words = text.split(" ");
        if (words.length < 2 || words.length > 7) return null;
        if (text.matches("[a-z0-9 ]+")) {
            var title = new BooleanQuery.Builder();
            int corrected = 0, anchors = 0;
            for (String word : words) {
                Term term = new Term("name", word);
                if (reader.docFreq(term) > 0) {
                    if (LATIN.matcher(word).matches()) anchors++;
                    title.add(new TermQuery(term), BooleanClause.Occur.MUST);
                } else if (word.matches("[a-z]{5,48}") && ++corrected == 1) {
                    title.add(new FuzzyQuery(term, 1, 1, 32, true), BooleanClause.Occur.MUST);
                } else return null;
            }
            return corrected == 1 && anchors > 0 ? title.build() : null;
        }
        if (!text.matches("[а-я0-9 ]+") || !text.matches(".*[а-я].*")) return null;
        var vocabulary = phoneticVocabulary();
        var title = new BooleanQuery.Builder();
        int matched = 0;
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.matches("\\d{4}")) {
                title.add(new TermQuery(new Term("name",word)), BooleanClause.Occur.MUST);
                continue;
            }
            String sound = soundKey(word);
            // Russian spellings often separate an English compound into two words.
            List<BytesRef> choices = null;
            if (i + 1 < words.length && words[i+1].matches("[а-я]+")) {
                choices = vocabulary.get(sound + soundKey(words[i+1]));
                if (choices != null) i++;
            }
            if (choices == null) choices = vocabulary.get(sound);
            if (choices == null || choices.size() > 12) return null;
            title.add(new TermInSetQuery("name", choices), BooleanClause.Occur.MUST);
            matched++;
        }
        return matched >= 2 ? title.build() : null;
    }

    private synchronized Map<String,List<BytesRef>> phoneticVocabulary() throws IOException {
        if (phonetic != null) return phonetic;
        var vocabulary = new HashMap<String,List<BytesRef>>();
        var terms = MultiTerms.getTerms(reader, "name");
        if (terms != null) {
            var iterator = terms.iterator();
            for (BytesRef bytes; (bytes = iterator.next()) != null;) {
                String word = bytes.utf8ToString();
                if (!LATIN.matcher(word).matches()) continue;
                String key = soundKey(word);
                if (key.length() >= 3)
                    vocabulary.computeIfAbsent(key, unused -> new ArrayList<>()).add(BytesRef.deepCopyOf(bytes));
            }
        }
        phonetic = vocabulary;
        return phonetic;
    }

    private static String soundKey(String word) {
        // Use the consonants of the English pronunciation; all matched words must occur in one title.
        StringBuilder result = new StringBuilder();
        for (char ch : word.replaceAll("ti(?=[ao])","sh").replace("sch","sk").replace("ph","f")
                .replace("ck","k").replaceAll("c(?=[eiy])","s").replace('c','k')
                .replaceAll("(?<=[ao])w", "").toCharArray()) {
            result.append(switch (ch) {
                case 'б' -> "b"; case 'в' -> "v"; case 'г' -> "g"; case 'д' -> "d";
                case 'ж' -> "zh"; case 'з' -> "z"; case 'к' -> "k"; case 'л' -> "l";
                case 'м' -> "m"; case 'н' -> "n"; case 'п' -> "p"; case 'р' -> "r";
                case 'с' -> "s"; case 'т' -> "t"; case 'ф' -> "f"; case 'х' -> "h";
                case 'ц' -> "ts"; case 'ч' -> "ch"; case 'ш','щ' -> "sh"; case 'x' -> "ks";
                default -> ch >= 'a' && ch <= 'z' && "aeiouy".indexOf(ch)<0 ? String.valueOf(ch) : "";
            });
        }
        return result.toString().replaceAll("(.)\\1+", "$1");
    }
}
