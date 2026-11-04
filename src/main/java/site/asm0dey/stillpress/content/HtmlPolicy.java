package site.asm0dey.stillpress.content;

import com.fasterxml.jackson.databind.JsonNode;
import module java.base;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
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
    // jsoup's parse error for an end tag with no open element: "Unexpected EndTag token [</div>] when in state …"
    private static final Pattern STRAY_END_TAG = Pattern.compile("Unexpected EndTag token \\[</([^>\\s]+)");
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
        var inserted = insertionPointsInHtml(body, all);
        Collections.sort(all);
        Collections.sort(asComments);
        return all.equals(asComments) && inserted ? List.of() : List.of("block comment inside HTML");
    }

    /**
     * Whether, where each delimiter stands, a block's rendered HTML (which may start with a letter) is read as HTML
     * content: not glued to a preceding {@code <}, {@code </} or {@code <!}, and not inside SVG or MathML.
     */
    private static boolean insertionPointsInHtml(String body, List<String> delimiters) {
        int from = 0;
        for (var d : delimiters) {
            int at = body.indexOf(d, from);
            from = at + d.length();
            var id = "sp-insert-" + UUID.randomUUID();
            var doc = Jsoup.parse(body.substring(0, at) + "x<sp-sentinel id=\"" + id + "\"></sp-sentinel>");
            var sentinel = doc.getElementById(id);
            if (sentinel == null || !sentinel.normalName().equals("sp-sentinel")) {
                return false;
            }
            for (var e : sentinel.parents()) {
                if (Set.of("svg", "math").contains(e.normalName())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code unclosed markup at the end} unless every tag, attribute, comment and raw-text element the fragment opens
     * is closed, so whatever follows it on a page (template markup, the next fragment) is parsed as written. A sentinel
     * element appended to the fragment must come out as the last child of {@code body}. The sentinel is a custom
     * element, which, unlike {@code <b>}, does not break out of SVG or MathML; it follows a letter, so a fragment
     * ending in {@code <} fails too. An end tag the fragment never opened
     * ({@code stray </div> end tag}) would close the page's own container instead, so it is refused too; a stray
     * {@code </p>} or {@code </br>} only adds an empty element.
     */
    public static List<String> balanced(String fragment) {
        if (fragment == null || fragment.isEmpty()) {
            return List.of();
        }
        var id = "sp-sentinel-" + UUID.randomUUID();
        var parser = Parser.htmlParser().setTrackErrors(Integer.MAX_VALUE);
        // the leading "x" turns a trailing "<" or "</" into a tag, as the next fragment's first letter would
        var doc = Jsoup.parse(fragment + "x<sp-sentinel id=\"" + id + "\"></sp-sentinel>", "", parser);
        var out = new LinkedHashSet<String>();
        for (var error : parser.getErrors()) {
            var m = STRAY_END_TAG.matcher(error.getErrorMessage());
            if (m.find() && !Set.of("p", "br", "sp-sentinel").contains(m.group(1).toLowerCase(Locale.ROOT))) {
                out.add("stray </" + m.group(1).toLowerCase(Locale.ROOT) + "> end tag");
            }
        }
        var sentinel = doc.getElementById(id);
        // a trailing "<" makes "x<sp-sentinel" one tag name that still carries the id
        if (sentinel == null || !sentinel.normalName().equals("sp-sentinel")) {
            out.add("unclosed markup at the end");
            return List.copyOf(out);
        }
        // open <p>s are harmless (wpautop leaves one): the next block-level element closes them by implied end tag
        for (Element e = sentinel; e != doc.body(); e = e.parent()) {
            if (e.nextSibling() != null || e != sentinel && !e.normalName().equals("p") || e.parent() == null) {
                out.add("unclosed markup at the end");
                break;
            }
        }
        return List.copyOf(out);
    }

    /**
     * Violations of HTML as it is served: the markup only, no block delimiters or shortcodes left to expand.
     */
    public static List<String> inPublished(String html) {
        var out = new LinkedHashSet<String>();
        if (html != null && !html.isEmpty()) {
            html(html, out);
        }
        return List.copyOf(out);
    }

    /**
     * Everything a fragment written by a lower role must pass on its own: {@link #violations}, {@link #balanced} and
     * {@link #structure}. Balanced, violation-free fragments stay safe when put side by side.
     */
    public static List<String> fragment(String fragment) {
        var out = new LinkedHashSet<>(violations(fragment));
        out.addAll(balanced(fragment));
        // what an excerpt or classic body publishes as; block bodies are also judged rendered (HtmlCheck)
        if (fragment != null && !fragment.isEmpty()) {
            out.addAll(balanced(ClassicContent.toHtml(fragment)));
        }
        out.addAll(structure(fragment));
        return List.copyOf(out);
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
