package site.asm0dey.stillpress.importer;

import module java.base;
import site.asm0dey.stillpress.content.Slugs;

/**
 * WordPress {@code user_nicename} from a login, as {@code wp_insert_user} derives it:
 * {@code sanitize_title(sanitize_user(mb_substr(login, 0, 50), true))}. WXR does not export the nicename.
 */
public final class Nicename {
    private Nicename() {
    }

    public static String of(String login) {
        var s =
                login.codePointCount(0, login.length()) > 50
                ? login.substring(0, login.offsetByCodePoints(0, 50))
                : login;
        // sanitize_user(..., strict = true)
        s = s.replaceAll("<[^>]*>", "");
        s = Slugs.removeAccents(s);
        s = s.replaceAll("%[a-fA-F0-9]{2}", "").replaceAll("&.+?;", "");
        s = s.replaceAll("[^a-zA-Z0-9 _.\\-@]", "");
        s = s.trim().replaceAll("\\s+", " ");
        // sanitize_title -> sanitize_title_with_dashes('save'); strict mode left only ASCII, so its octet rules are no-ops
        s = s.toLowerCase(Locale.ROOT).replace('.', '-');
        s = s.replaceAll("[^%a-z0-9 _-]", "").replaceAll("\\s+", "-").replaceAll("-+", "-");
        return s.replaceAll("^-+|-+$", "");
    }
}
