package site.asm0dey.stillpress.importer;

import module java.base;

/**
 * WordPress {@code user_nicename} from a login, as {@code wp_insert_user} derives it:
 * {@code sanitize_title(sanitize_user(mb_substr(login, 0, 50), true))}. WXR does not export the nicename.
 */
final class Nicename {
    private Nicename() {
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

    static String of(String login) {
        var s =
                login.codePointCount(0, login.length()) > 50
                ? login.substring(0, login.offsetByCodePoints(0, 50))
                : login;
        // sanitize_user(..., strict = true)
        s = s.replaceAll("<[^>]*>", "");
        s = removeAccents(s);
        s = s.replaceAll("%[a-fA-F0-9]{2}", "").replaceAll("&.+?;", "");
        s = s.replaceAll("[^a-zA-Z0-9 _.\\-@]", "");
        s = s.trim().replaceAll("\\s+", " ");
        // sanitize_title -> sanitize_title_with_dashes('save'); strict mode left only ASCII, so its octet rules are no-ops
        s = s.toLowerCase(Locale.ROOT).replace('.', '-');
        s = s.replaceAll("[^%a-z0-9 _-]", "").replaceAll("\\s+", "-").replaceAll("-+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    // shortcut: NFD + mark stripping plus common ligatures, not WP's full table or its locale rules (de_DE, da_DK, ca)
    private static String removeAccents(String s) {
        for (var e : LIGATURES.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
