package site.asm0dey.stillpress.content;

import com.fasterxml.jackson.databind.JsonNode;
import module java.base;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import site.asm0dey.stillpress.blocks.Block;
import site.asm0dey.stillpress.blocks.BlockParser;
import site.asm0dey.stillpress.blocks.ClassicContent;

/**
 * What HTML a role without {@code unfiltered_html} (authors, contributors) may not save: anything that can run
 * script. A save with such HTML is refused, never silently stripped.
 */
public final class HtmlPolicy {
    private static final Set<String> ELEMENTS = Set.of(
            "script",
            "style",
            "object",
            "embed",
            "base",
            "form",
            "meta",
            "link",
            // parsed as raw text by browsers but as markup by jsoup, so its content cannot be judged
            "noscript",
            // SVG animation can set href to a script URL
            "animate",
            "set",
            "animatetransform"
    );
    private static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "action", "formaction", "xlink:href");
    // embed hosts, exact or as a subdomain; wider than OEmbedProxy's lookup list, which only fetches oEmbed metadata
    private static final List<String> IFRAME_HOSTS =
            List.of("youtube.com", "youtube-nocookie.com", "youtu.be", "vimeo.com", "player.vimeo.com");
    // block attributes a renderer may write into a link
    private static final Set<String> URL_KEYS = Set.of("url", "href", "src", "link");
    private static final Pattern IGNORED = Pattern.compile("[\\s\\p{Cntrl}]");

    private HtmlPolicy() {
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
        // classic bodies are published through shortcodes and wpautop, which can build markup out of attribute text
        html(ClassicContent.toHtml(body), out);
        blocks(BlockParser.parse(body), out);
        return List.copyOf(out);
    }

    /**
     * {@code block comment inside HTML} when a block delimiter is not a comment node of the HTML: inside a tag, an
     * attribute value or raw text. The renderer removes delimiters wherever they are, so the published markup would
     * differ from the one judged.
     */
    public static List<String> structure(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        var asComments = new ArrayList<String>();
        Jsoup.parse(body).traverse((node, depth) -> {
            if (node instanceof Comment c) {
                asComments.addAll(BlockParser.delimiters("<!--" + c.getData() + "-->"));
            }
        });
        var all = new ArrayList<>(BlockParser.delimiters(body));
        Collections.sort(all);
        Collections.sort(asComments);
        return all.equals(asComments) ? List.of() : List.of("block comment inside HTML");
    }

    private static void blocks(List<Block> blocks, Set<String> out) {
        for (var b : blocks) {
            attributes(b.attrs(), "", out);
            blocks(b.innerBlocks(), out);
        }
    }

    private static void attributes(JsonNode node, String key, Set<String> out) {
        if (node.isTextual() && node.textValue().contains("<")) {
            html(node.textValue(), out);
        }
        if (node.isTextual() && URL_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
            // a renderer may write an attribute (an embed's url) into a link
            var value = squash(node.textValue());
            for (var scheme : List.of("javascript:", "vbscript:")) {
                if (value.startsWith(scheme)) {
                    out.add(scheme + " URL in a block attribute");
                }
            }
        }
        if (node.isObject()) {
            node
                .properties()
                .forEach(e -> attributes(e.getValue(), e.getKey(), out));
        } else {
            // array elements keep the array's key
            for (var child : node) {
                attributes(child, key, out);
            }
        }
    }

    private static void html(String html, Set<String> out) {
        for (var el : Jsoup.parse(html).getAllElements()) {
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
