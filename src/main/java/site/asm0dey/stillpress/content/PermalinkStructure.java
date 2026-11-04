package site.asm0dey.stillpress.content;

import module java.base;

/**
 * WordPress permalink structures: rendering a structure for a post, and inferring the structure an imported blog
 * used from its posts' actual permalinks.
 */
public final class PermalinkStructure {
    private PermalinkStructure() {
    }

    public static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/";
    // order breaks ties: the more common structure wins
    private static final List<String> CANDIDATES = List.of(
            DEFAULT,
            "/%year%/%monthnum%/%postname%/",
            "/%postname%/",
            "/%category%/%postname%/",
            "/archives/%post_id%/"
    );

    /**
     * @param id the public (WordPress) post id
     * @param local the post's site-local time
     * @param categoryPath hierarchical slugs of the post's category, e.g. {@code travel/europe}
     * @param permalink the post's actual permalink, or null
     */
    public record Fields(
            long id,
            String slug,
            LocalDateTime local,
            String categoryPath,
            String authorSlug,
            String permalink
    ) {}

    public static String render(String structure, Fields f) {
        return structure
            .replace("%year%", "%04d".formatted(f.local().getYear()))
            .replace("%monthnum%", "%02d".formatted(f.local().getMonthValue()))
            .replace("%day%", "%02d".formatted(f.local().getDayOfMonth()))
            .replace("%hour%", "%02d".formatted(f.local().getHour()))
            .replace("%minute%", "%02d".formatted(f.local().getMinute()))
            .replace("%second%", "%02d".formatted(f.local().getSecond()))
            .replace("%postname%", f.slug())
            .replace("%post_id%", String.valueOf(f.id()))
            .replace("%category%", f.categoryPath())
            .replace("%author%", f.authorSlug());
    }

    /**
     * The candidate structure that reproduces the most posts' permalinks, if it reproduces at least half of them.
     */
    public static Optional<String> infer(List<Fields> posts) {
        String best = null;
        long bestCount = 0;
        for (var c : CANDIDATES) {
            var n = posts
                .stream()
                .filter(p -> render(c, p).equals(p.permalink()))
                .count();
            if (n > bestCount) {
                best = c;
                bestCount = n;
            }
        }
        return bestCount * 2 >= posts.size() ? Optional.ofNullable(best) : Optional.empty();
    }
}
