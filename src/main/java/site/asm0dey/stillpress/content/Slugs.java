package site.asm0dey.stillpress.content;

import module java.base;

/**
 * WordPress {@code sanitize_title} for a title: {@code remove_accents} then {@code sanitize_title_with_dashes}. The
 * result is stored decoded, as permalinks are, so Unicode letters stay.
 */
public final class Slugs {
    private Slugs() {
    }

    private static final Map<String, String> LIGATURES = Map.ofEntries(
            Map.entry("ß", "ss"),
            Map.entry("æ", "ae"),
            Map.entry("Æ", "AE"),
            Map.entry("œ", "oe"),
            Map.entry("Œ", "OE"),
            Map.entry("ø", "o"),
            Map.entry("Ø", "O"),
            Map.entry("đ", "d"),
            Map.entry("Đ", "D"),
            Map.entry("ð", "d"),
            Map.entry("Ð", "D"),
            Map.entry("ł", "l"),
            Map.entry("Ł", "L"),
            Map.entry("þ", "th"),
            Map.entry("Þ", "TH")
    );

    // shortcut: ligatures plus NFD mark stripping on Latin letters, not WP's full table or its locale rules (de_DE,
    // da_DK, ca); upgrade when a locale needs them
    public static String removeAccents(String s) {
        for (var e : LIGATURES.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        var out = new StringBuilder();
        s
            .codePoints()
            .forEach(cp -> {
                // only Latin letters lose their marks: NFD would turn Cyrillic "й" into "и"
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) {
                    out.append(Normalizer
                        .normalize(Character.toString(cp), Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "")
                    );
                } else {
                    out.appendCodePoint(cp);
                }
            });
        return out.toString();
    }

    // shortcut: keeps letters, digits and marks only; WP's list of special punctuation (dashes, quotes, nbsp) is dropped too
    public static String title(String text) {
        var s = removeAccents(text.replaceAll("<[^>]*>", "")).toLowerCase(Locale.ROOT);
        s = s.replaceAll("&.+?;", "").replace('.', '-');
        s = s.replaceAll("[^\\p{L}\\p{N}\\p{M} _\\-]", "");
        s = s.replaceAll("\\s+", "-").replaceAll("-+", "-");
        return s.replaceFirst("^-+", "").replaceFirst("(?<!-)-+$", "");
    }
}
