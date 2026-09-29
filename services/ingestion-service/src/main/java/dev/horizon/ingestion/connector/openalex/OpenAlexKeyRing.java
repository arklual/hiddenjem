package dev.horizon.ingestion.connector.openalex;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Ordered credentials; a rate-limited key is skipped until the next UTC day. */
final class OpenAlexKeyRing {
    private final List<String> keys;
    private int index;
    private LocalDate day = LocalDate.now(ZoneOffset.UTC);

    OpenAlexKeyRing(String primary, String additional) {
        var ordered = new LinkedHashSet<String>();
        add(ordered, primary);
        if (additional != null) {
            for (String key : additional.split(",")) {
                add(ordered, key);
            }
        }
        keys = List.copyOf(new ArrayList<>(ordered));
    }

    synchronized String current() {
        resetOnNewDay();
        return keys.isEmpty() ? "" : keys.get(index);
    }

    List<String> configured() {
        return keys;
    }

    synchronized boolean exhausted(String key) {
        resetOnNewDay();
        if (keys.isEmpty()) {
            return false;
        }
        if (!keys.get(index).equals(key)) {
            return true;
        }
        if (index == keys.size() - 1) {
            return false;
        }
        index++;
        return true;
    }

    private void resetOnNewDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!day.equals(today)) {
            day = today;
            index = 0;
        }
    }

    private static void add(LinkedHashSet<String> keys, String key) {
        if (key != null && !key.isBlank()) {
            keys.add(key.trim());
        }
    }
}
