package com.supplypilot.document.rag;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches complete business identifiers after Unicode compatibility normalization. */
final class IdentifierMatcher {

    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[A-Z0-9]+(?:[-_/][A-Z0-9]+)+(?![\\p{L}\\p{N}_])");

    private IdentifierMatcher() {
    }

    static Set<String> find(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        Matcher matcher = IDENTIFIER.matcher(normalized);
        Set<String> identifiers = new HashSet<>();
        while (matcher.find()) identifiers.add(matcher.group());
        return Set.copyOf(identifiers);
    }

    static boolean containsAll(String value, Set<String> identifiers) {
        return find(value).containsAll(identifiers);
    }
}
