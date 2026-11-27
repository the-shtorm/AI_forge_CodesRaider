package ru.codesraider.smartsearch.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import ru.codesraider.smartsearch.modelclient.EncoderClient;
import static ru.codesraider.smartsearch.search.QueryPlan.Purpose;

/** Small passage index for suggesting a purpose to the verifier after empty retrieval. */
record TaskMeanings(List<Purpose> purposes, List<float[]> vectors) {
    static TaskMeanings load(EncoderClient encoder, String profile) throws IOException {
        var purposes = new ArrayList<Purpose>();
        var descriptions = new ArrayList<String>();
        try (var stream = TaskMeanings.class.getResourceAsStream("/search/task-descriptions.tsv")) {
            if (stream == null) throw new IOException("Missing task descriptions");
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null;) {
                String[] parts = line.split("\\t", 2);
                if (parts.length != 2) throw new IOException("Invalid task description");
                purposes.add(Purpose.valueOf(parts[0]));
                descriptions.add(parts[1]);
            }
        }
        return new TaskMeanings(List.copyOf(purposes), encoder.encodePassages(profile, descriptions));
    }

    Purpose nearest(float[] query) {
        var scores = new EnumMap<Purpose, Float>(Purpose.class);
        for (int i = 0; i < vectors.size(); i++) {
            var passage = vectors.get(i);
            float score = 0;
            for (int j = 0; j < query.length; j++) score += query[j] * passage[j];
            scores.merge(purposes.get(i), score, Math::max);
        }
        float first = -1, second = -1;
        Purpose best = null;
        for (var entry : scores.entrySet()) {
            float score = entry.getValue();
            if (score > first) {
                second = first;
                first = score;
                best = entry.getKey();
            } else if (score > second) second = score;
        }
        // Normalized multilingual-e5-small cosines. Compare distinct purposes, including UNKNOWN.
        return best != Purpose.UNKNOWN && first >= .85f && first - second >= .015f ? best : null;
    }
}
