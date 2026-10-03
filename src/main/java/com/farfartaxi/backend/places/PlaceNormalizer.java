package com.farfartaxi.backend.places;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Query/label normalization shared by search, favorites matching and learned ranking. */
public final class PlaceNormalizer {
    private static final Pattern MCDONALDS = Pattern.compile("\\bmc ?donald(?:s)?\\b");
    private static final Pattern ICA_MAXI = Pattern.compile("\\bica ?maxi\\b");
    /** Single-token synonyms (target is already normalized). */
    private static final Map<String, String> SYNONYMS = Map.of(
        "donken", "mcdonalds",
        "maccen", "mcdonalds",
        "mackan", "mcdonalds");

    private PlaceNormalizer() {
    }

    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String s = Normalizer.normalize(input, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        // apostrophes vanish (mcdonald's -> mcdonalds); other punctuation separates tokens
        s = s.replaceAll("['’‘`´]", "");
        s = s.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        s = MCDONALDS.matcher(s).replaceAll("mcdonalds");
        s = ICA_MAXI.matcher(s).replaceAll("ica maxi");
        if (s.isEmpty()) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        for (String t : s.split(" +")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(SYNONYMS.getOrDefault(t, t));
        }
        return out.toString();
    }

    public static List<String> tokens(String normalized) {
        List<String> out = new ArrayList<>();
        for (String t : normalized.split(" +")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** All query tokens must be a prefix of some token of the (normalized) text. */
    public static boolean tokenPrefixMatch(String normalizedQuery, String text) {
        List<String> q = tokens(normalizedQuery);
        if (q.isEmpty()) {
            return false;
        }
        List<String> t = tokens(normalize(text));
        for (String qt : q) {
            boolean hit = false;
            for (String tt : t) {
                if (tt.startsWith(qt)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }
}
