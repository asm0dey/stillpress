package site.asm0dey.stillpress.content;

import com.fasterxml.jackson.databind.JsonNode;
import module java.base;
import org.jsoup.Jsoup;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.blocks.Block;
import site.asm0dey.stillpress.blocks.BlockParser;

/**
 * What HTML a role without {@code unfiltered_html} (authors, contributors) may not save: anything that can run
 * script. A save with such HTML is refused, never silently stripped.
 */
public final class HtmlPolicy {
    private static final Set<String> ELEMENTS =
            Set.of("script", "style", "object", "embed", "base", "form", "meta", "link");
    private static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "action", "formaction", "xlink:href");
    // embed hosts, exact or as a subdomain; wider than OEmbedProxy's lookup list, which only fetches oEmbed metadata
    private static final List<String> IFRAME_HOSTS =
            List.of("youtube.com", "youtube-nocookie.com", "youtu.be", "vimeo.com", "player.vimeo.com");
    private static final Pattern IGNORED = Pattern.compile("[\\s\\p{Cntrl}]");

    private HtmlPolicy() {
    }

    /**
     * Refuses with 400 {@code unsafe_html} when a role without {@link Capabilities#canPostUnfilteredHtml} saves
     * script-capable HTML; {@code field} names the first offending part.
     */
    static void check(Role role, String title, String body, String excerpt) {
        if (Capabilities.canPostUnfilteredHtml(role)) {
            return;
        }
        var parts = new LinkedHashMap<String, String>();
        parts.put("title", title);
        parts.put("body", body);
        parts.put("excerpt", excerpt);
        String field = null;
        var found = new LinkedHashSet<String>();
        for (var p : parts.entrySet()) {
            var v = violations(p.getValue());
            if (!v.isEmpty() && field == null) {
                field = p.getKey();
            }
            found.addAll(v);
        }
        if (field != null) {
            throw new ItemService.Refused(400, "unsafe_html", field, List.copyOf(found));
        }
    }

    /**
     * Short, stable descriptions of what is not allowed, empty when the HTML is allowed. The whole text is read as
     * HTML (block delimiters are comments to it), and string block attributes that contain {@code <} are read too.
     */
    public static List<String> violations(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        var out = new LinkedHashSet<String>();
        html(body, out);
        blocks(BlockParser.parse(body), out);
        return List.copyOf(out);
    }

    private static void blocks(List<Block> blocks, Set<String> out) {
        for (var b : blocks) {
            attributes(b.attrs(), out);
            blocks(b.innerBlocks(), out);
        }
    }

    private static void attributes(JsonNode node, Set<String> out) {
        if (node.isTextual() && node.textValue().contains("<")) {
            html(node.textValue(), out);
        }
        // object values and array elements
        for (var child : node) {
            attributes(child, out);
        }
    }

    private static void html(String html, Set<String> out) {
        for (var el : Jsoup.parseBodyFragment(html).body().getAllElements()) {
            var tag = el.normalName();
            if (ELEMENTS.contains(tag)) {
                out.add("<" + tag + "> element");
            }
            for (var a : el.attributes()) {
                var name = a.getKey().toLowerCase(Locale.ROOT);
                var value = squash(a.getValue());
                if (name.startsWith("on")) {
                    out.add(name + " attribute on <" + tag + ">");
                } else if (name.equals("srcdoc")) {
                    out.add("srcdoc attribute on <" + tag + ">");
                } else if (name.equals("style")) {
                    var css = value.replaceAll("[\"'\\\\]", "");
                    if (css.contains("expression(") || css.contains("url(javascript:")) {
                        out.add("script in style attribute on <" + tag + ">");
                    }
                } else if (URL_ATTRIBUTES.contains(name)) {
                    for (var scheme : List.of("javascript:", "vbscript:", "data:")) {
                        var dataImage = tag.equals("img") && name.equals("src") && value.startsWith("data:image/");
                        if (value.startsWith(scheme) && !dataImage) {
                            out.add(scheme + " URL in " + name);
                        }
                    }
                }
            }
            if (tag.equals("iframe")) {
                var host = host(squash(el.attr("src")));
                if (IFRAME_HOSTS
                    .stream()
                    .noneMatch(h -> host.equals(h) || host.endsWith("." + h))) {
                    out.add("<iframe> from " + (host.isEmpty() ? "no host" : host));
                }
            }
        }
    }

    /**
     * Lower case, without the whitespace and control characters browsers skip inside URLs.
     */
    private static String squash(String value) {
        return IGNORED.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String host(String url) {
        try {
            return Objects.requireNonNullElse(new URI(url).getHost(), "");
        } catch (URISyntaxException e) {
            return "";
        }
    }
}
