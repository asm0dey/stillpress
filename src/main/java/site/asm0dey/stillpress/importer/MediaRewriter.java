package site.asm0dey.stillpress.importer;

import module java.base;

/**
 * Replaces original upload URLs, and their {@code -WxH} size variants, with local media URLs anywhere in raw text.
 */
public final class MediaRewriter {
    private final List<Map.Entry<Pattern, String>> rules = new ArrayList<>();

    public MediaRewriter(Map<String, String> sourceToLocal) {
        sourceToLocal.forEach((src, local) -> {
            int dot = src.lastIndexOf('.');
            int slash = src.lastIndexOf('/');
            var regex = dot > slash
                    ? Pattern.quote(src.substring(0, dot)) + "(?:-\\d+x\\d+)?" + Pattern.quote(src.substring(dot))
                    : Pattern.quote(src);
            rules.add(Map.entry(Pattern.compile(regex), Matcher.quoteReplacement(local)));
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
