package site.asm0dey.stillpress.importer;

import module java.base;

/**
 * Replaces original upload URLs, and their {@code -WxH} size variants, with local media URLs anywhere in raw text.
 */
public final class MediaRewriter {
    /**
     * Jetpack Photon CDN prefix: {@code //i0.wp.com/<host>/wp-content/uploads/...} serves the same upload.
     */
    static final String PHOTON = "i[0-9]\\.wp\\.com/";
    /**
     * Photon's query ({@code ?resize=1024%2C768&amp;ssl=1}): ends at a quote, space, backslash or an encoded quote.
     */
    static final String PHOTON_QUERY = "(?:\\?(?:[^\\s\"'<>&\\\\]|&(?!quot;|#0*3[49];|apos;|lt;|gt;))*)?";
    private static final Pattern PHOTON_URL = Pattern.compile("//" + PHOTON);
    private final List<Map.Entry<Pattern, String>> rules = new ArrayList<>();

    public MediaRewriter(Map<String, String> sourceToLocal) {
        sourceToLocal.forEach((key, local) -> {
            // any scheme or none: a "//host/..." rule must swallow the "https:" before it, not leave "https:/media/..."
            var src = key.replaceFirst("^https?:", "");
            int dot = src.lastIndexOf('.');
            int slash = src.lastIndexOf('/');
            var regex = dot > slash
                    ? Pattern.quote(src.substring(0, dot)) + "(?:-\\d+x\\d+)?" + Pattern.quote(src.substring(dot))
                    : Pattern.quote(src);
            if (PHOTON_URL.matcher(src).find()) {
                regex += PHOTON_QUERY;
            }
            rules.add(Map.entry(Pattern.compile("(?:https?:)?" + regex), Matcher.quoteReplacement(local)));
        });
    }

    public String rewrite(String html) {
        if (html == null) {
            return null;
        }
        for (var r : rules) {
            html = r.getKey().matcher(html).replaceAll(r.getValue());
        }
        return html;
    }
}
