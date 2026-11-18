package com.hamza.filetransfer.client;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Forgiving text matching for the chat search: case and accents don't matter ("Cafe" finds
 * "café"), Arabic diacritics (tashkeel) and tatweel are ignored, and the Arabic letters people
 * type interchangeably match each other: أ إ آ ٱ → ا, ى → ي, ة → ه, ؤ → و, ئ → ي.
 */
final class SearchText {

    private SearchText() {}

    static String normalize(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")   // accents and Arabic harakat (split off by NFKD)
                .replace("ـ", "");       // tatweel: كـتـاب
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(switch (c) {
                case 'أ', 'إ', 'آ', 'ٱ' -> 'ا'; // أ إ آ ٱ -> ا
                case 'ى' -> 'ي';                                // ى -> ي
                case 'ة' -> 'ه';                                // ة -> ه
                case 'ؤ' -> 'و';                                // ؤ -> و
                case 'ئ' -> 'ي';                                // ئ -> ي
                default -> c;
            });
        }
        return out.toString().toLowerCase(Locale.ROOT).strip();
    }

    /** Whether {@code text} contains the already-normalized {@code query}. */
    static boolean contains(String text, String normalizedQuery) {
        return !normalizedQuery.isEmpty() && normalize(text).contains(normalizedQuery);
    }
}
